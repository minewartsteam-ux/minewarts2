package ir.minewarts.rankbridge.http;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import ir.minewarts.rankbridge.RankBridgePlugin;
import ir.minewarts.rankbridge.rank.LuckPermsService;
import ir.minewarts.rankbridge.security.HmacVerifier;
import ir.minewarts.rankbridge.security.IdempotencyStore;
import ir.minewarts.rankbridge.security.NonceStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ProvisionHandler implements HttpHandler {

    private final RankBridgePlugin plugin;
    private final HmacVerifier verifier;
    private final NonceStore nonceStore;
    private final IdempotencyStore idempotencyStore;
    private final LuckPermsService luckPermsService;
    private final List<String> allowedIps;

    public ProvisionHandler(
            RankBridgePlugin plugin,
            HmacVerifier verifier,
            NonceStore nonceStore,
            IdempotencyStore idempotencyStore,
            LuckPermsService luckPermsService,
            List<String> allowedIps
    ) {
        this.plugin = plugin;
        this.verifier = verifier;
        this.nonceStore = nonceStore;
        this.idempotencyStore = idempotencyStore;
        this.luckPermsService = luckPermsService;
        this.allowedIps = allowedIps;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        long startTime = System.currentTimeMillis();
        String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();
        
        plugin.getLogger().info("📥 Request from " + clientIp + " -> " + exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());

        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                // اصلاح شده: اضافه شدن .toString()
                send(exchange, 405, error("method_not_allowed", "Only POST method is allowed").toString());
                return;
            }

            if (!allowedIps.isEmpty() && !allowedIps.contains(clientIp)) {
                plugin.getLogger().warning("❌ IP not allowed: " + clientIp);
                send(exchange, 403, error("forbidden_ip", "IP " + clientIp + " is not allowed").toString());
                return;
            }

            String timestamp = exchange.getRequestHeaders().getFirst("X-Timestamp");
            String nonce = exchange.getRequestHeaders().getFirst("X-Nonce");
            String idempotencyKey = exchange.getRequestHeaders().getFirst("X-Idempotency-Key");
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String path = exchange.getRequestURI().getPath();

            if (timestamp == null || timestamp.isBlank()) {
                send(exchange, 400, error("missing_header", "X-Timestamp header is required").toString());
                return;
            }
            if (nonce == null || nonce.isBlank()) {
                send(exchange, 400, error("missing_header", "X-Nonce header is required").toString());
                return;
            }
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                send(exchange, 400, error("missing_header", "X-Idempotency-Key header is required").toString());
                return;
            }
            if (auth == null || auth.isBlank()) {
                send(exchange, 400, error("missing_header", "Authorization header is required").toString());
                return;
            }

            String body = readBody(exchange.getRequestBody());
            if (body == null || body.isBlank()) {
                send(exchange, 400, error("empty_body", "Request body cannot be empty").toString());
                return;
            }

            try {
                verifier.verifyTimestamp(timestamp);
                nonceStore.assertFresh(nonce);
                verifier.verifyAuthorization(auth, "POST", path, timestamp, nonce, body);
            } catch (SecurityException e) {
                plugin.getLogger().warning("🔒 Security rejection: " + e.getMessage());
                send(exchange, 403, error("security_error", e.getMessage()).toString());
                return;
            }

            if (idempotencyStore.has(idempotencyKey)) {
                JsonObject cached = idempotencyStore.get(idempotencyKey);
                if (cached != null) {
                    plugin.getLogger().info("🔄 Idempotent request: " + idempotencyKey);
                    send(exchange, 200, cached.toString());
                    return;
                }
            }

            JsonObject payload;
            try {
                payload = JsonParser.parseString(body).getAsJsonObject();
            } catch (JsonSyntaxException e) {
                plugin.getLogger().warning("❌ Invalid JSON: " + e.getMessage());
                send(exchange, 400, error("invalid_json", "Invalid JSON payload: " + e.getMessage()).toString());
                return;
            }

            if (!payload.has("minecraft_username")) {
                send(exchange, 422, error("missing_field", "minecraft_username is required").toString());
                return;
            }
            if (!payload.has("game_rank_group")) {
                send(exchange, 422, error("missing_field", "game_rank_group is required").toString());
                return;
            }

            String username = payload.get("minecraft_username").getAsString();
            String group = payload.get("game_rank_group").getAsString();
            
            // ✅ تغییرات اینجا: دریافت تعداد ماه با پیش‌فرض 1
            int months = 1;
            if (payload.has("duration_months")) {
                months = payload.get("duration_months").getAsInt();
            } else if (payload.has("duration_days")) {
                // پشتیبانی از سایت‌هایی که روز ارسال میکنند (اختیاری)
                int days = payload.get("duration_days").getAsInt();
                months = days / 30; // تبدیل تقریبی به ماه
                if (months < 1) months = 1;
            }

            boolean clear = payload.has("clear_existing_parents") 
                    && payload.get("clear_existing_parents").getAsBoolean();

            if (username == null || username.isBlank()) {
                send(exchange, 422, error("invalid_field", "minecraft_username cannot be empty").toString());
                return;
            }
            if (group == null || group.isBlank()) {
                send(exchange, 422, error("invalid_field", "game_rank_group cannot be empty").toString());
                return;
            }
            if (months <= 0 || months > 12) {
                send(exchange, 422, error("invalid_field", "duration_months must be between 1 and 12").toString());
                return;
            }

            // تبدیل ماه به روز برای LuckPerms (هر ماه 30 روز محاسبه شده)
            int daysToGrant = months * 30;

            plugin.getLogger().info("🎯 Processing: " + username + " -> " + group + " (" + months + " months / " + daysToGrant + " days, clear=" + clear + ")");
            
            JsonObject result = luckPermsService.grantTempParent(username, group, daysToGrant, clear);

            idempotencyStore.save(idempotencyKey, result);

            long duration = System.currentTimeMillis() - startTime;
            plugin.getLogger().info("✅ Request completed in " + duration + "ms");

            send(exchange, 200, result.toString());

        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("❌ Invalid request: " + e.getMessage());
            send(exchange, 422, error("invalid_request", e.getMessage()).toString());
        } catch (IllegalStateException e) {
            plugin.getLogger().warning("❌ State error: " + e.getMessage());
            send(exchange, 503, error("server_error", e.getMessage()).toString());
        } catch (Exception e) {
            plugin.getLogger().severe("❌ Server error: " + e.getMessage());
            e.printStackTrace();
            send(exchange, 503, error("server_error", "Internal server error").toString());
        }
    }

    private static String readBody(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static JsonObject error(String code, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", code);
        o.addProperty("message", message);
        return o;
    }

    private static void send(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}