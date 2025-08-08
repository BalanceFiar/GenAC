package balance.genac.manager;

import balance.genac.GenAC;
import org.bukkit.configuration.file.FileConfiguration;

public class ConfigManager {

    private final GenAC plugin;
    private FileConfiguration config;

    public ConfigManager(GenAC plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();
    }

    public void reloadConfig() {
        plugin.reloadConfig();
        this.config = plugin.getConfig();
    }

    public boolean isEnabled(String path) {
        return config.getBoolean(path, true);
    }

    public String getString(String path) {
        return config.getString(path, "");
    }

    public int getInt(String path) {
        return config.getInt(path, 0);
    }

    public double getDouble(String path) {
        return config.getDouble(path, 0.0);
    }

    public FileConfiguration getConfig() {
        return config;
    }
}