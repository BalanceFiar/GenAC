package balance.genac.check.impl.combat.killaura;

import balance.genac.GenAC;
import balance.genac.alert.Alert;
import balance.genac.alert.AlertType;
import balance.genac.check.Check;
import balance.genac.check.CheckInfo;
import balance.genac.check.CheckType;
import balance.genac.utils.rotation.RotationData;
import balance.genac.utils.rotation.RotationTracker;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@CheckInfo(name = "KillAuraRotationA", type = CheckType.COMBAT, description = "For smooth rotations in KillAuras")
public class KillAuraRotationA extends Check {

    private final Map<UUID, RotationTracker> rotationTrackers = new HashMap<>();
    private final Map<UUID, Long> lastAttackTime = new HashMap<>();
    private final Map<UUID, Integer> recentAttacks = new HashMap<>();
    private final Map<UUID, AimWindow> aimWindows = new HashMap<>();
    private final Map<UUID, UUID> lastVictimId = new HashMap<>();
    private final Map<UUID, World> lastVictimWorld = new HashMap<>();
    private final Map<UUID, Baseline> baselines = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastBaselineUpdate = new HashMap<>();
    private final Map<UUID, Deque<Long>> attackTimes = new HashMap<>();
    private final Map<UUID, Double> suspicionScore = new HashMap<>();
    private final Map<UUID, Long> lastFlagTime = new HashMap<>();
    private final Map<UUID, Long> lastAnalysisTime = new HashMap<>();
    private final Map<UUID, GcdPersist> gcdPersist = new HashMap<>();

    private static final long ATTACK_WINDOW_MS = 2500L;
    private static final int MIN_ROT_SAMPLES = 16;
    private static final float MIN_DELTA_FOR_ANALYSIS = 0.25f;
    private static final long ANALYSIS_INTERVAL_MS = 200;

    private static final double GCD_LOCK_THRESHOLD = 0.95;
    private static final int GCD_PERSIST_REQ = 4;
    private static final double SINE_INDEX_THRESHOLD = 0.55;
    private static final double CIRCULARITY_THRESHOLD = 0.62;
    private static final double CONST_SPEED_THRESHOLD = 0.70;
    private static final double ROBOTIC_STD_MAX = 1.20;
    private static final double ROBOTIC_MEAN_MIN = 5.0;
    private static final double SPECTRAL_PEAK_THRESHOLD = 0.65;
    private static final double AUTOCORR_THRESHOLD = 0.55;
    private static final double JERK_ZERO_SHARE_THRESHOLD = 0.75;

    private static final long SNAP_WINDOW_MS = 600L;
    private static final int SNAP_MIN_SAMPLES = 8;
    private static final double SNAP_GAIN = 1.0;

    private static final int FLAG_COOLDOWN_MS = 3500;
    private static final double SCORE_DECAY_PER_TICK = 0.25;
    private static final double SCORE_TO_FLAG = 7.0;
    private static final double SCORE_CLAMP_MAX = 20.0;

    private static final int BATCH_MIN_HITS = 10;
    private static final long BATCH_WINDOW_MAX_MS = 0;

    public KillAuraRotationA(GenAC plugin) {
        super(plugin);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (player == null || player.isDead() || player.hasPermission("genac.bypass")) return;

        UUID uuid = player.getUniqueId();
        RotationTracker tracker = rotationTrackers.computeIfAbsent(uuid, k -> new RotationTracker());
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        tracker.addRotation(from, to);

        suspicionScore.put(uuid, Math.max(0.0, suspicionScore.getOrDefault(uuid, 0.0) - SCORE_DECAY_PER_TICK));
        updateBaselineIfIdle(uuid, tracker);

        if (!isPlayerAttacking(uuid)) {
            processAimWindowOnMove(uuid, player, to);
            return;
        }
        long now = System.currentTimeMillis();
        long lastA = lastAnalysisTime.getOrDefault(uuid, 0L);
        if (now - lastA < ANALYSIS_INTERVAL_MS) {
            processAimWindowOnMove(uuid, player, to);
            return;
        }
        lastAnalysisTime.put(uuid, now);

        List<RotationData> window = tracker.getRecentRotations(32);
        if (window.size() < MIN_ROT_SAMPLES) {
            processAimWindowOnMove(uuid, player, to);
            return;
        }
        List<RotationData> sig = window.stream()
                .filter(r -> r.getTotalDelta() >= MIN_DELTA_FOR_ANALYSIS)
                .collect(Collectors.toList());
        if (sig.size() < MIN_ROT_SAMPLES - 2) {
            processAimWindowOnMove(uuid, player, to);
            return;
        }

        Baseline base = baselines.get(uuid);
        Feature f = computeFeatures(sig);

        boolean gcdStrong = updateAndCheckGcdPersist(uuid, f, base);
        double snapAdd = applySnapIfReady(uuid);
        double frameAdd = 0.0;
        int strongGroups = 0;

        boolean A = (f.sineMax >= SINE_INDEX_THRESHOLD)
                || (f.circularity >= CIRCULARITY_THRESHOLD)
                || (f.specMax >= SPECTRAL_PEAK_THRESHOLD)
                || (f.autocorr >= AUTOCORR_THRESHOLD);

        boolean B = (f.constSpeed.constRatio >= CONST_SPEED_THRESHOLD && f.constSpeed.samples >= 8)
                || (f.robot.samples >= 6 && f.robot.stdDev <= ROBOTIC_STD_MAX && f.robot.mean >= ROBOTIC_MEAN_MIN)
                || (f.linear >= 0.50)
                || (f.noMicro)
                || (f.jerkShare >= JERK_ZERO_SHARE_THRESHOLD);

        boolean C = snapAdd > 0.0;

        if (A) strongGroups++;
        if (B) strongGroups++;
        if (C) strongGroups++;

        if (strongGroups >= 2) {
            frameAdd += 1.6;
            if (A && B) frameAdd += 0.5;
            if (C) frameAdd += 0.5;
            if (gcdStrong) frameAdd += 0.8;
        } else {
            if (C && gcdStrong) frameAdd += 0.7;
        }

        if (base != null) {
            if (f.noMicro && base.microRatioAvg > 0.25) frameAdd += 0.4;
            if (f.jerkShare >= JERK_ZERO_SHARE_THRESHOLD && base.jerkZeroShareAvg < 0.40) frameAdd += 0.3;
            if (gcdStrong) {
                double baseStep = "Yaw".equals(f.qBestAxis) ? base.gcdYawStep : base.gcdPitchStep;
                if (baseStep > 0 && Math.abs(f.qBest.step - baseStep) > 0.015) frameAdd += 0.25;
            }
        }

        if (frameAdd > 0 || snapAdd > 0) {
            double newScore = Math.min(SCORE_CLAMP_MAX, suspicionScore.getOrDefault(uuid, 0.0) + frameAdd + snapAdd);
            suspicionScore.put(uuid, newScore);
        }

        double score = suspicionScore.getOrDefault(uuid, 0.0);
        if (score >= SCORE_TO_FLAG && recentAttacks.getOrDefault(uuid, 0) >= 3 && isBatchReady(uuid)) {
            Long lastFlag = lastFlagTime.get(uuid);
            if (lastFlag == null || now - lastFlag >= FLAG_COOLDOWN_MS) {
                float avgRot = (float) sig.stream().mapToDouble(RotationData::getTotalDelta).average().orElse(0.0);
                flag(player, "Smooth rotation patterns indicative of KillAura",
                        String.format(Locale.US,
                                "Score=%.1f A=%b B=%b C=%b | GCD[%s: cov=%.2f step=%.3f] sine=%.2f ellip=%.2f const=%.2f robot[m=%.1f,s=%.2f] lin=%.2f nomicro=%b jerk0=%.2f fft=%.2f ac=%.2f avg=%.1f att=%d",
                                score, A, B, C,
                                f.qBestAxis, f.qBest.coverage, f.qBest.step,
                                f.sineMax, f.circularity, f.constSpeed.constRatio, f.robot.mean, f.robot.stdDev,
                                f.linear, f.noMicro, f.jerkShare, f.specMax, f.autocorr, avgRot, recentAttacks.getOrDefault(uuid, 0)
                        ));
                lastFlagTime.put(uuid, now);
                suspicionScore.put(uuid, Math.max(0.0, score - 4.0));
                resetBatch(uuid);
            }
        }

        processAimWindowOnMove(uuid, player, to);
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        Player player = (Player) event.getDamager();
        UUID uuid = player.getUniqueId();

        long now = System.currentTimeMillis();
        lastAttackTime.put(uuid, now);
        recentAttacks.put(uuid, recentAttacks.getOrDefault(uuid, 0) + 1);

        Deque<Long> times = attackTimes.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        times.addLast(now);
        while (times.size() > BATCH_MIN_HITS) times.removeFirst();
        if (times.size() >= BATCH_MIN_HITS) {
            lastAnalysisTime.put(uuid, 0L);
        }

        Entity victim = event.getEntity();
        lastVictimId.put(uuid, victim.getUniqueId());
        lastVictimWorld.put(uuid, victim.getWorld());

        AimWindow aw = new AimWindow();
        aw.startTime = now;
        aw.victimId = victim.getUniqueId();
        aw.world = victim.getWorld();
        aimWindows.put(uuid, aw);

        plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                recentAttacks.put(uuid, Math.max(0, recentAttacks.getOrDefault(uuid, 0) - 1)), 80L);
    }

    private void updateBaselineIfIdle(UUID uuid, RotationTracker tracker) {
        Long last = lastAttackTime.get(uuid);
        long now = System.currentTimeMillis();
        if (last != null && now - last < 5000L) return;

        long lastUpd = lastBaselineUpdate.getOrDefault(uuid, 0L);
        if (now - lastUpd < 600L) return;
        lastBaselineUpdate.put(uuid, now);

        List<RotationData> w = tracker.getRecentRotations(32);
        if (w.size() < 18) return;

        List<RotationData> sig = w.stream()
                .filter(r -> r.getTotalDelta() >= MIN_DELTA_FOR_ANALYSIS)
                .collect(Collectors.toList());
        if (sig.size() < 14) return;

        Feature f = computeFeatures(sig);
        Baseline b = baselines.getOrDefault(uuid, new Baseline());
        b.gcdYawStep = ema(b.gcdYawStep, f.qYaw.step, 0.20, b.gcdYawStep == 0 ? 1.0 : 0.20);
        b.gcdYawCov = ema(b.gcdYawCov, f.qYaw.coverage, 0.20, b.gcdYawStep == 0 ? 1.0 : 0.20);
        b.gcdPitchStep = ema(b.gcdPitchStep, f.qPitch.step, 0.20, b.gcdPitchStep == 0 ? 1.0 : 0.20);
        b.gcdPitchCov = ema(b.gcdPitchCov, f.qPitch.coverage, 0.20, b.gcdPitchStep == 0 ? 1.0 : 0.20);
        b.microRatioAvg = ema(b.microRatioAvg, f.microRatio, 0.20, b.microRatioAvg == 0 ? 1.0 : 0.20);
        b.jerkZeroShareAvg = ema(b.jerkZeroShareAvg, f.jerkShare, 0.20, b.jerkZeroShareAvg == 0 ? 1.0 : 0.20);
        baselines.put(uuid, b);
    }

    private double ema(double prev, double val, double alpha, double initAlpha) {
        if (prev == 0.0) return prev * (1 - initAlpha) + val * initAlpha;
        return prev * (1 - alpha) + val * alpha;
    }

    private static class AimSample {
        long t;
        double yawErr;
        double pitchErr;
        double err;
    }

    private static class AimWindow {
        UUID victimId;
        World world;
        long startTime;
        boolean evaluated;
        Deque<AimSample> samples = new ArrayDeque<>();
    }

    private void processAimWindowOnMove(UUID uuid, Player player, Location to) {
        AimWindow aw = aimWindows.get(uuid);
        if (aw == null) return;
        long now = System.currentTimeMillis();
        if (now - aw.startTime > SNAP_WINDOW_MS) {
            aimWindows.remove(uuid);
            return;
        }
        if (aw.victimId == null || aw.world == null || player.getWorld() != aw.world) return;
        Entity victim = null;
        for (Entity e : aw.world.getEntities()) {
            if (e.getUniqueId().equals(aw.victimId)) {
                victim = e;
                break;
            }
        }
        if (victim == null || victim.isDead()) return;

        Location eye = player.getEyeLocation();
        Location tgt = victim.getLocation().clone().add(0.0, victim.getHeight() * 0.6, 0.0);

        double[] yp = aimYawPitch(eye, tgt);
        double yawErr = Math.abs(wrapDegrees(yp[0] - to.getYaw()));
        double pitchErr = Math.abs(clamp(yp[1], -89.0, 89.0) - clamp(to.getPitch(), -89.0, 89.0));

        AimSample s = new AimSample();
        s.t = now;
        s.yawErr = yawErr;
        s.pitchErr = pitchErr;
        s.err = Math.hypot(yawErr, pitchErr);

        aw.samples.addLast(s);
        while (aw.samples.size() > 16) aw.samples.removeFirst();

    }

    private double applySnapIfReady(UUID uuid) {
        AimWindow aw = aimWindows.get(uuid);
        if (aw == null || aw.evaluated) return 0.0;
        if (aw.samples.size() < SNAP_MIN_SAMPLES) return 0.0;

        List<AimSample> list = new ArrayList<>(aw.samples);
        List<Double> E = list.stream().map(s -> s.err).collect(Collectors.toList());
        int N = E.size();

        int strongSteps = 0;
        double geo = 1.0;
        int cnt = 0;
        for (int i = 1; i < N; i++) {
            double e0 = E.get(i - 1), e1 = E.get(i);
            if (e0 < 1e-3) continue;
            double r = e1 / e0;
            if (r <= 0.55) strongSteps++;

            geo *= Math.max(1e-3, Math.min(2.0, r));
            cnt++;
        }
        double last = E.get(N - 1), first = E.get(0);
        double gmean = cnt == 0 ? 1.0 : Math.pow(geo, 1.0 / cnt);
        boolean good = strongSteps >= 3 && gmean <= 0.70 && last <= Math.max(1.5, first * 0.20);


        aw.evaluated = true;
        return good ? SNAP_GAIN : 0.0;
    }

    private static double[] aimYawPitch(Location fromEye, Location toPoint) {
        double dx = toPoint.getX() - fromEye.getX();
        double dy = toPoint.getY() - fromEye.getY();
        double dz = toPoint.getZ() - fromEye.getZ();
        double yaw = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
        double distXZ = Math.hypot(dx, dz);
        double pitch = -Math.toDegrees(Math.atan2(dy, distXZ));
        return new double[]{yaw, pitch};
    }

    private static class QuantResult {
        final String axis;
        final double step;
        final double coverage;
        final int samples;
        QuantResult(String axis, double step, double coverage, int samples) {
            this.axis = axis;
            this.step = step;
            this.coverage = coverage;
            this.samples = samples;
        }
    }

    private static class ConstResult {
        final double constRatio;
        final int samples;
        ConstResult(double r, int s) {
            constRatio = r;
            samples = s;
        }
    }

    private static class RobotResult {
        final double mean, stdDev;
        final int samples;
        RobotResult(double m, double s, int n) {
            mean = m;
            stdDev = s;
            samples = n;
        }
    }

    private static class Feature {
        QuantResult qYaw, qPitch, qBest;
        String qBestAxis;
        double sineMax, circularity, specMax, autocorr, linear, jerkShare, microRatio;
        boolean noMicro;
        ConstResult constSpeed;
        RobotResult robot;
    }

    private Feature computeFeatures(List<RotationData> rots) {
        Feature f = new Feature();
        f.qYaw = quantizationScore(rots, true);
        f.qPitch = quantizationScore(rots, false);
        f.qBest = f.qYaw.coverage >= f.qPitch.coverage ? f.qYaw : f.qPitch;
        f.qBestAxis = f.qYaw.coverage >= f.qPitch.coverage ? "Yaw" : "Pitch";
        f.sineMax = Math.max(sineIndex(rots, true), sineIndex(rots, false));
        f.circularity = circularityIndex(rots);
        f.constSpeed = constantSpeed(rots);
        f.robot = roboticConsistency(rots);
        f.linear = linearPattern(rots);
        f.noMicro = lacksMicroCorrections(rots);
        f.microRatio = microRatio(rots);
        f.jerkShare = jerkZeroShare(rots);
        f.specMax = Math.max(spectralPeak(rots, true), spectralPeak(rots, false));
        f.autocorr = Math.max(autocorr(rots, true), autocorr(rots, false));
        return f;
    }

    private QuantResult quantizationScore(List<RotationData> rots, boolean yaw) {
        List<Double> deltas = new ArrayList<>();
        for (RotationData r : rots) {
            double d = yaw ? Math.abs(r.getYawDelta()) : Math.abs(r.getPitchDelta());
            if (d >= 0.5) deltas.add(d);
        }
        int n = deltas.size();
        if (n < 8) return new QuantResult(yaw ? "Yaw" : "Pitch", 0, 0, n);

        double bestCoverage = 0.0, bestStep = 0.0;
        for (double step = 0.01; step <= 1.20; step += 0.0025) {
            double tol = Math.max(0.002, step * 0.02);
            int ok = 0;
            for (double d : deltas) {
                double rem = d % step;
                double dist = Math.min(rem, step - rem);
                if (dist <= tol) ok++;
            }
            double cov = ok / (double) n;
            if (cov > bestCoverage) {
                bestCoverage = cov;
                bestStep = step;
            }
        }
        return new QuantResult(yaw ? "Yaw" : "Pitch", bestStep, bestCoverage, n);
    }

    private boolean lacksMicroCorrections(List<RotationData> rots) {
        return microRatio(rots) < 0.12 && rots.stream().mapToDouble(RotationData::getTotalDelta).average().orElse(0) > 4.5;
    }

    private double microRatio(List<RotationData> rots) {
        int micro = 0;
        for (RotationData r : rots) {
            float d = r.getTotalDelta();
            if (d > 0.1f && d < 1.8f) micro++;
        }
        return rots.isEmpty() ? 0.0 : micro / (double) rots.size();
    }

    private double sineIndex(List<RotationData> rots, boolean yawAxis) {
        List<Double> v = new ArrayList<>();
        for (RotationData r : rots) {
            double d = yawAxis ? r.getYawDelta() : r.getPitchDelta();
            if (Math.abs(d) >= 1.0) v.add(d);
        }
        if (v.size() < 8) return 0.0;
        int matches = 0;
        for (int i = 2; i < v.size(); i++) {
            double a = v.get(i - 2), b = v.get(i - 1), c = v.get(i);
            if (((a > 0 && b < 0 && c > 0) || (a < 0 && b > 0 && c < 0)) && Math.abs(a) >= 2.5 && Math.abs(c) >= 2.5) {
                matches++;
            }
        }
        return matches / (double) (v.size() - 2);
    }

    private double circularityIndex(List<RotationData> rots) {
        List<float[]> v = new ArrayList<>();
        for (RotationData r : rots) if (r.getTotalDelta() >= 1.0f) v.add(new float[]{r.getYawDelta(), r.getPitchDelta()});
        if (v.size() < 8) return 0.0;
        int strong = 0, total = 0, lastSign = 0, cons = 0;
        for (int i = 1; i < v.size(); i++) {
            float x1 = v.get(i - 1)[0], y1 = v.get(i - 1)[1];
            float x2 = v.get(i)[0], y2 = v.get(i)[1];
            double m1 = Math.hypot(x1, y1), m2 = Math.hypot(x2, y2);
            if (m1 < 1.0 || m2 < 1.0) continue;
            double cross = x1 * y2 - y1 * x2;
            double norm = Math.abs(cross) / (m1 * m2);
            if (norm >= 0.65) {
                int sign = cross > 0 ? 1 : -1;
                cons = (lastSign == 0 || sign == lastSign) ? cons + 1 : 0;
                lastSign = sign;
                strong++;
            }
            total++;
        }
        if (total <= 0) return 0.0;
        double base = strong / (double) total;
        double bonus = Math.min(0.15, cons * 0.015);
        return Math.min(1.0, base + bonus);
    }

    private ConstResult constantSpeed(List<RotationData> rots) {
        List<Double> sp = rots.stream().map(RotationData::getTotalDelta).filter(d -> d >= 2.0f).map(Double::valueOf).collect(Collectors.toList());
        if (sp.size() < 6) return new ConstResult(0.0, sp.size());
        int close = 0;
        for (int i = 1; i < sp.size(); i++) if (Math.abs(sp.get(i) - sp.get(i - 1)) < 0.8) close++;
        return new ConstResult(close / (double) (sp.size() - 1), sp.size());
    }

    private RobotResult roboticConsistency(List<RotationData> rots) {
        List<Double> sp = rots.stream().map(RotationData::getTotalDelta).filter(d -> d >= 2.0f).map(Double::valueOf).collect(Collectors.toList());
        if (sp.size() < 4) return new RobotResult(0.0, 999.0, sp.size());
        double mean = sp.stream().mapToDouble(d -> d).average().orElse(0.0), var = 0.0;
        for (double s : sp) var += (s - mean) * (s - mean);
        var /= sp.size();
        return new RobotResult(mean, Math.sqrt(var), sp.size());
    }

    private double linearPattern(List<RotationData> rots) {
        if (rots.size() < 8) return 0.0;
        int linear = 0, total = 0;
        for (int i = 2; i < rots.size(); i++) {
            RotationData r1 = rots.get(i - 2), r2 = rots.get(i - 1), r3 = rots.get(i);
            float yd1 = r2.getYawDelta() - r1.getYawDelta();
            float yd2 = r3.getYawDelta() - r2.getYawDelta();
            if (Math.abs(yd1 - yd2) < 0.4f && Math.abs(yd1) > 0.8f) linear++;
            total++;
        }
        return total == 0 ? 0.0 : (linear / (double) total);
    }

    private double jerkZeroShare(List<RotationData> rots) {
        List<Double> d = rots.stream().map(r -> (double) r.getYawDelta()).collect(Collectors.toList());
        if (d.size() < 8) return 0.0;
        List<Double> acc = new ArrayList<>();
        for (int i = 1; i < d.size(); i++) acc.add(d.get(i) - d.get(i - 1));
        List<Double> jerk = new ArrayList<>();
        for (int i = 1; i < acc.size(); i++) jerk.add(acc.get(i) - acc.get(i - 1));
        int ok = 0;
        for (double j : jerk) if (Math.abs(j) < 0.15) ok++;
        return jerk.isEmpty() ? 0.0 : ok / (double) jerk.size();
    }

    private double spectralPeak(List<RotationData> rots, boolean yaw) {
        List<Double> vals = new ArrayList<>();
        for (RotationData r : rots) vals.add(yaw ? (double) r.getYawDelta() : (double) r.getPitchDelta());
        int N = vals.size();
        if (N < 12) return 0.0;
        double mean = vals.stream().mapToDouble(d -> d).average().orElse(0.0);
        double[] x = new double[N];
        for (int i = 0; i < N; i++) x[i] = vals.get(i) - mean;

        double maxMag = 0.0, sumMag = 0.0;
        for (int k = 1; k < N; k++) {
            double re = 0.0, im = 0.0;
            for (int n = 0; n < N; n++) {
                double ang = -2 * Math.PI * k * n / N;
                re += x[n] * Math.cos(ang);
                im += x[n] * Math.sin(ang);
            }
            double mag = Math.hypot(re, im);
            sumMag += mag;
            if (mag > maxMag) maxMag = mag;
        }
        if (sumMag <= 1e-6) return 0.0;
        return maxMag / sumMag;
    }

    private double autocorr(List<RotationData> rots, boolean yaw) {
        List<Double> v = new ArrayList<>();
        for (RotationData r : rots) v.add(yaw ? (double) r.getYawDelta() : (double) r.getPitchDelta());
        int N = v.size();
        if (N < 12) return 0.0;

        double mean = v.stream().mapToDouble(d -> d).average().orElse(0.0);
        for (int i = 0; i < N; i++) v.set(i, v.get(i) - mean);

        double denom = 0.0;
        for (double a : v) denom += a * a;
        if (denom < 1e-6) return 0.0;

        double best = 0.0;
        for (int lag = 2; lag <= 7; lag++) {
            double num = 0.0;
            for (int i = 0; i + lag < N; i++) num += v.get(i) * v.get(i + lag);
            best = Math.max(best, num / denom);
        }
        return Math.max(0.0, Math.min(1.0, best));
    }

    private static class GcdPersist {
        double step = 0.0;
        int persist = 0;
    }

    private boolean updateAndCheckGcdPersist(UUID uuid, Feature f, Baseline base) {
        boolean strongNow = f.qBest.coverage >= GCD_LOCK_THRESHOLD && f.qBest.samples >= 10 && f.qBest.step > 0.005 && f.qBest.step <= 1.2;
        GcdPersist gp = gcdPersist.getOrDefault(uuid, new GcdPersist());
        if (strongNow) {
            if (Math.abs(f.qBest.step - gp.step) < 0.005) {
                gp.persist++;
            } else {
                gp.step = f.qBest.step;
                gp.persist = 1;
            }
        } else {
            gp.persist = Math.max(0, gp.persist - 1);
        }
        gcdPersist.put(uuid, gp);

        boolean baselineSame = false;
        if (base != null) {
            double baseStep = "Yaw".equals(f.qBestAxis) ? base.gcdYawStep : base.gcdPitchStep;
            if (baseStep > 0 && Math.abs(baseStep - f.qBest.step) < 0.005) baselineSame = true;
        }
        return gp.persist >= GCD_PERSIST_REQ && !baselineSame;
    }

    @SuppressWarnings("unused")
    private double cadenceScore(UUID uuid) {
        Deque<Long> times = attackTimes.get(uuid);
        if (times == null || times.size() < 6) return 0.0;
        List<Long> list = new ArrayList<>(times);
        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < list.size(); i++) intervals.add(list.get(i) - list.get(i - 1));
        double mean = intervals.stream().mapToDouble(l -> l).average().orElse(0.0);
        if (mean <= 1e-3) return 0.0;
        double var = 0.0;
        for (long it : intervals) var += (it - mean) * (it - mean);
        var /= intervals.size();
        double std = Math.sqrt(var);
        double cv = std / mean;
        boolean plausibleCooldown = mean >= 350 && mean <= 800;
        return (cv <= 0.12 && plausibleCooldown) ? 0.35 : 0.0;
    }

    private boolean isPlayerAttacking(UUID uuid) {
        Long last = lastAttackTime.get(uuid);
        return last != null && System.currentTimeMillis() - last <= ATTACK_WINDOW_MS;
    }

    private static double wrapDegrees(double angle) {
        angle = angle % 360.0;
        if (angle >= 180.0) angle -= 360.0;
        if (angle < -180.0) angle += 360.0;
        return angle;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private void flag(Player player, String reason, String details) {
        Alert alert = new Alert(player, "KillAuraRotationA", AlertType.HIGH,
                getViolationLevel(player), reason + " - " + details);
        plugin.getAlertManager().sendAlert(alert);
        increaseViolationLevel(player);
    }

    @Override
    public void clearPlayerData(Player player) {
        UUID uuid = player.getUniqueId();
        rotationTrackers.remove(uuid);
        lastAttackTime.remove(uuid);
        recentAttacks.remove(uuid);
        suspicionScore.remove(uuid);
        lastFlagTime.remove(uuid);
        lastAnalysisTime.remove(uuid);
        aimWindows.remove(uuid);
        lastVictimId.remove(uuid);
        lastVictimWorld.remove(uuid);
        gcdPersist.remove(uuid);
        baselines.remove(uuid);
        attackTimes.remove(uuid);
        lastBaselineUpdate.remove(uuid);
        resetViolationLevel(player);
    }

    @Override
    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("checks.killaurarotationa.enabled", true);
    }

    private boolean isBatchReady(UUID uuid) {
        Deque<Long> times = attackTimes.get(uuid);
        if (times == null || times.size() < BATCH_MIN_HITS) return false;
        if (BATCH_WINDOW_MAX_MS <= 0) return true;
        Long first = times.peekFirst();
        Long last = times.peekLast();
        if (first == null || last == null) return false;
        return last - first <= BATCH_WINDOW_MAX_MS;
    }

    private void resetBatch(UUID uuid) {
        Deque<Long> times = attackTimes.get(uuid);
        if (times != null) times.clear();
        recentAttacks.put(uuid, 0);
    }

    private static class Baseline {
        double gcdYawStep, gcdYawCov, gcdPitchStep, gcdPitchCov;
        double microRatioAvg;
        double jerkZeroShareAvg;
    }
}