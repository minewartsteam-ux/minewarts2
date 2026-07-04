# RankBridge — Minecraft Server Plugin

Secure HTTPS bridge between the Django web shop and LuckPerms.

## Build

```bash
cd minecraft-bridge
mvn package
```

Copy `target/rank-bridge-1.0.0.jar` to your Paper server's `plugins/` folder.

## Configure

1. Edit `plugins/RankBridge/config.yml`:
   - Set `hmac-secret` to the same value as `RANK_BRIDGE_HMAC_SECRET` on the shop
   - Add your shop VPS egress IP to `allowed-shop-ips`
   - List LuckPerms groups in `allowed-groups`

2. Put Nginx in front (TLS on 443 → proxy to 127.0.0.1:8080):

```nginx
location /v1/ {
    allow SHOP_EGRESS_IP;
    deny all;
    proxy_pass http://127.0.0.1:8080;
}
```

## Shop `.env`

```env
RANK_BRIDGE_URL=https://bridge.yourdomain.com
RANK_BRIDGE_HMAC_SECRET=your-long-random-secret
RANK_BRIDGE_SHOP_ID=minewarts-shop
```

## Cron (shop VPS)

```cron
*/2 * * * * cd /path/to/shop && python manage.py process_rank_jobs
```

## Local dev fallback

```env
RANK_USE_RCON_FALLBACK=true
MINECRAFT_RCON_HOST=127.0.0.1
MINECRAFT_RCON_PORT=25575
MINECRAFT_RCON_PASSWORD=your-rcon-password
```
