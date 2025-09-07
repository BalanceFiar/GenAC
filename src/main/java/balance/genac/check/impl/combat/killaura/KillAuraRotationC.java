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
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@CheckInfo(
        name = "KillAuraRotationC",
        type = CheckType.COMBAT,
        description = "Detects impossibly fast and accurate aim snaps onto targets"
)
public class KillAuraRotationC extends Check {

    private final Map<UUID, RotationData> rotationDataMap = new ConcurrentHashMap<>();
    private final Map<UUID, AttackPattern> attackPatterns = new ConcurrentHashMap<>();
    private PacketAdapter useEntityListener;
    private PacketAdapter positionLookListener;

    private static final double SNAP_THRESHOLD = 35.0;
    private static final double PERFECT_AIM_THRESHOLD = 0.15;
    private static final double ACCELERATION_THRESHOLD = 450.0;
    private static final long PATTERN_WINDOW = 2000L;
    private static final double GCD_THRESHOLD = 0.1;
    private static final int SAMPLE_SIZE = 20;

    public KillAuraRotationC(GenAC plugin) {
        super(plugin);
        registerPacketListeners();
    }

    private void registerPacketListeners() {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
            return;
        }

        try {
            useEntityListener = new PacketAdapter(
                    plugin, ListenerPriority.NORMAL, PacketType.Play.Client.USE_ENTITY) {
                @Override
                public void onPacketReceiving(PacketEvent event) {
                    if (event.isCancelled()) return;
                    handleUseEntityPacket(event);
                }
            };

            positionLookListener = new PacketAdapter(
                    plugin, ListenerPriority.NORMAL,
                    PacketType.Play.Client.POSITION_LOOK,
                    PacketType.Play.Client.LOOK) {
                @Override
                public void onPacketReceiving(PacketEvent event) {
                    handleRotationPacket(event);
                }
            };

            ProtocolLibrary.getProtocolManager().addPacketListener(useEntityListener);
            ProtocolLibrary.getProtocolManager().addPacketListener(positionLookListener);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to register packet listeners");
        }
    }

    private void handleRotationPacket(PacketEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;

        float yaw = event.getPacket().getFloat().read(0);
        float pitch = event.getPacket().getFloat().read(1);

        UUID playerId = player.getUniqueId();
        RotationData data = rotationDataMap.computeIfAbsent(playerId, k -> new RotationData());

        long currentTime = System.currentTimeMillis();
        data.addRotationSample(yaw, pitch, currentTime);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!isEnabled()) return;

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        RotationData data = rotationDataMap.computeIfAbsent(playerId, k -> new RotationData());
        AttackPattern pattern = attackPatterns.computeIfAbsent(playerId, k -> new AttackPattern());

        Location from = event.getFrom();
        Location to = event.getTo();

        float yawDelta = normalizeAngle(to.getYaw() - from.getYaw());
        float pitchDelta = to.getPitch() - from.getPitch();

        data.processMovement(yawDelta, pitchDelta, System.currentTimeMillis());

        if (pattern.hasRecentAttack()) {
            analyzeRotationPattern(player, data, pattern);
        }
    }

    private void handleUseEntityPacket(PacketEvent event) {
        Player attacker = event.getPlayer();
        if (attacker == null) return;

        try {
            EnumWrappers.EntityUseAction action = event.getPacket().getEntityUseActions().read(0);
            if (action != EnumWrappers.EntityUseAction.ATTACK) return;

            Entity target = event.getPacket().getEntityModifier(attacker.getWorld()).read(0);
            if (!(target instanceof Player)) return;

            UUID attackerId = attacker.getUniqueId();
            RotationData rotData = rotationDataMap.computeIfAbsent(attackerId, k -> new RotationData());
            AttackPattern pattern = attackPatterns.computeIfAbsent(attackerId, k -> new AttackPattern());

            pattern.registerAttack((Player) target, System.currentTimeMillis());

            performComplexAnalysis(attacker, (Player) target, rotData, pattern);

        } catch (Exception ignored) {}
    }

    private void performComplexAnalysis(Player attacker, Player target, RotationData rotData, AttackPattern pattern) {
        long currentTime = System.currentTimeMillis();

        double snapScore = calculateSnapScore(attacker, target, rotData);
        double gcdScore = rotData.calculateGCDDeviation();
        double consistencyScore = pattern.getConsistencyScore();
        double perfectAimScore = calculatePerfectAimScore(attacker, target, rotData);
        double accelerationScore = rotData.getAccelerationScore();

        double totalScore = (snapScore * 0.3) + (gcdScore * 0.2) +
                (consistencyScore * 0.2) + (perfectAimScore * 0.2) +
                (accelerationScore * 0.1);

        if (totalScore > 0.75) {
            pattern.incrementViolation();

            if (pattern.shouldFlag()) {
                String details = String.format(
                        "Snap: %.2f | GCD: %.2f | Consistency: %.2f | Perfect: %.2f | Pattern: %d",
                        snapScore, gcdScore, consistencyScore, perfectAimScore, pattern.getViolations()
                );

                Alert alert = new Alert(attacker, "KillAuraRotationC", AlertType.COMBAT,
                        getViolationLevel(attacker), details);
                plugin.getAlertManager().sendAlert(alert);
                increaseViolationLevel(attacker);

                pattern.reset();
            }
        } else if (totalScore < 0.3) {
            pattern.decay();
        }
    }

    private double calculateSnapScore(Player attacker, Player target, RotationData rotData) {
        Vector direction = target.getLocation().toVector().subtract(attacker.getLocation().toVector());
        double distance = direction.length();

        if (distance > 6.0) return 0.0;

        direction.normalize();

        float targetYaw = (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
        float targetPitch = (float) Math.toDegrees(Math.asin(-direction.getY()));

        float currentYaw = attacker.getLocation().getYaw();
        float currentPitch = attacker.getLocation().getPitch();

        float yawDiff = Math.abs(normalizeAngle(targetYaw - currentYaw));
        float pitchDiff = Math.abs(targetPitch - currentPitch);

        if (rotData.hasRecentSnap(yawDiff, pitchDiff)) {
            double snapSpeed = rotData.getSnapSpeed();
            double snapAccuracy = 1.0 - (Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff) / 180.0);

            if (snapSpeed > SNAP_THRESHOLD && snapAccuracy > 0.95) {
                return Math.min(1.0, (snapSpeed / SNAP_THRESHOLD) * snapAccuracy);
            }
        }

        return 0.0;
    }

    private double calculatePerfectAimScore(Player attacker, Player target, RotationData rotData) {
        Location attackerEye = attacker.getEyeLocation();
        Location targetCenter = target.getLocation().add(0, target.getEyeHeight() / 2, 0);

        Vector toTarget = targetCenter.toVector().subtract(attackerEye.toVector()).normalize();
        Vector lookDirection = attackerEye.getDirection();

        double dotProduct = toTarget.dot(lookDirection);
        double angleAccuracy = Math.acos(Math.min(1.0, Math.max(-1.0, dotProduct)));

        if (angleAccuracy < PERFECT_AIM_THRESHOLD) {
            int perfectHits = rotData.getPerfectAimCount();
            if (perfectHits > 3) {
                return Math.min(1.0, perfectHits / 5.0);
            }
        }

        return 0.0;
    }

    private void analyzeRotationPattern(Player player, RotationData data, AttackPattern pattern) {
        List<Float> recentYaws = data.getRecentYawDeltas();
        List<Float> recentPitches = data.getRecentPitchDeltas();

        if (recentYaws.size() < 5) return;

        double yawVariance = calculateVariance(recentYaws);
        double pitchVariance = calculateVariance(recentPitches);

        boolean unnaturalPattern = detectUnnaturalPattern(recentYaws, recentPitches);
        boolean consistentTiming = pattern.hasConsistentTiming();

        if (unnaturalPattern && consistentTiming) {
            pattern.addSuspiciousPattern();
        }
    }

    private boolean detectUnnaturalPattern(List<Float> yaws, List<Float> pitches) {
        int perfectAngles = 0;
        int suddenStops = 0;

        for (int i = 1; i < yaws.size(); i++) {
            float yawDiff = Math.abs(yaws.get(i) - yaws.get(i-1));
            float pitchDiff = Math.abs(pitches.get(i) - pitches.get(i-1));

            if (yawDiff % 0.1f < 0.01f || pitchDiff % 0.1f < 0.01f) {
                perfectAngles++;
            }

            if ((yawDiff > 10 && yaws.get(i) < 0.1f) ||
                    (pitchDiff > 5 && pitches.get(i) < 0.1f)) {
                suddenStops++;
            }
        }

        return perfectAngles > yaws.size() / 3 || suddenStops > 2;
    }

    private double calculateVariance(List<Float> values) {
        if (values.isEmpty()) return 0.0;

        double mean = values.stream().mapToDouble(Float::doubleValue).average().orElse(0.0);
        return values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average().orElse(0.0);
    }

    private float normalizeAngle(float angle) {
        while (angle > 180.0f) angle -= 360.0f;
        while (angle < -180.0f) angle += 360.0f;
        return angle;
    }

    @Override
    public void clearPlayerData(Player player) {
        UUID playerId = player.getUniqueId();
        rotationDataMap.remove(playerId);
        attackPatterns.remove(playerId);
    }

    @Override
    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("checks.combat.killaura-rotation-c.enabled", true);
    }

    public void onDisable() {
        if (useEntityListener != null) {
            ProtocolLibrary.getProtocolManager().removePacketListener(useEntityListener);
        }
        if (positionLookListener != null) {
            ProtocolLibrary.getProtocolManager().removePacketListener(positionLookListener);
        }
        rotationDataMap.clear();
        attackPatterns.clear();
    }

    private static class RotationData {
        private final LinkedList<RotationSample> samples = new LinkedList<>();
        private final LinkedList<Float> yawDeltas = new LinkedList<>();
        private final LinkedList<Float> pitchDeltas = new LinkedList<>();
        private float lastYaw, lastPitch;
        private long lastRotationTime;
        private int perfectAimCount;
        private double maxAcceleration;

        void addRotationSample(float yaw, float pitch, long timestamp) {
            samples.add(new RotationSample(yaw, pitch, timestamp));
            if (samples.size() > SAMPLE_SIZE) {
                samples.removeFirst();
            }

            if (lastRotationTime > 0) {
                float yawDelta = Math.abs(yaw - lastYaw);
                float pitchDelta = Math.abs(pitch - lastPitch);
                long timeDelta = timestamp - lastRotationTime;

                if (timeDelta > 0) {
                    double acceleration = Math.sqrt(yawDelta * yawDelta + pitchDelta * pitchDelta) / timeDelta * 1000;
                    maxAcceleration = Math.max(maxAcceleration, acceleration);
                }
            }

            lastYaw = yaw;
            lastPitch = pitch;
            lastRotationTime = timestamp;
        }

        void processMovement(float yawDelta, float pitchDelta, long timestamp) {
            yawDeltas.add(yawDelta);
            pitchDeltas.add(pitchDelta);

            if (yawDeltas.size() > 20) {
                yawDeltas.removeFirst();
                pitchDeltas.removeFirst();
            }
        }

        double calculateGCDDeviation() {
            if (samples.size() < 10) return 0.0;

            List<Float> yawDiffs = new ArrayList<>();
            for (int i = 1; i < samples.size(); i++) {
                float diff = Math.abs(samples.get(i).yaw - samples.get(i-1).yaw);
                if (diff > 0.01f) yawDiffs.add(diff);
            }

            if (yawDiffs.size() < 5) return 0.0;

            float gcd = findGCD(yawDiffs);
            if (gcd < GCD_THRESHOLD) return 0.0;

            double deviations = 0;
            for (Float diff : yawDiffs) {
                double remainder = diff % gcd;
                if (remainder < 0.01f || remainder > gcd - 0.01f) {
                    deviations++;
                }
            }

            return deviations / yawDiffs.size();
        }

        private float findGCD(List<Float> values) {
            if (values.isEmpty()) return 0;
            float result = values.get(0);
            for (int i = 1; i < values.size(); i++) {
                result = gcd(result, values.get(i));
            }
            return result;
        }

        private float gcd(float a, float b) {
            if (b < 0.01f) return a;
            return gcd(b, a % b);
        }

        boolean hasRecentSnap(float yawDiff, float pitchDiff) {
            if (samples.size() < 2) return false;

            RotationSample recent = samples.getLast();
            RotationSample previous = samples.get(samples.size() - 2);

            long timeDiff = recent.timestamp - previous.timestamp;
            if (timeDiff <= 0 || timeDiff > 100) return false;

            double rotationSpeed = Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff) / timeDiff * 1000;
            return rotationSpeed > SNAP_THRESHOLD;
        }

        double getSnapSpeed() {
            if (samples.size() < 2) return 0.0;

            double maxSpeed = 0.0;
            for (int i = 1; i < samples.size(); i++) {
                RotationSample current = samples.get(i);
                RotationSample previous = samples.get(i - 1);

                float yawDiff = Math.abs(current.yaw - previous.yaw);
                float pitchDiff = Math.abs(current.pitch - previous.pitch);
                long timeDiff = current.timestamp - previous.timestamp;

                if (timeDiff > 0 && timeDiff < 100) {
                    double speed = Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff) / timeDiff * 1000;
                    maxSpeed = Math.max(maxSpeed, speed);
                }
            }

            return maxSpeed;
        }

        double getAccelerationScore() {
            if (maxAcceleration < ACCELERATION_THRESHOLD) return 0.0;
            return Math.min(1.0, maxAcceleration / (ACCELERATION_THRESHOLD * 2));
        }

        int getPerfectAimCount() {
            return perfectAimCount;
        }

        List<Float> getRecentYawDeltas() {
            return new ArrayList<>(yawDeltas);
        }

        List<Float> getRecentPitchDeltas() {
            return new ArrayList<>(pitchDeltas);
        }

        private static class RotationSample {
            final float yaw, pitch;
            final long timestamp;

            RotationSample(float yaw, float pitch, long timestamp) {
                this.yaw = yaw;
                this.pitch = pitch;
                this.timestamp = timestamp;
            }
        }
    }

    private static class AttackPattern {
        private final LinkedList<AttackData> attacks = new LinkedList<>();
        private int violations = 0;
        private int suspiciousPatterns = 0;
        private long lastAttackTime = 0;

        void registerAttack(Player target, long timestamp) {
            attacks.add(new AttackData(target.getUniqueId(), timestamp));
            if (attacks.size() > 10) {
                attacks.removeFirst();
            }
            lastAttackTime = timestamp;
        }

        boolean hasRecentAttack() {
            return System.currentTimeMillis() - lastAttackTime < 500;
        }

        boolean hasConsistentTiming() {
            if (attacks.size() < 4) return false;

            List<Long> intervals = new ArrayList<>();
            for (int i = 1; i < attacks.size(); i++) {
                intervals.add(attacks.get(i).timestamp - attacks.get(i-1).timestamp);
            }

            double avgInterval = intervals.stream().mapToLong(Long::longValue).average().orElse(0);
            double variance = intervals.stream()
                    .mapToDouble(i -> Math.pow(i - avgInterval, 2))
                    .average().orElse(0);

            return variance < 10000;
        }

        double getConsistencyScore() {
            if (attacks.size() < 3) return 0.0;

            Set<UUID> uniqueTargets = new HashSet<>();
            for (AttackData attack : attacks) {
                uniqueTargets.add(attack.targetId);
            }

            if (uniqueTargets.size() > 1 && hasConsistentTiming()) {
                return Math.min(1.0, suspiciousPatterns / 3.0);
            }

            return 0.0;
        }

        void incrementViolation() {
            violations++;
        }

        void decay() {
            if (violations > 0) violations--;
            if (suspiciousPatterns > 0) suspiciousPatterns--;
        }

        void addSuspiciousPattern() {
            suspiciousPatterns++;
        }

        boolean shouldFlag() {
            return violations >= 3 || suspiciousPatterns >= 5;
        }

        int getViolations() {
            return violations;
        }

        void reset() {
            violations = Math.max(0, violations - 2);
            suspiciousPatterns = Math.max(0, suspiciousPatterns - 3);
        }

        private static class AttackData {
            final UUID targetId;
            final long timestamp;

            AttackData(UUID targetId, long timestamp) {
                this.targetId = targetId;
                this.timestamp = timestamp;
            }
        }
    }
}
