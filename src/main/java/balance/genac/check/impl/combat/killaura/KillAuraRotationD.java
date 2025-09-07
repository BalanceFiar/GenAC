package balance.genac.check.impl.combat.killaura;

import balance.genac.GenAC;
import balance.genac.alert.Alert;
import balance.genac.alert.AlertType;
import balance.genac.check.Check;
import balance.genac.check.CheckInfo;
import balance.genac.check.CheckType;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@CheckInfo(
        name = "KillAuraRotationD",
        type = CheckType.COMBAT,
        description = "Heuristically analyzes rotation patterns over time"
)
public class KillAuraRotationD extends Check {

    private final Map<UUID, PlayerAnalysisData> playerDataMap = new ConcurrentHashMap<>();
    private PacketAdapter packetListener;

    private static final int BUFFER_SIZE = 300;
    private static final double ENTROPY_THRESHOLD = 0.65;
    private static final double MACHINE_PRECISION_THRESHOLD = 0.92;
    private static final double ANOMALY_SCORE_THRESHOLD = 0.78;
    private static final long ANALYSIS_INTERVAL = 2500L;

    public KillAuraRotationD(GenAC plugin) {
        super(plugin);
        registerPacketListener();
    }

    private void registerPacketListener() {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
            return;
        }

        try {
            packetListener = new PacketAdapter(plugin, ListenerPriority.NORMAL,
                    PacketType.Play.Client.USE_ENTITY,
                    PacketType.Play.Client.ARM_ANIMATION,
                    PacketType.Play.Client.LOOK,
                    PacketType.Play.Client.POSITION_LOOK) {
                @Override
                public void onPacketReceiving(PacketEvent event) {
                    if (event.isCancelled()) return;
                    handlePacket(event);
                }
            };

            ProtocolLibrary.getProtocolManager().addPacketListener(packetListener);
        } catch (Exception ignored) {}
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!isEnabled()) return;

        Player player = event.getPlayer();
        if (player == null || !player.isOnline()) return;

        UUID playerId = player.getUniqueId();
        PlayerAnalysisData data = playerDataMap.computeIfAbsent(playerId, k -> new PlayerAnalysisData());

        float yawDelta = normalizeAngle(event.getTo().getYaw() - event.getFrom().getYaw());
        float pitchDelta = event.getTo().getPitch() - event.getFrom().getPitch();

        data.processRotation(yawDelta, pitchDelta, System.currentTimeMillis());

        if (data.shouldAnalyze()) {
            performDeepAnalysis(player, data);
        }
    }

    private void handlePacket(PacketEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;

        UUID playerId = player.getUniqueId();
        PlayerAnalysisData data = playerDataMap.computeIfAbsent(playerId, k -> new PlayerAnalysisData());
        PacketType type = event.getPacketType();

        if (type == PacketType.Play.Client.USE_ENTITY) {
            try {
                EnumWrappers.EntityUseAction action = event.getPacket().getEntityUseActions().read(0);
                if (action == EnumWrappers.EntityUseAction.ATTACK) {
                    Entity target = event.getPacket().getEntityModifier(player.getWorld()).read(0);
                    if (target instanceof Player) {
                        data.registerAttack(System.currentTimeMillis());
                    }
                }
            } catch (Exception ignored) {}
        } else if (type == PacketType.Play.Client.ARM_ANIMATION) {
            data.registerSwing(System.currentTimeMillis());
        } else if (type == PacketType.Play.Client.LOOK || type == PacketType.Play.Client.POSITION_LOOK) {
            float yaw = event.getPacket().getFloat().read(0);
            float pitch = event.getPacket().getFloat().read(1);
            data.addRawRotation(yaw, pitch, System.currentTimeMillis());
        }
    }

    private void performDeepAnalysis(Player player, PlayerAnalysisData data) {
        long currentTime = System.currentTimeMillis();

        double entropyScore = data.calculateEntropyScore();
        double machinePrecisionScore = data.calculateMachinePrecisionScore();
        double temporalAnomalyScore = data.calculateTemporalAnomalyScore();
        double patternConsistencyScore = data.calculatePatternConsistencyScore();
        double snapAccuracyScore = data.calculateSnapAccuracyScore();
        double distributionAnomalyScore = data.calculateDistributionAnomaly();

        double finalScore = (entropyScore * 0.15) +
                (machinePrecisionScore * 0.25) +
                (temporalAnomalyScore * 0.15) +
                (patternConsistencyScore * 0.20) +
                (snapAccuracyScore * 0.15) +
                (distributionAnomalyScore * 0.10);

        if (finalScore > ANOMALY_SCORE_THRESHOLD) {
            data.anomalyCounter++;

            if (data.shouldFlag()) {
                String details = String.format(
                        "Entropy: %.2f | Machine: %.2f | Temporal: %.2f | Pattern: %.2f | Snap: %.2f | Anomalies: %d",
                        entropyScore, machinePrecisionScore, temporalAnomalyScore,
                        patternConsistencyScore, snapAccuracyScore, data.anomalyCounter
                );

                Alert alert = new Alert(player, "KillAuraRotationD", AlertType.COMBAT,
                        getViolationLevel(player), details);
                plugin.getAlertManager().sendAlert(alert);
                increaseViolationLevel(player);

                data.resetAfterFlag();
            }
        } else if (finalScore < 0.3) {
            data.decay();
        }

        data.lastAnalysisTime = currentTime;
    }

    private float normalizeAngle(float angle) {
        while (angle > 180.0f) angle -= 360.0f;
        while (angle < -180.0f) angle += 360.0f;
        return angle;
    }

    @Override
    public void clearPlayerData(Player player) {
        playerDataMap.remove(player.getUniqueId());
    }

    @Override
    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("checks.combat.killaura-rotation-d.enabled", true);
    }

    public void onDisable() {
        if (packetListener != null) {
            ProtocolLibrary.getProtocolManager().removePacketListener(packetListener);
        }
        playerDataMap.clear();
    }

    private static class PlayerAnalysisData {
        private final LinkedList<RotationFrame> rotationBuffer = new LinkedList<>();
        private final LinkedList<ActionEvent> actionHistory = new LinkedList<>();
        private final List<Double> angleDistribution = new ArrayList<>();
        private final Map<Integer, Integer> angleFrequency = new HashMap<>();

        private long lastAnalysisTime = 0;
        private long lastAttackTime = 0;
        private long lastSwingTime = 0;
        private int anomalyCounter = 0;
        private int consecutiveHits = 0;

        void processRotation(float yawDelta, float pitchDelta, long timestamp) {
            rotationBuffer.add(new RotationFrame(yawDelta, pitchDelta, timestamp));

            if (rotationBuffer.size() > BUFFER_SIZE) {
                rotationBuffer.removeFirst();
            }

            double angle = Math.sqrt(yawDelta * yawDelta + pitchDelta * pitchDelta);
            angleDistribution.add(angle);
            if (angleDistribution.size() > 100) {
                angleDistribution.remove(0);
            }

            int angleKey = (int)(angle * 10);
            angleFrequency.merge(angleKey, 1, Integer::sum);
        }

        void addRawRotation(float yaw, float pitch, long timestamp) {
            if (!rotationBuffer.isEmpty()) {
                RotationFrame last = rotationBuffer.getLast();
                if (timestamp - last.timestamp < 50) {
                    last.rawYaw = yaw;
                    last.rawPitch = pitch;
                }
            }
        }

        void registerAttack(long timestamp) {
            actionHistory.add(new ActionEvent(ActionType.ATTACK, timestamp));
            if (actionHistory.size() > 50) {
                actionHistory.removeFirst();
            }

            if (timestamp - lastAttackTime < 100) {
                consecutiveHits++;
            } else {
                consecutiveHits = 1;
            }

            lastAttackTime = timestamp;
        }

        void registerSwing(long timestamp) {
            actionHistory.add(new ActionEvent(ActionType.SWING, timestamp));
            if (actionHistory.size() > 50) {
                actionHistory.removeFirst();
            }
            lastSwingTime = timestamp;
        }

        boolean shouldAnalyze() {
            return System.currentTimeMillis() - lastAnalysisTime > ANALYSIS_INTERVAL &&
                    rotationBuffer.size() > 50;
        }

        double calculateEntropyScore() {
            if (angleFrequency.isEmpty()) return 0.0;

            int total = angleFrequency.values().stream().mapToInt(Integer::intValue).sum();
            double entropy = 0.0;

            for (int count : angleFrequency.values()) {
                if (count > 0) {
                    double probability = (double) count / total;
                    entropy -= probability * Math.log(probability) / Math.log(2);
                }
            }

            double maxEntropy = Math.log(angleFrequency.size()) / Math.log(2);
            double normalizedEntropy = maxEntropy > 0 ? entropy / maxEntropy : 0;

            return normalizedEntropy < ENTROPY_THRESHOLD ? 1.0 - normalizedEntropy : 0.0;
        }

        double calculateMachinePrecisionScore() {
            if (rotationBuffer.size() < 20) return 0.0;

            List<RotationFrame> recent = rotationBuffer.stream()
                    .skip(Math.max(0, rotationBuffer.size() - 30))
                    .collect(Collectors.toList());

            int perfectAngles = 0;
            int suspiciousStops = 0;
            double lastMagnitude = 0;

            for (int i = 0; i < recent.size(); i++) {
                RotationFrame frame = recent.get(i);
                double magnitude = Math.sqrt(frame.yawDelta * frame.yawDelta + frame.pitchDelta * frame.pitchDelta);

                if (Math.abs(frame.yawDelta) % 0.1f < 0.01f && magnitude > 5) {
                    perfectAngles++;
                }

                if (i > 0 && lastMagnitude > 20 && magnitude < 0.5) {
                    suspiciousStops++;
                }

                lastMagnitude = magnitude;
            }

            double precisionRatio = (double) perfectAngles / recent.size();
            double stopRatio = (double) suspiciousStops / recent.size();

            return Math.min(1.0, precisionRatio * 2 + stopRatio * 3);
        }

        double calculateTemporalAnomalyScore() {
            if (actionHistory.size() < 10) return 0.0;

            List<Long> attackIntervals = new ArrayList<>();
            ActionEvent lastAttack = null;

            for (ActionEvent event : actionHistory) {
                if (event.type == ActionType.ATTACK) {
                    if (lastAttack != null) {
                        attackIntervals.add(event.timestamp - lastAttack.timestamp);
                    }
                    lastAttack = event;
                }
            }

            if (attackIntervals.size() < 3) return 0.0;

            double mean = attackIntervals.stream().mapToLong(Long::longValue).average().orElse(0);
            double variance = attackIntervals.stream()
                    .mapToDouble(i -> Math.pow(i - mean, 2))
                    .average().orElse(0);

            double cv = mean > 0 ? Math.sqrt(variance) / mean : 0;

            int swingAttackMatches = 0;
            for (int i = 0; i < actionHistory.size() - 1; i++) {
                if (actionHistory.get(i).type == ActionType.SWING &&
                        actionHistory.get(i + 1).type == ActionType.ATTACK) {
                    long diff = actionHistory.get(i + 1).timestamp - actionHistory.get(i).timestamp;
                    if (diff < 50 && diff > 0) {
                        swingAttackMatches++;
                    }
                }
            }

            double matchRatio = actionHistory.size() > 0 ?
                    (double) swingAttackMatches / actionHistory.size() : 0;

            return cv < 0.1 && matchRatio > 0.7 ? 1.0 : cv < 0.3 ? 0.5 : 0.0;
        }

        double calculatePatternConsistencyScore() {
            if (rotationBuffer.size() < 30) return 0.0;

            List<Double> segments = new ArrayList<>();
            for (int i = 0; i < rotationBuffer.size() - 10; i += 10) {
                double segmentSum = 0;
                for (int j = 0; j < 10; j++) {
                    RotationFrame frame = rotationBuffer.get(i + j);
                    segmentSum += Math.sqrt(frame.yawDelta * frame.yawDelta + frame.pitchDelta * frame.pitchDelta);
                }
                segments.add(segmentSum);
            }

            if (segments.size() < 2) return 0.0;

            double segmentMean = segments.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double segmentVariance = segments.stream()
                    .mapToDouble(s -> Math.pow(s - segmentMean, 2))
                    .average().orElse(0);

            double consistency = segmentMean > 0 ? 1.0 - (Math.sqrt(segmentVariance) / segmentMean) : 0;

            return Math.max(0, Math.min(1.0, consistency));
        }

        double calculateSnapAccuracyScore() {
            if (rotationBuffer.size() < 10) return 0.0;

            int snapCount = 0;
            int accurateSnaps = 0;

            for (int i = 1; i < rotationBuffer.size(); i++) {
                RotationFrame current = rotationBuffer.get(i);
                RotationFrame previous = rotationBuffer.get(i - 1);

                double currentMag = Math.sqrt(current.yawDelta * current.yawDelta + current.pitchDelta * current.pitchDelta);
                double previousMag = Math.sqrt(previous.yawDelta * previous.yawDelta + previous.pitchDelta * previous.pitchDelta);

                if (currentMag > 30 && previousMag < 5) {
                    snapCount++;

                    boolean hasAttackNearby = actionHistory.stream()
                            .anyMatch(e -> e.type == ActionType.ATTACK &&
                                    Math.abs(e.timestamp - current.timestamp) < 100);

                    if (hasAttackNearby) {
                        accurateSnaps++;
                    }
                }
            }

            if (snapCount == 0) return 0.0;

            double accuracy = (double) accurateSnaps / snapCount;
            return accuracy > 0.8 && snapCount > 3 ? accuracy : 0.0;
        }

        double calculateDistributionAnomaly() {
            if (angleDistribution.size() < 20) return 0.0;

            List<Double> sorted = new ArrayList<>(angleDistribution);
            Collections.sort(sorted);

            double q1 = sorted.get(sorted.size() / 4);
            double q3 = sorted.get(3 * sorted.size() / 4);
            double iqr = q3 - q1;

            if (iqr < 0.1) return 1.0;

            double median = sorted.get(sorted.size() / 2);
            double mad = sorted.stream()
                    .mapToDouble(v -> Math.abs(v - median))
                    .sorted()
                    .skip(sorted.size() / 2)
                    .findFirst()
                    .orElse(0);

            return mad < 0.5 ? 1.0 : mad < 2.0 ? 0.5 : 0.0;
        }

        boolean shouldFlag() {
            return anomalyCounter >= 3 || (anomalyCounter >= 2 && consecutiveHits > 5);
        }

        void resetAfterFlag() {
            anomalyCounter = Math.max(0, anomalyCounter - 2);
            consecutiveHits = 0;
            angleFrequency.clear();
        }

        void decay() {
            if (anomalyCounter > 0) anomalyCounter--;
            consecutiveHits = Math.max(0, consecutiveHits - 1);
        }

        private static class RotationFrame {
            final float yawDelta, pitchDelta;
            final long timestamp;
            float rawYaw, rawPitch;

            RotationFrame(float yawDelta, float pitchDelta, long timestamp) {
                this.yawDelta = yawDelta;
                this.pitchDelta = pitchDelta;
                this.timestamp = timestamp;
            }
        }

        private static class ActionEvent {
            final ActionType type;
            final long timestamp;

            ActionEvent(ActionType type, long timestamp) {
                this.type = type;
                this.timestamp = timestamp;
            }
        }

        private enum ActionType {
            ATTACK, SWING
        }
    }
}
