package ir.minewarts.rankbridge.rank;

import com.google.gson.JsonObject;
import ir.minewarts.rankbridge.RankBridgePlugin;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class LuckPermsService {

    private static final Pattern USERNAME = Pattern.compile("^[a-zA-Z0-9_]{1,16}$");

    private final RankBridgePlugin plugin;
    private final List<String> allowedGroups;

    public LuckPermsService(RankBridgePlugin plugin, List<String> allowedGroups) {
        this.plugin = plugin;
        this.allowedGroups = allowedGroups;
    }

    public JsonObject grantTempParent(String username, String group, int days, boolean clearExisting)
            throws Exception {
        
        // ✅ Validate username
        if (!USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("Invalid minecraft username: " + username);
        }
        
        // ✅ Check if group is allowed
        if (!allowedGroups.isEmpty() && !allowedGroups.contains(group)) {
            throw new SecurityException("Group not allowed: " + group + ". Allowed: " + allowedGroups);
        }
        
        // ✅ Validate days
        if (days < 1) {
            throw new IllegalArgumentException("duration_days must be at least 1");
        }
        if (days > 365) {
            throw new IllegalArgumentException("duration_days cannot exceed 365");
        }

        // ✅ Get LuckPerms instance
        LuckPerms lp = LuckPermsProvider.get();
        
        // ✅ Check if group exists in LuckPerms
        Group lpGroup = lp.getGroupManager().getGroup(group);
        if (lpGroup == null) {
            plugin.getLogger().warning("⚠️ Group '" + group + "' does not exist in LuckPerms!");
            throw new IllegalArgumentException("Group '" + group + "' does not exist in LuckPerms");
        }
        
        // ✅ Get or create UUID for username
        UUID uuid = null;
        
        // Method 1: Try to get from offline player
        @SuppressWarnings("deprecation")
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(username);
        if (offlinePlayer != null && offlinePlayer.hasPlayedBefore()) {
            uuid = offlinePlayer.getUniqueId();
            plugin.getLogger().info("✅ Found player via OfflinePlayer: " + username + " (" + uuid + ")");
        }
        
        // Method 2: Try to get from LuckPerms user manager
        if (uuid == null) {
            plugin.getLogger().info("🔍 Player not found in OfflinePlayer, searching in LuckPerms...");
            // Load user and get UUID from LuckPerms data
            CompletableFuture<User> loadFuture = lp.getUserManager().loadUser(username);
            User tempUser = null;
            try {
                tempUser = loadFuture.get(10, TimeUnit.SECONDS);
                if (tempUser != null) {
                    // User exists in LuckPerms, get UUID from their data
                    uuid = tempUser.getUniqueId();
                    plugin.getLogger().info("✅ Found player via LuckPerms: " + username + " (" + uuid + ")");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("⚠️ Could not load user from LuckPerms: " + e.getMessage());
            }
        }
        
        // Method 3: If still null, try to create a new user with the username
        if (uuid == null) {
            plugin.getLogger().info("🆕 Player not found anywhere, creating new user: " + username);
            // Generate a UUID from username (consistent method)
            uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes());
            plugin.getLogger().info("✅ Generated UUID: " + uuid + " for user: " + username);
        }

        // ✅ Load user with the found UUID
        CompletableFuture<User> loadUserFuture = lp.getUserManager().loadUser(uuid, username);
        User user = loadUserFuture.get(15, TimeUnit.SECONDS);

        if (user == null) {
            throw new IllegalStateException("Could not load or create user: " + username);
        }

        // ✅ Clear existing inheritance if requested
        if (clearExisting) {
            plugin.getLogger().info("🧹 Clearing existing inheritance for " + username);
            user.data().clear(NodeType.INHERITANCE);
        }

        // ✅ Create and add node with proper expiration
        InheritanceNode node = InheritanceNode.builder(group)
                .expiry(Duration.ofDays(days))
                .build();
        user.data().add(node);

        // ✅ Save user
        lp.getUserManager().saveUser(user).get(15, TimeUnit.SECONDS);

        // ✅ Create response with detailed info
        JsonObject result = new JsonObject();
        result.addProperty("status", "success");
        result.addProperty("username", username);
        result.addProperty("uuid", uuid.toString());
        result.addProperty("group", group);
        result.addProperty("duration_days", days);
        result.addProperty("duration_seconds", days * 24 * 60 * 60);
        result.addProperty("clear_existing", clearExisting);
        result.addProperty("message", "Rank " + group + " granted to " + username + " for " + days + " days");
        
        // Add expiration date (optional)
        long expiryTime = System.currentTimeMillis() + (days * 24L * 60L * 60L * 1000L);
        result.addProperty("expires_at", expiryTime);

        plugin.getLogger().info("✅ Rank " + group + " granted to " + username + " for " + days + " days (expires in " + days + " days)");
        
        return result;
    }
}