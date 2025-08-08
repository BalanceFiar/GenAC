package balance.genac.command;

import balance.genac.GenAC;
import balance.genac.alert.Alert;
import balance.genac.check.Check;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class GenACCommand implements CommandExecutor, TabCompleter {

    private final GenAC plugin;

    public GenACCommand(GenAC plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("genac.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command!");
            return true;
        }

        if (args.length == 0) {
            sendHelpMessage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "info":
                sendInfoMessage(sender);
                break;
            case "checks":
                sendChecksMessage(sender);
                break;
            case "alerts":
                if (args.length > 1) {
                    handleAlertsCommand(sender, args);
                } else {
                    sendAlertsMessage(sender);
                }
                break;
            case "reload":
                reloadConfig(sender);
                break;
            case "toggle":
                if (args.length > 1) {
                    toggleCheck(sender, args[1]);
                } else {
                    sender.sendMessage(ChatColor.RED + "Usage: /genac toggle <check>");
                }
                break;
            case "violations":
                if (args.length > 1) {
                    showViolations(sender, args[1]);
                } else {
                    sender.sendMessage(ChatColor.RED + "Usage: /genac violations <player>");
                }
                break;
            default:
                sendHelpMessage(sender);
                break;
        }

        return true;
    }

    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== GenAC Commands ===");
        sender.sendMessage(ChatColor.YELLOW + "/genac info" + ChatColor.WHITE + " - Show plugin information");
        sender.sendMessage(ChatColor.YELLOW + "/genac checks" + ChatColor.WHITE + " - List all checks");
        sender.sendMessage(ChatColor.YELLOW + "/genac alerts [count]" + ChatColor.WHITE + " - Show recent alerts");
        sender.sendMessage(ChatColor.YELLOW + "/genac reload" + ChatColor.WHITE + " - Reload configuration");
        sender.sendMessage(ChatColor.YELLOW + "/genac toggle <check>" + ChatColor.WHITE + " - Toggle a check");
        sender.sendMessage(ChatColor.YELLOW + "/genac violations <player>" + ChatColor.WHITE + " - Show player violations");
    }

    private void sendInfoMessage(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== GenAC Information ===");
        sender.sendMessage(ChatColor.YELLOW + "Version: " + ChatColor.WHITE + plugin.getDescription().getVersion());
        sender.sendMessage(ChatColor.YELLOW + "Loaded Checks: " + ChatColor.WHITE + plugin.getCheckManager().getCheckCount());
        sender.sendMessage(ChatColor.YELLOW + "Recent Alerts: " + ChatColor.WHITE + plugin.getAlertManager().getAlertCount());
        sender.sendMessage(ChatColor.YELLOW + "Status: " + ChatColor.GREEN + "Running");
    }

    private void sendChecksMessage(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== Loaded Checks ===");
        for (Check check : plugin.getCheckManager().getLoadedChecks()) {
            String status = check.isEnabled() ? ChatColor.GREEN + "Enabled" : ChatColor.RED + "Disabled";
            sender.sendMessage(ChatColor.YELLOW + check.getName() + ChatColor.WHITE + " - " + status);
            sender.sendMessage(ChatColor.GRAY + "  Type: " + check.getType().getDisplayName());
            sender.sendMessage(ChatColor.GRAY + "  Description: " + check.getDescription());
        }
    }

    private void sendAlertsMessage(CommandSender sender) {
        List<Alert> alerts = plugin.getAlertManager().getRecentAlerts(10);
        if (alerts.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No recent alerts found.");
            return;
        }

        sender.sendMessage(ChatColor.GOLD + "=== Recent Alerts (Last 10) ===");
        for (Alert alert : alerts) {
            sender.sendMessage(ChatColor.GRAY + "[" + formatTime(alert.getTimestamp()) + "] " +
                    alert.getAlertType().getColoredName() + ChatColor.WHITE + " " +
                    alert.getPlayer().getName() + " failed " + alert.getCheckName() +
                    " (" + alert.getViolationLevel() + ")");
        }
    }

    private void handleAlertsCommand(CommandSender sender, String[] args) {
        try {
            int count = Integer.parseInt(args[1]);
            List<Alert> alerts = plugin.getAlertManager().getRecentAlerts(count);

            if (alerts.isEmpty()) {
                sender.sendMessage(ChatColor.YELLOW + "No recent alerts found.");
                return;
            }

            sender.sendMessage(ChatColor.GOLD + "=== Recent Alerts (Last " + count + ") ===");
            for (Alert alert : alerts) {
                sender.sendMessage(ChatColor.GRAY + "[" + formatTime(alert.getTimestamp()) + "] " +
                        alert.getAlertType().getColoredName() + ChatColor.WHITE + " " +
                        alert.getPlayer().getName() + " failed " + alert.getCheckName() +
                        " (" + alert.getViolationLevel() + ")");
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Invalid number format!");
        }
    }

    private void reloadConfig(CommandSender sender) {
        try {
            plugin.getConfigManager().reloadConfig();
            sender.sendMessage(ChatColor.GREEN + "Configuration reloaded successfully!");
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "Failed to reload configuration: " + e.getMessage());
        }
    }

    private void toggleCheck(CommandSender sender, String checkName) {
        sender.sendMessage(ChatColor.YELLOW + "Check toggle functionality will be implemented soon.");
    }

    private void showViolations(CommandSender sender, String playerName) {
        Player target = plugin.getServer().getPlayer(playerName);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player not found!");
            return;
        }

        sender.sendMessage(ChatColor.GOLD + "=== Violations for " + target.getName() + " ===");
        for (Check check : plugin.getCheckManager().getLoadedChecks()) {
            int violations = check.getViolationLevel(target);
            if (violations > 0) {
                sender.sendMessage(ChatColor.YELLOW + check.getName() + ChatColor.WHITE + ": " + violations);
            }
        }
    }

    private String formatTime(long timestamp) {
        long diff = System.currentTimeMillis() - timestamp;
        long seconds = diff / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        if (hours > 0) {
            return hours + "h ago";
        } else if (minutes > 0) {
            return minutes + "m ago";
        } else {
            return seconds + "s ago";
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("genac.admin")) {
            return new ArrayList<>();
        }

        if (args.length == 1) {
            return Arrays.asList("info", "checks", "alerts", "reload", "toggle", "violations");
        }

        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("toggle")) {
                List<String> checkNames = new ArrayList<>();
                for (Check check : plugin.getCheckManager().getLoadedChecks()) {
                    checkNames.add(check.getName().toLowerCase());
                }
                return checkNames;
            }
        }

        return new ArrayList<>();
    }
}