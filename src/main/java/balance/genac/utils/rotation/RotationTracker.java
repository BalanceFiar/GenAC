package balance.genac.utils.rotation;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RotationTracker {
    private final Map<UUID, List<RotationData>> playerRotations = new ConcurrentHashMap<>();
    private final Map<UUID, RotationData> lastRotations = new ConcurrentHashMap<>();
    private final int maxHistorySize;

    public RotationTracker(int maxHistorySize) {
        this.maxHistorySize = maxHistorySize;
    }

    public void updateRotation(Player player, float yaw, float pitch) {
        UUID uuid = player.getUniqueId();
        RotationData lastRotation = lastRotations.get(uuid);

        float yawDelta = 0;
        float pitchDelta = 0;

        if (lastRotation != null) {
            yawDelta = RotationUtils.getYawDelta(yaw, lastRotation.getYaw());
            pitchDelta = RotationUtils.getPitchDelta(pitch, lastRotation.getPitch());
        }

        RotationData newRotation = new RotationData(yaw, pitch, yawDelta, pitchDelta);
        lastRotations.put(uuid, newRotation);


        List<RotationData> history = playerRotations.computeIfAbsent(uuid, k -> new ArrayList<>());
        history.add(newRotation);

        // Ограничиваем размер истории
        if (history.size() > maxHistorySize) {
            history.remove(0);
        }
    }

    public List<RotationData> getRotationHistory(Player player) {
        return playerRotations.getOrDefault(player.getUniqueId(), new ArrayList<>());
    }

    public RotationData getLastRotation(Player player) {
        return lastRotations.get(player.getUniqueId());
    }

    public List<RotationData> getRecentRotations(Player player, int count) {
        List<RotationData> history = getRotationHistory(player);
        if (history.size() <= count) {
            return new ArrayList<>(history);
        }
        return new ArrayList<>(history.subList(history.size() - count, history.size()));
    }

    public void clearHistory(Player player) {
        UUID uuid = player.getUniqueId();
        playerRotations.remove(uuid);
        lastRotations.remove(uuid);
    }

    public void addRotation(Location from, Location to) {
        if (from == null || to == null) return;


        float yawFrom = from.getYaw();
        float pitchFrom = from.getPitch();
        float yawTo = to.getYaw();
        float pitchTo = to.getPitch();


        yawFrom = RotationUtils.normalizeYaw(yawFrom);
        pitchFrom = RotationUtils.normalizePitch(pitchFrom);
        yawTo = RotationUtils.normalizeYaw(yawTo);
        pitchTo = RotationUtils.normalizePitch(pitchTo);


        float yawDelta = RotationUtils.getYawDelta(yawFrom, yawTo);
        float pitchDelta = RotationUtils.getPitchDelta(pitchFrom, pitchTo);


        RotationData rotation = new RotationData(yawTo, pitchTo, yawDelta, pitchDelta);


        if (rotationHistory == null) {
            rotationHistory = new ArrayList<>();
        }

        rotationHistory.add(rotation);


        if (rotationHistory.size() > maxHistorySize) {
            rotationHistory.remove(0);
        }
    }


    private List<RotationData> rotationHistory;


    public RotationTracker() {
        this.maxHistorySize = 20;
        this.rotationHistory = new ArrayList<>();
    }


    public List<RotationData> getRecentRotations(int count) {
        if (rotationHistory == null || rotationHistory.isEmpty()) {
            return new ArrayList<>();
        }

        if (rotationHistory.size() <= count) {
            return new ArrayList<>(rotationHistory);
        }

        return new ArrayList<>(rotationHistory.subList(rotationHistory.size() - count, rotationHistory.size()));
    }
}