"""
Rank provisioning entry point.

Uses the secure bridge API (HMAC-signed HTTPS) with a durable job queue.
RCON fallback is available for local development when RANK_USE_RCON_FALLBACK=true.
"""
from orders.rank_provisioning import enqueue_rank_provision, process_rank_job, process_pending_rank_jobs

__all__ = ['apply_rank', 'enqueue_rank_provision', 'process_rank_job', 'process_pending_rank_jobs']


def apply_rank(order_id):
    """Enqueue rank provisioning for an order (non-blocking, with automatic retry)."""
    try:
        enqueue_rank_provision(order_id)
        return True
    except Exception:
        return False
