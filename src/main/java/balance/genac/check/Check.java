package balance.genac.check;

import balance.genac.GenAC;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public abstract class Check implements Listener {

    protected final GenAC plugin;
    private final Map<UUID, Integer> violationLevels;
    private final CheckInfo checkInfo;

    public Check(GenAC plugin) {
        this.plugin = plugin;
        this.violationLevels = new HashMap<>();
        this.checkInfo = this.getClass().getAnnotation(CheckInfo.class);

        if (checkInfo == null) {
            throw new IllegalStateException("Check " + this.getClass().getSimpleName() + " must have @CheckInfo annotation");
        }
    }

    public String getName() {
        return checkInfo.name();
    }

    public CheckType getType() {
        return checkInfo.type();
    }

    public String getDescription() {
        return checkInfo.description();
    }

    public int getViolationLevel(Player player) {
        if (plugin.getPunishmentManager() != null) {
            return plugin.getPunishmentManager().getViolations(player.getUniqueId(), getName());
        }
        return violationLevels.getOrDefault(player.getUniqueId(), 0);
    }

    public void increaseViolationLevel(Player player) {
        if (plugin.getPunishmentManager() != null) {
            plugin.getPunishmentManager().handleViolation(player, getName());
        } else {
            UUID uuid = player.getUniqueId();
            violationLevels.put(uuid, violationLevels.getOrDefault(uuid, 0) + 1);
        }
    }

    public void decreaseViolationLevel(Player player) {
        UUID uuid = player.getUniqueId();
        int currentLevel = violationLevels.getOrDefault(uuid, 0);
        if (currentLevel > 0) {
            violationLevels.put(uuid, currentLevel - 1);
        }
    }

    public void resetViolationLevel(Player player) {
        if (plugin.getPunishmentManager() != null) {
            plugin.getPunishmentManager().clearViolations(player.getUniqueId(), getName());
        }
        violationLevels.remove(player.getUniqueId());
    }

    public void clearPlayerData(Player player) {
        resetViolationLevel(player);
    }

    public boolean isEnabled() {
        String checkPath = getConfigPath();
        return plugin.getConfig().getBoolean(checkPath + ".enabled", true);
    }

    public int getMaxViolations() {
        String checkPath = getConfigPath();
        return plugin.getConfig().getInt(checkPath + ".max-violations", 10);
    }

    private String getConfigPath() {
        String checkName = getName().toLowerCase();
        return "checks." + checkName;
    }

    @Override
    public String toString() {
        return "Check{" +
                "name='" + getName() + '\'' +
                ", type=" + getType() +
                ", description='" + getDescription() + '\'' +
                '}';
    }
}