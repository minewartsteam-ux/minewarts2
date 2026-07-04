package ir.minewarts.rankbridge.security;

import com.google.gson.JsonObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class IdempotencyStore {

    private final Map<String, JsonObject> results = new ConcurrentHashMap<>();
    private final long maxAgeMs = 300_000; // 5 minutes

    public boolean has(String key) {
        if (key == null || key.isBlank()) return false;
        cleanup();
        return results.containsKey(key);
    }

    public JsonObject get(String key) {
        if (key == null || key.isBlank()) return null;
        cleanup();
        return results.get(key);
    }

    public void save(String key, JsonObject result) {
        if (key == null || key.isBlank() || result == null) return;
        cleanup();
        results.put(key, result);
        
        // اگر تعداد آیتم‌ها زیاد شد، پاکسازی کامل
        if (results.size() > 1000) {
            results.clear();
        }
    }

    private void cleanup() {
        // حذف آیتم‌های قدیمی (در این پیاده‌سازی ساده، 
        // فقط اگر خیلی بزرگ شد پاک می‌کنیم)
        if (results.size() > 2000) {
            results.clear();
        }
    }
}