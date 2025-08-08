package balance.genac.check.impl.baritone;

import balance.genac.GenAC;
import balance.genac.alert.Alert;
import balance.genac.alert.AlertType;
import balance.genac.check.Check;
import balance.genac.check.CheckInfo;
import balance.genac.check.CheckType;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.*;

@CheckInfo(
        name = "Baritone",
        type = CheckType.MOVEMENT,
        description = "Rotation-only Baritone detector"
)
public class Baritone extends Check {

    public Baritone(GenAC plugin) { super(plugin); }

    private static final long ROT_WINDOW_MS = 6000;
    private static final long MINED_WINDOW_MS = 300_000;
    private static final int MINED_MIN_COUNT = 10;

    private static final int MIN_DELTAS = 14;
    private static final int MIN_MICRO = 10;

    private static final double YAW_ZERO_EPS = 0.30;
    private static final double DPITCH_MIN = 0.01;
    private static final double DPITCH_MAX = 1.20;

    private static final double STEP_SCAN_MIN = 0.01;
    private static final double STEP_SCAN_MAX = 0.50;
    private static final double STEP_SCAN_STEP = 0.001;

    private static final double FIT_REQUIRED = 0.78;
    private static final double FIT_TOL_BASE = 0.006;
    private static final double FIT_TOL_FACTOR = 0.18;

    private static final double PHASE_STD_BASE = 0.005;
    private static final double PHASE_STD_FACTOR = 0.12;

    private static final int RUN_MIN = 6;
    private static final double RUN_TOL_FACTOR = 0.14;

    private static final double HIST_PEAK_MIN = 0.70;

    private static final double YAW_STD_MAX = 0.18;
    private static final double YAW_SPIKE_DEG = 10.0;
    private static final long YAW_SPIKE_CLUSTER_GAP_MS = 400;
    private static final int YAW_SPIKE_CLUSTER_MIN = 2;
    private static final int YAW_ZIGZAG_MIN = 1;

    private static final int SCORE_FLAG = 2;
    private static final long DECAY_MS = 1200;

    private final Map<UUID, RotData> map = new HashMap<>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!isEnabled()) return;
        Player p = e.getPlayer();
        if (p.hasPermission("genac.bypass")) return;
        RotData d = map.computeIfAbsent(p.getUniqueId(), k -> new RotData());
        long now = System.currentTimeMillis();
        d.mined.addLast(now);
        pruneTimes(d.mined, now, MINED_WINDOW_MS);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!isEnabled()) return;
        Player p = e.getPlayer();
        if (p.hasPermission("genac.bypass")) return;
        if (p.getGameMode() == GameMode.SPECTATOR) return;
        if (e.getTo() == null) return;

        RotData d = map.computeIfAbsent(p.getUniqueId(), k -> new RotData());
        long now = System.currentTimeMillis();
        pruneTimes(d.mined, now, MINED_WINDOW_MS);
        if (d.mined.size() < MINED_MIN_COUNT) {
            decay(d, now);
            d.hasLast = false;
            return;
        }

        float yaw = normYaw(e.getTo().getYaw());
        float pitch = e.getTo().getPitch();

        if (!d.hasLast) {
            d.lastYaw = yaw;
            d.lastPitch = pitch;
            d.hasLast = true;
            return;
        }

        double dyaw = angleDiff(d.lastYaw, yaw);
        double dpitch = Math.abs(pitch - d.lastPitch);

        d.lastYaw = yaw;
        d.lastPitch = pitch;

        d.deltas.addLast(new Delta(now, dyaw, dpitch));
        pruneDeltas(d.deltas, now, ROT_WINDOW_MS);

        if (d.deltas.size() < MIN_DELTAS) {
            decay(d, now);
            return;
        }

        List<Double> micro = new ArrayList<>();
        for (Delta it : d.deltas) {
            if (Math.abs(it.dyaw) <= YAW_ZERO_EPS && it.dpitch >= DPITCH_MIN && it.dpitch <= DPITCH_MAX) {
                micro.add(it.dpitch);
            }
        }
        if (micro.size() < MIN_MICRO) {
            decay(d, now);
            return;
        }

        Fit fit = bestFit(micro);
        boolean sQuant = fit.fit >= FIT_REQUIRED;

        boolean sPhase = false;
        double phaseStd = 999;
        if (fit.step > 0) {
            phaseStd = phaseStd(micro, fit.step);
            sPhase = phaseStd <= (PHASE_STD_BASE + PHASE_STD_FACTOR * fit.step);
        }

        boolean sRun = hasRun(micro, fit.step, RUN_MIN, RUN_TOL_FACTOR);

        boolean sHist = histogramPeak(micro) >= HIST_PEAK_MIN;

        double yawStd = yawStd(d.deltas);
        boolean sYawStable = yawStd <= YAW_STD_MAX;

        YawSpikes spikes = yawSpikes(d.deltas);
        boolean sYawSpikes = (spikes.clusterCount >= 1 && spikes.maxClusterLen >= YAW_SPIKE_CLUSTER_MIN) || (spikes.zigzags >= YAW_ZIGZAG_MIN);

        boolean support = sPhase || sRun || sHist;
        boolean passed = (sQuant && (sYawStable || sYawSpikes) && support) || (sQuant && fit.fit >= 0.90 && yawStd <= 0.25);

        if (passed) {
            d.score++;
            d.lastUpdate = now;
            if (d.score >= SCORE_FLAG) {
                flag(p, String.format("step=%.4f fit=%.2f phaseStd=%.4f run=%s hist=%.2f yawStd=%.3f spikes={clusters=%d maxLen=%d zigzags=%d} micro=%d/%d",
                        fit.step, fit.fit, phaseStd, sRun, histogramPeak(micro), yawStd, spikes.clusterCount, spikes.maxClusterLen, spikes.zigzags, micro.size(), d.deltas.size()));
                d.score = 0;
                d.deltas.clear();
            }
        } else {
            decay(d, now);
        }
    }

    private Fit bestFit(List<Double> vals) {
        Collections.sort(vals);
        double bestFit = 0.0, bestStep = 0.0;
        for (double step = STEP_SCAN_MIN; step <= STEP_SCAN_MAX + 1e-12; step += STEP_SCAN_STEP) {
            double tol = Math.max(FIT_TOL_BASE, step * FIT_TOL_FACTOR);
            int ok = 0;
            for (double v : vals) {
                double rem = remainderToNearestMultiple(v, step);
                if (rem <= tol) ok++;
            }
            double f = ok / (double) vals.size();
            if (f > bestFit) {
                bestFit = f;
                bestStep = step;
            }
        }
        return new Fit(bestStep, bestFit);
    }

    private boolean hasRun(List<Double> vals, double step, int minLen, double tolFactor) {
        if (vals.isEmpty() || step <= 0) return false;
        double tol = step * tolFactor;
        int best = 1, cur = 1;
        for (int i = 1; i < vals.size(); i++) {
            if (Math.abs(vals.get(i) - vals.get(i - 1)) <= tol) {
                cur++;
                best = Math.max(best, cur);
            } else {
                cur = 1;
            }
        }
        return best >= minLen;
    }

    private double phaseStd(List<Double> vals, double step) {
        if (vals.isEmpty() || step <= 0) return 999;
        List<Double> rems = new ArrayList<>(vals.size());
        for (double v : vals) rems.add(remainderToNearestMultiple(v, step));
        double mean = rems.stream().mapToDouble(x -> x).average().orElse(0);
        double var = 0;
        for (double r : rems) var += (r - mean) * (r - mean);
        var /= rems.size();
        return Math.sqrt(var);
    }

    private double histogramPeak(List<Double> values) {
        if (values.isEmpty()) return 0.0;
        double binW = 0.01;
        Map<Integer, Integer> hist = new HashMap<>();
        for (double v : values) {
            int bin = (int) Math.floor(v / binW);
            hist.put(bin, hist.getOrDefault(bin, 0) + 1);
        }
        int total = values.size();
        int top = 0, second = 0;
        for (int c : hist.values()) {
            if (c > top) { second = top; top = c; }
            else if (c > second) { second = c; }
        }
        return (top + second) / (double) total;
    }

    private double yawStd(Deque<Delta> deltas) {
        if (deltas.size() < 2) return 0.0;
        double sum = 0, sum2 = 0;
        int n = 0;
        for (Delta d : deltas) { double v = Math.abs(d.dyaw); sum += v; sum2 += v * v; n++; }
        double mean = sum / n;
        return Math.sqrt(Math.max(0, (sum2 / n) - mean * mean));
    }

    private YawSpikes yawSpikes(Deque<Delta> deltas) {
        int clusters = 0, maxLen = 0, zigzags = 0;
        long lastTime = -1;
        int lastSign = 0, runLen = 0;
        for (Delta d : deltas) {
            double ady = Math.abs(d.dyaw);
            if (ady >= YAW_SPIKE_DEG) {
                int sign = d.dyaw > 0 ? 1 : -1;
                if (lastTime < 0 || (d.time - lastTime) > YAW_SPIKE_CLUSTER_GAP_MS) {
                    clusters++;
                    runLen = 1;
                } else {
                    runLen++;
                }
                maxLen = Math.max(maxLen, runLen);
                if (lastSign != 0 && sign != lastSign) zigzags++;
                lastSign = sign;
                lastTime = d.time;
            }
        }
        return new YawSpikes(clusters, maxLen, zigzags);
    }

    private double remainderToNearestMultiple(double v, double step) {
        if (step <= 0) return v;
        double k = Math.round(v / step);
        return Math.abs(v - k * step);
    }

    private void pruneTimes(Deque<Long> dq, long now, long window) {
        while (!dq.isEmpty() && now - dq.peekFirst() > window) dq.pollFirst();
    }

    private void pruneDeltas(Deque<Delta> dq, long now, long window) {
        while (!dq.isEmpty() && now - dq.peekFirst().time > window) dq.pollFirst();
    }

    private void decay(RotData d, long now) {
        if (now - d.lastUpdate > DECAY_MS && d.score > 0) {
            d.score--;
            d.lastUpdate = now;
        }
    }

    private float normYaw(float yaw) {
        float y = yaw % 360f;
        if (y < -180f) y += 360f;
        if (y > 180f) y -= 360f;
        return y;
    }

    private double angleDiff(double a, double b) {
        double d = b - a;
        while (d > 180) d -= 360;
        while (d < -180) d += 360;
        return d;
    }

    private void flag(Player p, String details) {
        Alert alert = new Alert(
                p,
                "Baritone",
                AlertType.HIGH,
                getViolationLevel(p),
                "Rotation-only | " + details
        );
        plugin.getAlertManager().sendAlert(alert);
        increaseViolationLevel(p);
    }

    @Override
    public void clearPlayerData(Player player) {
        map.remove(player.getUniqueId());
        resetViolationLevel(player);
    }

    @Override
    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("checks.baritone.enabled", true);
    }

    private static class RotData {
        boolean hasLast = false;
        float lastYaw, lastPitch;
        Deque<Delta> deltas = new ArrayDeque<>();
        Deque<Long> mined = new ArrayDeque<>();
        int score = 0;
        long lastUpdate = 0;
    }

    private static class Delta {
        final long time;
        final double dyaw;
        final double dpitch;
        Delta(long time, double dyaw, double dpitch) { this.time = time; this.dyaw = dyaw; this.dpitch = dpitch; }
    }

    private static class Fit {
        final double step;
        final double fit;
        Fit(double step, double fit) { this.step = step; this.fit = fit; }
    }

    private static class YawSpikes {
        final int clusterCount;
        final int maxClusterLen;
        final int zigzags;
        YawSpikes(int clusterCount, int maxClusterLen, int zigzags) {
            this.clusterCount = clusterCount; this.maxClusterLen = maxClusterLen; this.zigzags = zigzags;
        }
    }
}