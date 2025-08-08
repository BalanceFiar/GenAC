package balance.genac.manager;

import balance.genac.GenAC;
import balance.genac.check.Check;
import balance.genac.check.impl.combat.hit.Reach;
import balance.genac.check.impl.combat.killaura.KillAuraRotationA;
import balance.genac.check.impl.combat.killaura.KillAuraRotationB;
import balance.genac.check.impl.combat.hit.WallHit;
import balance.genac.check.impl.movement.InvMove;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;

import java.util.ArrayList;
import java.util.List;

public class CheckManager {

    private final GenAC plugin;
    private final List<Check> loadedChecks;

    public CheckManager(GenAC plugin) {
        this.plugin = plugin;
        this.loadedChecks = new ArrayList<>();
    }

    public void loadChecks() {
        plugin.getLogger().info("Loading checks...");
        registerCheck(new Reach(plugin));
        registerCheck(new InvMove(plugin));
        registerCheck(new WallHit(plugin));
        registerCheck(new KillAuraRotationA(plugin));
        registerCheck(new KillAuraRotationB(plugin));

        plugin.getLogger().info("Loaded " + loadedChecks.size() + " checks successfully!");
    }

    public void unloadChecks() {
        plugin.getLogger().info("Unloading checks...");

        for (Check check : loadedChecks) {
            HandlerList.unregisterAll(check);
        }

        loadedChecks.clear();
        plugin.getLogger().info("All checks unloaded!");
    }

    private void registerCheck(Check check) {
        try {
            Bukkit.getPluginManager().registerEvents(check, plugin);
            loadedChecks.add(check);
            plugin.getLogger().info("Registered check: " + check.getClass().getSimpleName());
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to register check: " + check.getClass().getSimpleName());
            e.printStackTrace();
        }
    }

    public List<Check> getLoadedChecks() {
        return new ArrayList<>(loadedChecks);
    }

    public int getCheckCount() {
        return loadedChecks.size();
    }
}