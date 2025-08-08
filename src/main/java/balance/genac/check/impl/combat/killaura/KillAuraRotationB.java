package balance.genac.check.impl.combat.killaura;

import balance.genac.GenAC;
import balance.genac.alert.Alert;
import balance.genac.alert.AlertType;
import balance.genac.check.Check;
import balance.genac.check.CheckInfo;
import balance.genac.check.CheckType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.*;

@CheckInfo(
        name = "KillAuraRotationB",
        type = CheckType.COMBAT,
        description = "Detects snap-aim rotations around attack using buffered steps, GCD quantization and end-aim to nearby targets."
)
public class KillAuraRotationB extends Check {

    public KillAuraRotationB(GenAC plugin) { super(plugin); }

    private final Map<UUID, Data> map = new HashMap<>();

    // Буфер и окна анализа
    private static final long BUFFER_MAX_MS   = 1000L;  // держим до 1 сек истории
    private static final long PRE_WINDOW_MS   = 450L;   // анализ до удара
    private static final long POST_WINDOW_MS  = 150L;   // анализ после удара
    private static final long NEAR_ATTACK_MS  = 150L;   // конец последовательности должен быть близко к удару

    // Тики (шире, чтобы не терять при лаге)
    private static final long MIN_TICK_MS = 18L;
    private static final long MAX_TICK_MS = 140L;

    // Геометрия
    private static final float MIN_INITIAL_ERR_DEG = 10.0f; // стартовая ошибка
    private static final float MIN_STEP_DEG        = 0.35f; // минимальный шаг yaw
    private static final int   MIN_STEPS           = 2;
    private static final int   MAX_STEPS           = 7;

    // В конце — почти идеальное наведение на цель
    private static final float END_ERR_YAW_DEG   = 1.6f;
    private static final float END_ERR_PITCH_DEG = 3.0f;

    // Равномерность/доводка
    private static final float STEP_UNIF_STRICT = 0.22f;
    private static final float STEP_UNIF_BAL    = 0.40f;
    private static final float MIN_MEAN_YAW_STEP = 1.00f;
    private static final float LAST_STEP_RATIO_STRICT = 0.75f;
    private static final float LAST_STEP_RATIO_BAL    = 0.85f;

    // Квантизация (GCD)
    private static final float GCD_MIN      = 0.010f;
    private static final float GCD_EPS      = 0.020f;
    private static final float GCD_COVERAGE_BAL = 0.60f; // доля шагов, кратных GCD

    // Поиск целей
    private static final double TARGET_RADIUS = 5.5;
    private static final double[] AIM_Y_FRAC = {0.85, 0.65, 0.50}; // голова/грудь/центр

    // Антиспам алертов
    private static final long ALERT_COOLDOWN_MS = 1200L;

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!isEnabled()) return;
        if (e.getFrom().equals(e.getTo())) return;

        Player p = e.getPlayer();
        if (p.hasPermission("genac.bypass")) return;

        UUID id = p.getUniqueId();
        Data d = map.computeIfAbsent(id, k -> new Data());

        long now = System.currentTimeMillis();
        float fromYaw = e.getFrom().getYaw();
        float toYaw   = e.getTo().getYaw();
        float fromPitch = e.getFrom().getPitch();
        float toPitch   = e.getTo().getPitch();

        float dYaw   = wrapDeg(toYaw - fromYaw);
        float dPitch = toPitch - fromPitch;
        if (Math.abs(dYaw) < 0.02f && Math.abs(dPitch) < 0.02f) return;

        d.push(new RotSample(now, fromYaw, toYaw, fromPitch, toPitch, dYaw, dPitch));
        d.trimOld(now - BUFFER_MAX_MS);

        // Если недавно был удар — через 3–4 тика анализируем окно вокруг удара
        if (d.pending && now - d.lastAttack >= 60L && d.lastAnalyzedAttack != d.lastAttack) {
            analyzeAroundAttack(p, d, now);
            d.lastAnalyzedAttack = d.lastAttack;
            d.pending = false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player)) return;

        Player p = (Player) e.getDamager();
        if (p.hasPermission("genac.bypass")) return;

        UUID id = p.getUniqueId();
        Data d = map.computeIfAbsent(id, k -> new Data());

        d.lastAttack = System.currentTimeMillis();
        d.lastVictim = e.getEntity().getUniqueId();
        d.pending = true;

        // Если пост-движения не будет — все равно проанализируем через ~200мс
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Data dd = map.get(id);
            if (dd != null && dd.pending && System.currentTimeMillis() - dd.lastAttack >= 80L) {
                analyzeAroundAttack(p, dd, System.currentTimeMillis());
                dd.lastAnalyzedAttack = dd.lastAttack;
                dd.pending = false;
            }
        }, 4L);
    }

    private void analyzeAroundAttack(Player p, Data d, long now) {
        if (now - d.lastAlert < ALERT_COOLDOWN_MS) return;

        long start = d.lastAttack - PRE_WINDOW_MS;
        long end   = d.lastAttack + POST_WINDOW_MS;
        List<RotSample> win = d.window(start, end);
        if (win.isEmpty()) return;

        List<List<RotSample>> seqs = buildCandidates(win);
        if (seqs.isEmpty()) return;

        List<AimTarget> targets = collectNearbyTargets(p);

        for (List<RotSample> seq : seqs) {
            if (checkSequenceAndAlert(p, d, seq, targets, d.lastAttack)) {
                break; // уже отправили алерт
            }
        }
    }

    // Последовательности подряд шагов одного знака yaw в тиковом окне
    private List<List<RotSample>> buildCandidates(List<RotSample> win) {
        List<List<RotSample>> out = new ArrayList<>();
        List<RotSample> curr = new ArrayList<>();
        Float sign = null;

        for (int i = 0; i < win.size(); i++) {
            RotSample s = win.get(i);
            boolean big = Math.abs(s.dYaw) >= MIN_STEP_DEG;

            long dt = (i == 0 ? 50L : s.time - win.get(i - 1).time);
            boolean tickOk = dt >= MIN_TICK_MS && dt <= MAX_TICK_MS;

            float sgn = Math.signum(s.dYaw);

            if (big && tickOk && sgn != 0.0f) {
                if (curr.isEmpty()) {
                    curr.add(s);
                    sign = sgn;
                } else if (sgn == sign) {
                    curr.add(s);
                } else {
                    if (curr.size() >= MIN_STEPS) out.add(new ArrayList<>(curr));
                    curr.clear(); curr.add(s); sign = sgn;
                }
            } else {
                if (curr.size() >= MIN_STEPS) out.add(new ArrayList<>(curr));
                curr.clear(); sign = null;
            }
        }
        if (curr.size() >= MIN_STEPS) out.add(curr);
        return out;
    }

    private List<AimTarget> collectNearbyTargets(Player p) {
        List<AimTarget> res = new ArrayList<>();
        Location eye = p.getEyeLocation();
        World w = p.getWorld();

        for (Entity e : w.getNearbyEntities(eye, TARGET_RADIUS, TARGET_RADIUS, TARGET_RADIUS)) {
            if (!(e instanceof LivingEntity)) continue;
            LivingEntity le = (LivingEntity) e;
            if (le.isDead() || le.equals(p)) continue;

            for (double frac : AIM_Y_FRAC) {
                Location aim = le.getLocation().add(0, Math.max(0.1, le.getHeight() * frac), 0);
                double[] rot = idealRot(eye, aim);
                res.add(new AimTarget(le.getUniqueId(), (float) rot[0], (float) rot[1]));
            }
        }
        return res;
    }

    // Вся логика детекта тут: без класса Detection — алерт шлётся сразу
    private boolean checkSequenceAndAlert(Player p, Data d, List<RotSample> seq, List<AimTarget> targets, long attackTime) {
        int n = seq.size();
        if (n < MIN_STEPS || n > MAX_STEPS) return false;

        float[] yaw = new float[n];
        float[] pitch = new float[n];
        long[] dts = new long[n];

        for (int i = 0; i < n; i++) {
            RotSample s = seq.get(i);
            yaw[i] = Math.abs(s.dYaw);
            pitch[i] = Math.abs(s.dPitch);
            dts[i] = (i == 0 ? (n > 1 ? (seq.get(1).time - s.time) : 50L) : (s.time - seq.get(i - 1).time));
        }

        // Тиковость
        for (long t : dts) if (t < MIN_TICK_MS || t > MAX_TICK_MS) return false;

        RotSample first = seq.get(0);
        RotSample last  = seq.get(n - 1);

        // Должно закончиться рядом с ударом
        if ((attackTime - last.time) > NEAR_ATTACK_MS) return false;

        // Стартовая ошибка по yaw к любой цели
        float initialErrYaw = Float.MAX_VALUE;
        for (AimTarget at : targets) {
            initialErrYaw = Math.min(initialErrYaw, Math.abs(wrapDeg(first.fromYaw - at.yaw)));
        }
        if (initialErrYaw < MIN_INITIAL_ERR_DEG) return false;

        // Конечная ошибка по yaw/pitch к любой цели (берем минимум)
        float bestEndYawErr = Float.MAX_VALUE;
        float bestEndPitchErr = Float.MAX_VALUE;
        for (AimTarget at : targets) {
            float ey = Math.abs(wrapDeg(last.toYaw - at.yaw));
            float ep = Math.abs(last.toPitch - at.pitch);
            if (ey < bestEndYawErr) {
                bestEndYawErr = ey;
                bestEndPitchErr = ep;
            }
        }
        if (bestEndYawErr > END_ERR_YAW_DEG) return false;
        if (bestEndPitchErr > END_ERR_PITCH_DEG) return false;

        // Средний шаг yaw
        float meanYaw = 0f; for (float v : yaw) meanYaw += v; meanYaw /= n;
        if (meanYaw < MIN_MEAN_YAW_STEP) return false;

        // Равномерность по первым n-1 шагам
        int main = Math.max(1, n - 1);
        float max = 0, min = Float.MAX_VALUE, sum = 0, sum2 = 0;
        for (int i = 0; i < main; i++) {
            max = Math.max(max, yaw[i]);
            min = Math.min(min, yaw[i]);
            sum += yaw[i];
            sum2 += yaw[i] * yaw[i];
        }
        float mean = sum / Math.max(1, main);
        float uniformity = (max - min) / Math.max(1e-6f, max);
        float variance = Math.max(0f, (sum2 / Math.max(1, main)) - (mean * mean));
        float cv = (mean > 1e-6f ? (float) (Math.sqrt(variance) / mean) : 1f);

        // Квантизация (GCD) и покрытие
        GcdResult gr = estimateGcdAndCoverage(yaw, n);
        boolean gcdStrict = (gr.gcd >= GCD_MIN && gr.allQuantized);
        boolean gcdBal    = (gr.gcd >= GCD_MIN && gr.coverage >= GCD_COVERAGE_BAL);

        // Доводка (последний шаг меньше среднего)
        boolean lastSmallerStrict = yaw[n - 1] <= mean * LAST_STEP_RATIO_STRICT;
        boolean lastSmallerBal    = yaw[n - 1] <= mean * LAST_STEP_RATIO_BAL;

        boolean strictPass =
                uniformity <= STEP_UNIF_STRICT &&
                        gcdStrict &&
                        lastSmallerStrict;

        boolean balancedPass =
                (uniformity <= STEP_UNIF_BAL || cv <= 0.35f) &&
                        gcdBal &&
                        lastSmallerBal;

        if (!(strictPass || balancedPass)) return false;

        // Всё сошлось — отправляем алерт прямо отсюда
        if (System.currentTimeMillis() - d.lastAlert >= ALERT_COOLDOWN_MS) {
            String dbg = String.format(
                    "steps:%d uni:%.2f gcd:%s endYawErr:%.2f dtOK:Y",
                    n, uniformity, (strictPass || balancedPass) && gr.gcd >= GCD_MIN ? String.format("%.3f", gr.gcd) : "n/a",
                    bestEndYawErr
            );
            sendAlert(p, "Snap-aim rotation", dbg);
            d.lastAlert = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    private void sendAlert(Player p, String reason, String details) {
        Alert alert = new Alert(
                p,
                "KillAuraRotationB",
                AlertType.EXPERIMENTAL,
                getViolationLevel(p),
                reason + " | " + details
        );
        plugin.getAlertManager().sendAlert(alert);
        increaseViolationLevel(p);
    }

    // ==== utils/data ====

    private static class AimTarget {
        final UUID id;
        final float yaw, pitch;
        AimTarget(UUID id, float yaw, float pitch) { this.id = id; this.yaw = yaw; this.pitch = pitch; }
    }

    private static class Data {
        long lastAttack = 0L;
        long lastAnalyzedAttack = -1L;
        long lastAlert = 0L;
        UUID lastVictim = null;
        boolean pending = false;

        final Deque<RotSample> buf = new ArrayDeque<>(128);

        void push(RotSample s) {
            if (buf.size() >= 128) buf.removeFirst();
            buf.addLast(s);
        }
        void trimOld(long minTime) {
            while (!buf.isEmpty() && buf.peekFirst().time < minTime) buf.removeFirst();
        }
        List<RotSample> window(long from, long to) {
            List<RotSample> res = new ArrayList<>();
            for (RotSample s : buf) if (s.time >= from && s.time <= to) res.add(s);
            return res;
        }
    }

    private static class RotSample {
        final long time;
        final float fromYaw, toYaw, fromPitch, toPitch;
        final float dYaw, dPitch;
        RotSample(long time, float fromYaw, float toYaw, float fromPitch, float toPitch, float dYaw, float dPitch) {
            this.time = time;
            this.fromYaw = fromYaw;
            this.toYaw = toYaw;
            this.fromPitch = fromPitch;
            this.toPitch = toPitch;
            this.dYaw = dYaw;
            this.dPitch = dPitch;
        }
    }

    private static class GcdResult {
        final float gcd;
        final boolean allQuantized;
        final float coverage;
        GcdResult(float gcd, boolean allQuantized, float coverage) {
            this.gcd = gcd; this.allQuantized = allQuantized; this.coverage = coverage;
        }
    }

    private static double[] idealRot(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double h = Math.hypot(dx, dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0F);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, h)));
        return new double[]{yaw, pitch};
    }

    private static float wrapDeg(float a) {
        a %= 360f;
        if (a >= 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    private GcdResult estimateGcdAndCoverage(float[] steps, int n) {
        // берём первые до 4 шагов (кроме финального) для оценки gcd
        List<Float> sample = new ArrayList<>();
        int cap = Math.min(n - 1, 4);
        for (int i = 0; i < cap; i++) if (steps[i] >= MIN_STEP_DEG) sample.add(steps[i]);
        if (sample.size() < 2) {
            for (int i = 0; i < n; i++) if (steps[i] >= MIN_STEP_DEG) sample.add(steps[i]);
        }
        if (sample.size() < 2) return new GcdResult(0f, false, 0f);

        Set<Float> candidates = new HashSet<>();
        for (int i = 0; i < sample.size(); i++) {
            for (int j = i + 1; j < sample.size(); j++) {
                float g = gcdFloat(sample.get(i), sample.get(j), GCD_EPS);
                if (g >= GCD_MIN) candidates.add(approx(g, 0.001f));
            }
        }
        if (candidates.isEmpty()) return new GcdResult(0f, false, 0f);

        float bestG = 0f; float bestCov = 0f; boolean bestAll = false;
        for (float g : candidates) {
            int total = 0, ok = 0; boolean all = true;
            for (int i = 0; i < n; i++) {
                float v = steps[i];
                if (v < MIN_STEP_DEG) continue;
                total++;
                float k = Math.round(v / g);
                float q = Math.abs(v - k * g);
                boolean hit = (q <= GCD_EPS || Math.abs(g - q) <= GCD_EPS);
                if (hit) ok++; else all = false;
            }
            if (total == 0) continue;
            float cov = ok / (float) total;
            if (cov > bestCov || (Math.abs(cov - bestCov) <= 1e-6 && g > bestG)) {
                bestG = g; bestCov = cov; bestAll = all;
            }
        }
        return new GcdResult(bestG, bestAll, bestCov);
    }

    private static float approx(float v, float step) {
        return Math.round(v / step) * step;
    }

    private static float gcdFloat(float a, float b, float eps) {
        a = Math.abs(a); b = Math.abs(b);
        if (a < b) { float t = a; a = b; b = t; }
        if (b < eps) return a;
        for (int i = 0; i < 18; i++) {
            float r = a % b;
            if (r < eps || Math.abs(b - r) < eps) return b;
            a = b; b = r;
        }
        return b;
    }

    @Override
    public void clearPlayerData(Player player) {
        map.remove(player.getUniqueId());
        resetViolationLevel(player);
    }

    @Override
    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("checks.killaurarotationb.enabled", true);
    }
}