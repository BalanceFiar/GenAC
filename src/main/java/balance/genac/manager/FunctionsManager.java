package balance.genac.manager;

import balance.genac.GenAC;
import balance.genac.function.Function;
import balance.genac.function.MetaCancel;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class FunctionsManager {

    private final GenAC plugin;
    private final List<Function> functions;

    public FunctionsManager(GenAC plugin) {
        this.plugin = plugin;
        this.functions = new ArrayList<>();
    }

    public void loadFunctions() {
        plugin.getLogger().info("Loading functions...");

        if (plugin.getConfig().getBoolean("functions.metacancel.enabled", true)) {
            registerFunction(new MetaCancel(plugin));
        }
        
        plugin.getLogger().info("Loaded " + functions.size() + " functions");
    }

    private void registerFunction(Function function) {
        if (function.isEnabled()) {
            functions.add(function);
            function.onEnable();
            plugin.getLogger().info("Registered function: " + function.getName());
        }
    }

    public void unloadFunctions() {
        for (Function function : functions) {
            function.onDisable();
        }
        functions.clear();
        plugin.getLogger().info("Unloaded all functions");
    }

    public void clearPlayerData(Player player) {
        for (Function function : functions) {
            function.clearPlayerData(player);
        }
    }

    public List<Function> getFunctions() {
        return new ArrayList<>(functions);
    }

    public Function getFunction(String name) {
        return functions.stream()
                .filter(function -> function.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }
    
    public MetaCancel getMetaCancel() {
        Function function = getFunction("MetaCancel");
        return function instanceof MetaCancel ? (MetaCancel) function : null;
    }
}
