package balance.genac.manager;

import balance.genac.GenAC;
import balance.genac.alert.Alert;
import balance.genac.alert.Flag;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

public class AlertManager {

    private final GenAC plugin;
    private final List<UUID> alertsEnabled;
    private final ConcurrentLinkedQueue<Alert> alertQueue;
    private final List<Alert> recentAlerts;
    private final int maxRecentAlerts;

    private final Flag flagHandler;

    public AlertManager(GenAC plugin) {
        this.plugin = plugin;
        this.alertsEnabled = new ArrayList<>();
        this.alertQueue = new ConcurrentLinkedQueue<>();
        this.recentAlerts = new ArrayList<>();
        this.maxRecentAlerts = 100;
        this.flagHandler = new Flag(plugin);
    }

    public void sendAlert(Alert alert) {
        if (!plugin.getConfig().getBoolean("alerts.enabled", true)) {
            return;
        }

        alertQueue.offer(alert);
        addToRecentAlerts(alert);

        String message = formatAlert(alert);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (hasAlertsEnabled(player) && player.hasPermission("genac.alerts")) {
                player.sendMessage(message);
            }
        }

        if (plugin.getConfig().getBoolean("alerts.console-alerts", true)) {
            Bukkit.getConsoleSender().sendMessage(ChatColor.stripColor(message));
        }

        flagHandler.handleAlert(alert);
    }

    private void addToRecentAlerts(Alert alert) {
        synchronized (recentAlerts) {
            recentAlerts.add(alert);

            if (recentAlerts.size() > maxRecentAlerts) {
                recentAlerts.remove(0);
            }
        }
    }

    private String formatAlert(Alert alert) {
        String format = plugin.getConfig().getString("alerts.alert-format",
                "&8[&bGenAC&8] &f{player} &8failed &f{check} &8(&f{vl}&8) &8- &f{details}");

        return ChatColor.translateAlternateColorCodes('&', format
                .replace("{player}", alert.getPlayer().getName())
                .replace("{check}", alert.getCheckName())
                .replace("{type}", alert.getAlertType().getDisplayName())
                .replace("{vl}", String.valueOf(alert.getViolationLevel()))
                .replace("{details}", alert.getMessage())
                .replace("{ping}", String.valueOf(getPing(alert.getPlayer()))));
    }

    public void toggleAlerts(Player player) {
        UUID uuid = player.getUniqueId();
        if (alertsEnabled.contains(uuid)) {
            alertsEnabled.remove(uuid);
            player.sendMessage(ChatColor.RED + "Alerts disabled!");
        } else {
            alertsEnabled.add(uuid);
            player.sendMessage(ChatColor.GREEN + "Alerts enabled!");
        }
    }

    public boolean hasAlertsEnabled(Player player) {
        return alertsEnabled.contains(player.getUniqueId()) || !alertsEnabled.contains(player.getUniqueId());
    }

    public List<Alert> getRecentAlerts() {
        synchronized (recentAlerts) {
            return new ArrayList<>(recentAlerts);
        }
    }

    public List<Alert> getRecentAlerts(int count) {
        synchronized (recentAlerts) {
            if (recentAlerts.size() <= count) {
                return new ArrayList<>(recentAlerts);
            }
            return new ArrayList<>(recentAlerts.subList(recentAlerts.size() - count, recentAlerts.size()));
        }
    }

    public int getAlertCount() {
        return recentAlerts.size();
    }

    private int getPing(Player player) {
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            return (Integer) handle.getClass().getField("ping").get(handle);
        } catch (Exception e) {
            return 0;
        }
    }
}