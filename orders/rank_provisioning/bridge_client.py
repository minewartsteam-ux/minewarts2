import hashlib
import hmac
import json
import logging
import uuid
from urllib.parse import urlparse

import requests
from django.conf import settings

logger = logging.getLogger(__name__)


class BridgeError(Exception):
    def __init__(self, message, status_code=None, response_body=None):
        super().__init__(message)
        self.status_code = status_code
        self.response_body = response_body


class BridgeRetryableError(BridgeError):
    pass


class BridgeClient:
    def __init__(
        self,
        base_url=None,
        secret=None,
        shop_id=None,
        timeout=None,
    ):
        self.base_url = (base_url or settings.RANK_BRIDGE_URL).rstrip('/')
        self.secret = secret or settings.RANK_BRIDGE_HMAC_SECRET
        self.shop_id = shop_id or settings.RANK_BRIDGE_SHOP_ID
        self.timeout = timeout or settings.RANK_BRIDGE_TIMEOUT

    @property
    def enabled(self):
        return bool(self.base_url and self.secret)

    def _canonical_string(self, method, path, timestamp, nonce, body):
        body_hash = hashlib.sha256(body.encode('utf-8')).hexdigest()
        return f'{method.upper()}\n{path}\n{timestamp}\n{nonce}\n{body_hash}'

    def _sign(self, canonical):
        return hmac.new(
            self.secret.encode('utf-8'),
            canonical.encode('utf-8'),
            hashlib.sha256,
        ).hexdigest()

    def provision_rank(self, payload, idempotency_key):
        if not self.enabled:
            raise BridgeError('Rank bridge is not configured (RANK_BRIDGE_URL / RANK_BRIDGE_HMAC_SECRET)')

        path = '/v1/ranks/provision'
        url = f'{self.base_url}{path}'
        body = json.dumps(payload, separators=(',', ':'), ensure_ascii=False)
        timestamp = str(int(__import__('time').time()))
        nonce = str(uuid.uuid4())

        canonical = self._canonical_string('POST', path, timestamp, nonce, body)
        signature = self._sign(canonical)

        headers = {
            'Content-Type': 'application/json',
            'X-Shop-Id': self.shop_id,
            'X-Timestamp': timestamp,
            'X-Nonce': nonce,
            'X-Idempotency-Key': idempotency_key,
            'Authorization': f'HMAC-SHA256 Credential={self.shop_id}, Signature={signature}',
        }

        verify_ssl = getattr(settings, 'RANK_BRIDGE_VERIFY_SSL', True)

        try:
            response = requests.post(
                url,
                data=body.encode('utf-8'),
                headers=headers,
                timeout=self.timeout,
                verify=verify_ssl,
            )
        except requests.Timeout as exc:
            raise BridgeRetryableError('Bridge request timed out') from exc
        except requests.ConnectionError as exc:
            raise BridgeRetryableError(f'Bridge connection failed: {exc}') from exc

        if response.status_code in (502, 503, 504):
            raise BridgeRetryableError(
                f'Bridge unavailable ({response.status_code})',
                status_code=response.status_code,
                response_body=response.text,
            )

        if response.status_code == 409:
            return response.json() if response.content else {'status': 'already_applied'}

        if response.status_code == 200:
            return response.json() if response.content else {'status': 'applied'}

        if response.status_code in (401, 403):
            raise BridgeError(
                'Bridge authentication failed — check HMAC secret and shop ID',
                status_code=response.status_code,
                response_body=response.text,
            )

        if response.status_code == 422:
            raise BridgeError(
                f'Bridge rejected request: {response.text}',
                status_code=response.status_code,
                response_body=response.text,
            )

        if response.status_code >= 500:
            raise BridgeRetryableError(
                f'Bridge server error ({response.status_code})',
                status_code=response.status_code,
                response_body=response.text,
            )

        raise BridgeError(
            f'Unexpected bridge response ({response.status_code}): {response.text}',
            status_code=response.status_code,
            response_body=response.text,
        )
