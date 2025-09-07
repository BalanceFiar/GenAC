package balance.genac;

import balance.genac.command.GenACCommand;
import balance.genac.manager.AlertManager;
import balance.genac.manager.CheckManager;
import balance.genac.manager.ConfigManager;
import balance.genac.manager.FunctionsManager;
import balance.genac.manager.PunishmentManager;
import balance.genac.utils.VersionUtils;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

public final class GenAC extends JavaPlugin {

    private static GenAC instance;
    private ConfigManager configManager;
    private AlertManager alertManager;
    private CheckManager checkManager;
    private PunishmentManager punishmentManager;
    private FunctionsManager functionsManager;

    @Override
    public void onEnable() {
        instance = this;

        if (!VersionUtils.isSupportedVersion()) {
            getLogger().severe("===========================================");
            getLogger().severe("UNSUPPORTED SERVER VERSION DETECTED!");
            getLogger().severe("Current version: " + VersionUtils.getFormattedVersion());
            getLogger().severe("Supported versions: 1.16.5 - 1.21.4");
            getLogger().severe("Plugin will be disabled for safety.");
            getLogger().severe("===========================================");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().info("Running on Minecraft " + VersionUtils.getFormattedVersion() + " - Compatible!");
        Bukkit.getConsoleSender().sendMessage(ChatColor.GREEN + "[GenAC] Plugin has been enabled!");

        this.configManager = new ConfigManager(this);
        this.alertManager = new AlertManager(this);
        this.punishmentManager = new PunishmentManager(this);
        this.checkManager = new CheckManager(this);
        this.functionsManager = new FunctionsManager(this);

        configManager.loadConfig();
        checkManager.loadChecks();
        functionsManager.loadFunctions();

        getCommand("genac").setExecutor(new GenACCommand(this));
        getCommand("genac").setTabCompleter(new GenACCommand(this));

        getLogger().info("GenAC successfully loaded!");
    }

    @Override
    public void onDisable() {
        Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "[GenAC] Plugin has been disabled!");

        if (checkManager != null) {
            checkManager.unloadChecks();
        }
        if (functionsManager != null) {
            functionsManager.unloadFunctions();
        }
    }

    public static GenAC getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public AlertManager getAlertManager() {
        return alertManager;
    }

    public CheckManager getCheckManager() {
        return checkManager;
    }

    public PunishmentManager getPunishmentManager() {
        return punishmentManager;
    }

    public FunctionsManager getFunctionsManager() {
        return functionsManager;
    }
}