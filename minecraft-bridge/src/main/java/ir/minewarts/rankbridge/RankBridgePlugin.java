package ir.minewarts.rankbridge;

import com.sun.net.httpserver.HttpServer;
import ir.minewarts.rankbridge.http.ProvisionHandler;
import ir.minewarts.rankbridge.rank.LuckPermsService;
import ir.minewarts.rankbridge.security.HmacVerifier;
import ir.minewarts.rankbridge.security.IdempotencyStore;
import ir.minewarts.rankbridge.security.NonceStore;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.logging.Level;

public class RankBridgePlugin extends JavaPlugin {

    private HttpServer httpServer;
    private NonceStore nonceStore;
    private IdempotencyStore idempotencyStore;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        String secret = getConfig().getString("hmac-secret", "");
        if (secret.isBlank() || "CHANGE_ME_USE_A_LONG_RANDOM_SECRET".equals(secret)) {
            getLogger().severe("❌ Set hmac-secret in config.yml before enabling RankBridge!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        String shopId = getConfig().getString("shop-id", "minewarts-shop");
        List<String> allowedIps = getConfig().getStringList("allowed-shop-ips");
        List<String> allowedGroups = getConfig().getStringList("allowed-groups");
        int skew = getConfig().getInt("security.timestamp-skew-seconds", 300);
        int nonceTtl = getConfig().getInt("security.nonce-ttl-seconds", 600);
        String host = getConfig().getString("http.host", "127.0.0.1");
        int port = getConfig().getInt("http.port", 8080);

        getLogger().info("========================================");
        getLogger().info("🚀 RankBridge Plugin Loading...");
        getLogger().info("========================================");
        getLogger().info("🔹 Shop ID: " + shopId);
        getLogger().info("🔹 Allowed IPs: " + (allowedIps.isEmpty() ? "ALL (⚠️ not recommended)" : allowedIps));
        getLogger().info("🔹 Allowed Groups: " + (allowedGroups.isEmpty() ? "ALL (⚠️ not recommended)" : allowedGroups));
        getLogger().info("🔹 HTTP Server: http://" + host + ":" + port);
        getLogger().info("🔹 Timestamp Skew: " + skew + "s");
        getLogger().info("🔹 Nonce TTL: " + nonceTtl + "s");
        getLogger().info("========================================");

        HmacVerifier verifier = new HmacVerifier(secret, shopId, skew);
        this.nonceStore = new NonceStore(nonceTtl);
        this.idempotencyStore = new IdempotencyStore();
        LuckPermsService luckPermsService = new LuckPermsService(this, allowedGroups);

        try {
            httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
            httpServer.createContext(
                    "/v1/ranks/provision",
                    new ProvisionHandler(
                            this,
                            verifier,
                            nonceStore,
                            idempotencyStore,
                            luckPermsService,
                            allowedIps
                    )
            );
            httpServer.setExecutor(null);
            httpServer.start();
            getLogger().info("✅ RankBridge HTTP server started on http://" + host + ":" + port);
            getLogger().info("✅ Plugin enabled successfully!");
            getLogger().info("========================================");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "❌ Failed to start HTTP server", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (httpServer != null) {
            httpServer.stop(0);
            getLogger().info("✅ RankBridge HTTP server stopped");
        }
        if (nonceStore != null) {
            nonceStore.clear();
        }
        getLogger().info("✅ RankBridge disabled");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("rankbridge")) {
            return false;
        }

        if (!sender.hasPermission("rankbridge.admin")) {
            sender.sendMessage(ChatColor.RED + "❌ You don't have permission to use this command!");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "📋 RankBridge Commands:");
            sender.sendMessage(ChatColor.GRAY + "/rankbridge status - Show plugin status");
            sender.sendMessage(ChatColor.GRAY + "/rankbridge reload - Reload configuration");
            sender.sendMessage(ChatColor.GRAY + "/rankbridge test <player> <group> <days> - Test rank grant");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "status":
                sender.sendMessage(ChatColor.GREEN + "✅ RankBridge Status:");
                sender.sendMessage(ChatColor.GRAY + "  - Status: " + (httpServer != null ? ChatColor.GREEN + "Running" : ChatColor.RED + "Stopped"));
                sender.sendMessage(ChatColor.GRAY + "  - Port: " + getConfig().getInt("http.port", 8080));
                sender.sendMessage(ChatColor.GRAY + "  - Nonces: " + (nonceStore != null ? "Active" : "Inactive"));
                break;

            case "reload":
                reloadConfig();
                sender.sendMessage(ChatColor.GREEN + "✅ Configuration reloaded!");
                break;

            case "test":
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.RED + "❌ Usage: /rankbridge test <player> <group> <days>");
                    return true;
                }
                String testPlayer = args[1];
                String testGroup = args[2];
                int testDays;
                try {
                    testDays = Integer.parseInt(args[3]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "❌ Invalid days format!");
                    return true;
                }
                
                sender.sendMessage(ChatColor.YELLOW + "🔄 Testing rank grant...");
                try {
                    LuckPermsService testService = new LuckPermsService(this, 
                            getConfig().getStringList("allowed-groups"));
                    JsonObject result = testService.grantTempParent(testPlayer, testGroup, testDays, false);
                    sender.sendMessage(ChatColor.GREEN + "✅ Test successful!");
                    sender.sendMessage(ChatColor.GRAY + "  Result: " + result.toString());
                } catch (Exception e) {
                    sender.sendMessage(ChatColor.RED + "❌ Test failed: " + e.getMessage());
                }
                break;

            default:
                sender.sendMessage(ChatColor.RED + "❌ Unknown command: " + args[0]);
                break;
        }
        return true;
    }
}