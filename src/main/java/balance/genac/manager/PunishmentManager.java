package balance.genac.manager;

import balance.genac.GenAC;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PunishmentManager {
    
    private final GenAC plugin;
    private final Map<UUID, Map<String, Integer>> playerViolations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPunishment = new ConcurrentHashMap<>();
    
    private static final long PUNISHMENT_COOLDOWN = 5000L;
    
    public PunishmentManager(GenAC plugin) {
        this.plugin = plugin;
    }
    
    public void handleViolation(Player player, String checkName) {
        UUID playerId = player.getUniqueId();
        
        Map<String, Integer> violations = playerViolations.computeIfAbsent(playerId, k -> new HashMap<>());
        int newCount = violations.getOrDefault(checkName, 0) + 1;
        violations.put(checkName, newCount);
        
        Long lastPunish = lastPunishment.get(playerId);
        if (lastPunish != null && System.currentTimeMillis() - lastPunish < PUNISHMENT_COOLDOWN) {
            return;
        }
        
        checkPunishments(player, checkName, newCount);
    }
    
    private void checkPunishments(Player player, String checkName, int violations) {
        String checkPath = getCheckPath(checkName);
        if (checkPath == null) return;
        
        if (shouldKick(checkPath, violations)) {
            executeKick(player, checkName, violations);
            return;
        }
        
        if (shouldBan(checkPath, violations)) {
            executeBan(player, checkName, violations);
        }
    }
    
    private boolean shouldKick(String configPath, int violations) {
        if (!plugin.getConfig().getBoolean(configPath + ".punishment.kick.enabled", false)) {
            return false;
        }
        int threshold = plugin.getConfig().getInt(configPath + ".punishment.kick.threshold", 999);
        return violations >= threshold;
    }
    
    private boolean shouldBan(String configPath, int violations) {
        if (!plugin.getConfig().getBoolean(configPath + ".punishment.ban.enabled", false)) {
            return false;
        }
        int threshold = plugin.getConfig().getInt(configPath + ".punishment.ban.threshold", 999);
        return violations >= threshold;
    }
    
    private void executeKick(Player player, String reason, int violations) {
        lastPunishment.put(player.getUniqueId(), System.currentTimeMillis());
        
        String message = plugin.getConfig().getString("punishments.kick-message", 
            "§cYou have been kicked for suspicious activity: {reason} ({violations} violations)")
            .replace("{reason}", reason)
            .replace("{violations}", String.valueOf(violations));
        
        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline()) {
                    player.kickPlayer(message);
                    plugin.getLogger().info("Kicked " + player.getName() + " for " + reason + " (" + violations + " violations)");
                }
            }
        }.runTask(plugin);
    }
    
    private void executeBan(Player player, String reason, int violations) {
        lastPunishment.put(player.getUniqueId(), System.currentTimeMillis());
        
        String checkPath = getCheckPath(reason);
        int duration = plugin.getConfig().getInt((checkPath != null ? checkPath : "checks.reach") + ".punishment.ban.duration", 3600);
        
        String banCommand = plugin.getConfig().getString("punishments.ban-command", 
            "tempban {player} {duration}s Suspicious activity: {reason} ({violations} violations)")
            .replace("{player}", player.getName())
            .replace("{duration}", String.valueOf(duration))
            .replace("{reason}", reason)
            .replace("{violations}", String.valueOf(violations));
        
        new BukkitRunnable() {
            @Override
            public void run() {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), banCommand);
                plugin.getLogger().info("Banned " + player.getName() + " for " + duration + "s for " + reason + " (" + violations + " violations)");
            }
        }.runTask(plugin);
    }
    
    private String getCheckPath(String checkName) {
        String lowerName = checkName.toLowerCase();
        return "checks." + lowerName;
    }
    
    public int getViolations(UUID playerId, String checkName) {
        Map<String, Integer> violations = playerViolations.get(playerId);
        return violations != null ? violations.getOrDefault(checkName, 0) : 0;
    }
    
    public void clearViolations(UUID playerId) {
        playerViolations.remove(playerId);
        lastPunishment.remove(playerId);
    }
    
    public void clearViolations(UUID playerId, String checkName) {
        Map<String, Integer> violations = playerViolations.get(playerId);
        if (violations != null) {
            violations.remove(checkName);
            if (violations.isEmpty()) {
                playerViolations.remove(playerId);
            }
        }
    }
}
