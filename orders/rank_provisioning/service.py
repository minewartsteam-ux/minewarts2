import logging
import os
import threading
from datetime import timedelta

from django.conf import settings
from django.db import transaction, connections
from django.utils import timezone

from shop.models import ProductMonth
from orders.models import Order, RankProvisionJob
from .mapper import RankMapper, UnknownWebRankError
from .bridge_client import BridgeClient, BridgeError, BridgeRetryableError

logger = logging.getLogger(__name__)

RETRY_BACKOFF_MINUTES = (1, 5, 15, 60, 360)


def _compute_duration_months(rank_item):
    """
    تعداد ماه‌ها را از آیتم سفارش استخراج می‌کند.
    پیش‌فرض ۱ ماه است.
    """
    if rank_item.month_option_id:
        try:
            month_option = ProductMonth.objects.get(id=rank_item.month_option_id)
            return month_option.months
        except ProductMonth.DoesNotExist:
            return 1
    return 1


def _get_rank_item(order):
    for item in order.items.select_related('product').all():
        if item.product.product_type == 'rank':
            return item
    return None


def _build_payload(order, rank_item, grant):
    """
    ساختار Payload حالا دقیقاً همان چیزی است که پلاگین جاوا انتظار دارد.
    duration_months ارسال می‌شود نه duration_days.
    """
    return {
        'order_id': str(order.id),
        'minecraft_username': order.minecraft_username,
        'web_rank_slug': grant.web_slug,
        'game_rank_group': grant.lp_group,
        'duration_months': _compute_duration_months(rank_item),  # ✅ تغییر یافته به ماه
        'clear_existing_parents': grant.clear_existing,
        'action': 'grant_temp_parent',
    }


def _apply_via_rcon(payload):
    """Legacy fallback when bridge is not configured (local dev only)."""
    from mcrcon import MCRcon

    password = os.getenv('MINECRAFT_RCON_PASSWORD')
    if not password:
        raise BridgeError('Neither rank bridge nor MINECRAFT_RCON_PASSWORD is configured')

    host = os.getenv('MINECRAFT_RCON_HOST', '127.0.0.1')
    port = int(os.getenv('MINECRAFT_RCON_PORT', 25575))
    username = payload['minecraft_username']
    group = payload['game_rank_group']
    
    # ✅ محاسبه روز برای RCON از روی ماه
    months = payload.get('duration_months', 1)
    days = months * 30

    with MCRcon(host, password, port) as mcr:
        if payload.get('clear_existing_parents', True):
            mcr.command(f'lp user {username} parent clear')
        mcr.command(f'lp user {username} parent addtemp {group} {days}d')

    return {'status': 'applied', 'via': 'rcon'}


def _process_job_wrapper(job_id):
    """
    یک Wrapper برای اجرای Job در Thread جداگانه.
    این تابع حتماً کانکشن‌های دیتابیس را در پایان کار می‌بندد تا باگ Leaked Connection در جنگو رخ ندهد.
    """
    try:
        process_rank_job(job_id)
    finally:
        # ✅ بستن کانکشن‌های دیتابیس مخصوص این Thread
        connections.close_all()


def process_rank_job(job_id):
    try:
        job = RankProvisionJob.objects.select_related('order').get(id=job_id)
    except RankProvisionJob.DoesNotExist:
        logger.error('RankProvisionJob %s not found', job_id)
        return False

    if job.status in (RankProvisionJob.STATUS_APPLIED, RankProvisionJob.STATUS_FAILED, RankProvisionJob.STATUS_DEAD_LETTER):
        return job.status == RankProvisionJob.STATUS_APPLIED

    if job.status == RankProvisionJob.STATUS_RETRY and job.next_retry_at and job.next_retry_at > timezone.now():
        return False

    order = job.order
    job.status = RankProvisionJob.STATUS_PROCESSING
    job.attempts += 1
    job.save(update_fields=['status', 'attempts', 'updated'])

    payload = job.payload
    client = BridgeClient()

    try:
        if client.enabled:
            result = client.provision_rank(payload, job.idempotency_key)
        elif settings.RANK_USE_RCON_FALLBACK:
            result = _apply_via_rcon(payload)
        else:
            raise BridgeError('Rank bridge not configured. Set RANK_BRIDGE_URL and RANK_BRIDGE_HMAC_SECRET.')

        job.status = RankProvisionJob.STATUS_APPLIED
        job.bridge_response = result
        job.last_error = ''
        job.next_retry_at = None
        job.save(update_fields=['status', 'bridge_response', 'last_error', 'next_retry_at', 'updated'])

        order.rank_applied = True
        order.rank_error = ''
        order.save(update_fields=['rank_applied', 'rank_error', 'updated'])
        logger.info('Rank applied for order %s via job %s', order.id, job.id)
        return True

    except BridgeRetryableError as exc:
        max_retries = settings.RANK_MAX_RETRIES
        job.last_error = str(exc)
        job.bridge_response = {'error': str(exc), 'status_code': exc.status_code}

        if job.attempts >= max_retries:
            job.status = RankProvisionJob.STATUS_DEAD_LETTER
            order.rank_error = f'Max retries exceeded: {exc}'
            order.save(update_fields=['rank_error', 'updated'])
        else:
            backoff_idx = min(job.attempts - 1, len(RETRY_BACKOFF_MINUTES) - 1)
            minutes = RETRY_BACKOFF_MINUTES[backoff_idx]
            job.status = RankProvisionJob.STATUS_RETRY
            job.next_retry_at = timezone.now() + timedelta(minutes=minutes)

        job.save(update_fields=['status', 'last_error', 'bridge_response', 'next_retry_at', 'updated'])
        logger.warning('Rank job %s retry scheduled: %s', job.id, exc)
        return False

    except (BridgeError, UnknownWebRankError) as exc:
        job.status = RankProvisionJob.STATUS_FAILED
        job.last_error = str(exc)
        job.save(update_fields=['status', 'last_error', 'updated'])
        order.rank_error = str(exc)
        order.save(update_fields=['rank_error', 'updated'])
        logger.error('Rank job %s failed permanently: %s', job.id, exc)
        return False

    except Exception as exc:
        job.status = RankProvisionJob.STATUS_RETRY
        job.last_error = str(exc)
        job.next_retry_at = timezone.now() + timedelta(minutes=RETRY_BACKOFF_MINUTES[0])
        job.save(update_fields=['status', 'last_error', 'next_retry_at', 'updated'])
        logger.exception('Unexpected error in rank job %s', job.id)
        return False


def enqueue_rank_provision(order_id):
    order = Order.objects.prefetch_related('items__product').get(id=order_id)
    rank_item = _get_rank_item(order)

    if not rank_item:
        order.rank_applied = True
        order.save(update_fields=['rank_applied', 'updated'])
        return None

    if not order.minecraft_username:
        order.rank_error = 'Minecraft username is missing'
        order.save(update_fields=['rank_error', 'updated'])
        return None

    mapper = RankMapper()
    grant = mapper.from_product(rank_item.product)
    payload = _build_payload(order, rank_item, grant)
    idempotency_key = f'order-{order.id}-rank-{grant.lp_group}-v1'

    existing = RankProvisionJob.objects.filter(idempotency_key=idempotency_key).first()
    if existing:
        if existing.status != RankProvisionJob.STATUS_APPLIED:
            # ✅ استفاده از Wrapper بجای Thread مستقیم
            threading.Thread(target=_process_job_wrapper, args=(existing.id,), daemon=True).start()
        return existing

    with transaction.atomic():
        job = RankProvisionJob.objects.create(
            order=order,
            idempotency_key=idempotency_key,
            payload=payload,
            status=RankProvisionJob.STATUS_PENDING,
        )

    # ✅ استفاده از Wrapper بجای Thread مستقیم
    threading.Thread(target=_process_job_wrapper, args=(job.id,), daemon=True).start()
    return job


def process_pending_rank_jobs(limit=50):
    from django.db.models import Q

    now = timezone.now()
    jobs = RankProvisionJob.objects.filter(
        Q(status=RankProvisionJob.STATUS_PENDING)
        | Q(status=RankProvisionJob.STATUS_RETRY, next_retry_at__lte=now)
        | Q(status=RankProvisionJob.STATUS_RETRY, next_retry_at__isnull=True)
    ).order_by('next_retry_at', 'created')[:limit]

    processed = 0
    for job in jobs:
        if process_rank_job(job.id):
            processed += 1
    return processed