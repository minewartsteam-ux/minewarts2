package ir.minewarts.rankbridge.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class NonceStore {

    private final long ttlMillis;
    private final Map<String, Long> seen = new ConcurrentHashMap<>();

    public NonceStore(int ttlSeconds) {
        this.ttlMillis = ttlSeconds * 1000L;
    }

    public void assertFresh(String nonce) {
        if (nonce == null || nonce.isBlank()) {
            throw new SecurityException("Missing X-Nonce");
        }
        purgeExpired();
        
        Long existing = seen.putIfAbsent(nonce, System.currentTimeMillis());
        if (existing != null) {
            throw new SecurityException("Replay detected: nonce already used");
        }
    }

    private void purgeExpired() {
        long cutoff = System.currentTimeMillis() - ttlMillis;
        seen.entrySet().removeIf(e -> e.getValue() < cutoff);
    }
    
    // برای پاکسازی دستی در زمان disable
    public void clear() {
        seen.clear();
    }
}