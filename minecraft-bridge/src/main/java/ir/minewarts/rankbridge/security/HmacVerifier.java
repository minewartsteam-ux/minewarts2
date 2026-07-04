package ir.minewarts.rankbridge.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public class HmacVerifier {

    private final byte[] secret;
    private final String shopId;
    private final int maxSkewSeconds;

    public HmacVerifier(String secret, String shopId, int maxSkewSeconds) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.shopId = shopId;
        this.maxSkewSeconds = maxSkewSeconds;
    }

    public void verifyTimestamp(String timestampHeader) {
        if (timestampHeader == null || timestampHeader.isBlank()) {
            throw new SecurityException("Missing X-Timestamp");
        }
        
        long now = System.currentTimeMillis() / 1000L;
        long ts;
        try {
            ts = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            throw new SecurityException("Invalid X-Timestamp format");
        }
        
        if (Math.abs(now - ts) > maxSkewSeconds) {
            throw new SecurityException("Timestamp outside allowed window (max " + maxSkewSeconds + "s)");
        }
    }

    public void verifyAuthorization(String authHeader, String method, String path,
                                    String timestamp, String nonce, String body) {
        if (authHeader == null || authHeader.isBlank()) {
            throw new SecurityException("Missing Authorization header");
        }
        
        if (!authHeader.startsWith("HMAC-SHA256 ")) {
            throw new SecurityException("Invalid Authorization scheme, expected HMAC-SHA256");
        }
        
        // استخراج Credential و Signature
        String authPart = authHeader.substring("HMAC-SHA256 ".length()).trim();
        String credential = null;
        String signature = null;
        
        for (String part : authPart.split(",")) {
            part = part.trim();
            if (part.startsWith("Credential=")) {
                credential = part.substring("Credential=".length()).trim();
            } else if (part.startsWith("Signature=")) {
                signature = part.substring("Signature=".length()).trim();
            }
        }
        
        // بررسی Credential
        if (credential == null || !credential.equals(shopId)) {
            throw new SecurityException("Invalid Credential: expected " + shopId);
        }
        
        if (signature == null || signature.isBlank()) {
            throw new SecurityException("Missing Signature in Authorization header");
        }
        
        // محاسبه امضای مورد انتظار
        String expected = sign(method, path, timestamp, nonce, body);
        
        // مقایسه امن
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("Invalid signature");
        }
    }

    public String sign(String method, String path, String timestamp, String nonce, String body) {
        try {
            String bodyHash = sha256Hex(body != null ? body : "");
            String canonical = method.toUpperCase() + "\n" + 
                              path + "\n" + 
                              timestamp + "\n" + 
                              nonce + "\n" + 
                              bodyHash;
            
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] raw = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new SecurityException("HMAC computation failed: " + e.getMessage(), e);
        }
    }

    private static String sha256Hex(String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new SecurityException("Body hash failed: " + e.getMessage(), e);
        }
    }
}