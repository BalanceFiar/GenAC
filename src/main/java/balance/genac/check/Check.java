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
        return violationLevels.getOrDefault(player.getUniqueId(), 0);
    }

    public void increaseViolationLevel(Player player) {
        UUID uuid = player.getUniqueId();
        violationLevels.put(uuid, violationLevels.getOrDefault(uuid, 0) + 1);
    }

    public void decreaseViolationLevel(Player player) {
        UUID uuid = player.getUniqueId();
        int currentLevel = violationLevels.getOrDefault(uuid, 0);
        if (currentLevel > 0) {
            violationLevels.put(uuid, currentLevel - 1);
        }
    }

    public void resetViolationLevel(Player player) {
        violationLevels.remove(player.getUniqueId());
    }

    public void clearPlayerData(Player player) {
        violationLevels.remove(player.getUniqueId());
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("checks." + getName().toLowerCase() + ".enabled", true);
    }

    public int getMaxViolations() {
        return plugin.getConfig().getInt("checks." + getName().toLowerCase() + ".max-violations", 10);
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