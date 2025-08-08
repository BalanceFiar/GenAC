package balance.genac.check.impl.combat.killaura;

import balance.genac.GenAC;
import balance.genac.alert.Alert;
import balance.genac.alert.AlertType;
import balance.genac.check.Check;
import balance.genac.check.CheckInfo;
import balance.genac.check.CheckType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.*;

@CheckInfo(name = "KillAuraRotationB", type = CheckType.COMBAT, description = "For snap rotations")
public class KillAuraRotationB extends Check {

    private final Map<UUID, RotationData> rotData = new HashMap<>();
    private final Map<UUID, Deque<JerkData>> jerkHistory = new HashMap<>();
    private final Map<UUID, Long> lastFlag = new HashMap<>();

    private static final int BATCH_SIZE = 5;
    private static final float JERK_THRESHOLD = 75.0f;
    private static final int MIN_VIOLATIONS = 3;
    private static final long FLAG_COOLDOWN = 3000L;

    private static class RotationData {
        float lastYawDelta;
        float lastPitchDelta;
        long lastAttack;
    }

    private static class JerkData {
        float yawJerk;
        float pitchJerk;
        long timestamp;
    }

    public KillAuraRotationB(GenAC plugin) {
        super(plugin);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!isEnabled()) return;
        Player p = e.getPlayer();
        if (p.hasPermission("genac.bypass")) return;

        UUID u = p.getUniqueId();
        RotationData rot = rotData.computeIfAbsent(u, k -> new RotationData());

        float currentYawDelta = Math.abs(wrapDegrees(e.getTo().getYaw() - e.getFrom().getYaw()));
        float currentPitchDelta = Math.abs(e.getTo().getPitch() - e.getFrom().getPitch());

        if (System.currentTimeMillis() - rot.lastAttack < 500) {
            float yawJerk = Math.abs(currentYawDelta - rot.lastYawDelta);
            float pitchJerk = Math.abs(currentPitchDelta - rot.lastPitchDelta);

            if (yawJerk > JERK_THRESHOLD || pitchJerk > JERK_THRESHOLD) {
                Deque<JerkData> history = jerkHistory.computeIfAbsent(u, k -> new ArrayDeque<>());
                JerkData jerk = new JerkData();
                jerk.yawJerk = yawJerk;
                jerk.pitchJerk = pitchJerk;
                jerk.timestamp = System.currentTimeMillis();
                history.addLast(jerk);

                while (history.size() > BATCH_SIZE) {
                    history.removeFirst();
                }

                if (history.size() == BATCH_SIZE) {
                    analyzeJerkBatch(p, history);
                }
            }
        }

        rot.lastYawDelta = currentYawDelta;
        rot.lastPitchDelta = currentPitchDelta;
    }

    @EventHandler
    public void onAttack(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player)) return;
        Player p = (Player) e.getDamager();
        UUID u = p.getUniqueId();
        RotationData rot = rotData.computeIfAbsent(u, k -> new RotationData());
        rot.lastAttack = System.currentTimeMillis();
    }

    private void analyzeJerkBatch(Player p, Deque<JerkData> history) {
        int violations = 0;
        float maxYawJerk = 0;
        float maxPitchJerk = 0;
        float avgYawJerk = 0;
        float avgPitchJerk = 0;

        for (JerkData jerk : history) {
            if (jerk.yawJerk > JERK_THRESHOLD || jerk.pitchJerk > JERK_THRESHOLD) {
                violations++;
            }
            maxYawJerk = Math.max(maxYawJerk, jerk.yawJerk);
            maxPitchJerk = Math.max(maxPitchJerk, jerk.pitchJerk);
            avgYawJerk += jerk.yawJerk;
            avgPitchJerk += jerk.pitchJerk;
        }

        avgYawJerk /= history.size();
        avgPitchJerk /= history.size();

        if (violations >= MIN_VIOLATIONS) {
            UUID u = p.getUniqueId();
            long now = System.currentTimeMillis();
            Long last = lastFlag.get(u);

            if (last == null || now - last >= FLAG_COOLDOWN) {
                increaseViolationLevel(p);
                Alert alert = new Alert(p, "KillAuraRotationB", AlertType.HIGH,
                        getViolationLevel(p),
                        String.format("Jerk pattern: %d/%d violations, maxY=%.1f maxP=%.1f avgY=%.1f avgP=%.1f",
                                violations, BATCH_SIZE, maxYawJerk, maxPitchJerk, avgYawJerk, avgPitchJerk));
                plugin.getAlertManager().sendAlert(alert);
                lastFlag.put(u, now);
                history.clear();
            }
        }
    }

    private static float wrapDegrees(float angle) {
        angle = angle % 360.0f;
        if (angle >= 180.0f) angle -= 360.0f;
        if (angle < -180.0f) angle += 360.0f;
        return angle;
    }

    @Override
    public void clearPlayerData(Player player) {
        UUID u = player.getUniqueId();
        rotData.remove(u);
        jerkHistory.remove(u);
        lastFlag.remove(u);
        resetViolationLevel(player);
    }

    @Override
    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("checks.killaurarotationb.enabled", true);
    }
}