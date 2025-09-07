package balance.genac.check.impl.movement;

import balance.genac.GenAC;
import balance.genac.alert.Alert;
import balance.genac.alert.AlertType;
import balance.genac.check.Check;
import balance.genac.check.CheckInfo;
import balance.genac.check.CheckType;
import balance.genac.utils.VersionUtils;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.Bukkit;

import java.util.*;

@CheckInfo(name = "InvMove", type = CheckType.MOVEMENT, description = "Detects interacting with inventory while movement")
public class InvMove extends Check {

    private static class Sample {
        double tickSpeed;
        long ts;
        Sample(double s, long t){ tickSpeed = s; ts = t; }
    }

    private static class Data {
        Deque<Sample> speeds = new ArrayDeque<>();
        long lastClickTs = 0L;
        int movingClicks = 0;
        double score = 0.0;
        long lastScoreUpdate = System.currentTimeMillis();
        Location lastLoc;
        long lastMoveTs = 0L;
    }

    private final Map<UUID, Data> map = new HashMap<>();

    private static final int SPEED_BUF = 12;
    private static final long CLICK_WINDOW_MS = 220L;
    private static final double THRESH_GROUND_TICK = 0.05;
    private static final double THRESH_AIR_TICK = 0.04;
    private static final double THRESH_SNEAK_TICK = 0.025;

    private static final double SCORE_GAIN = 2.2;
    private static final double SCORE_GAIN_STREAK = 3.0;
    private static final double SCORE_DECAY_PER_SEC = 0.8;
    private static final double FLAG_THRESHOLD = 4.0;
    private static final long FLAG_COOLDOWN_MS = 2500L;

    public InvMove(GenAC plugin) {
        super(plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!isEnabled()) return;
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();

        Data d = map.computeIfAbsent(id, k -> new Data());

        Location to = e.getTo();
        if (to == null) return;

        long now = System.currentTimeMillis();
        if (d.lastLoc != null) {
            double dx = to.getX() - d.lastLoc.getX();
            double dz = to.getZ() - d.lastLoc.getZ();
            double dist = Math.hypot(dx, dz);
            d.speeds.addLast(new Sample(Math.max(dist, 0.0), now));
            while (d.speeds.size() > SPEED_BUF) d.speeds.removeFirst();
        }
        d.lastLoc = to.clone();

        decayScore(d, now);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!isEnabled()) return;
        if (!(e.getWhoClicked() instanceof Player)) return;

        Player p = (Player) e.getWhoClicked();
        if (!eligible(p)) return;

        UUID id = p.getUniqueId();
        Data d = map.computeIfAbsent(id, k -> new Data());
        long now = System.currentTimeMillis();

        boolean real = isRealItemInteraction(e);
        if (!real) return;

        boolean movingNow = isMovingNow(p, d, now);

        if (detectOffhandFromInventory(p, e)) {
            if (cooldown(id, now)) {
                flag(p, "Offhand from inventory while interacting", "src=inventory click=" + e.getClick());
            }
            return;
        }

        if (mayEquipElytraClick(e)) {
            boolean movingAtClick = movingNow;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                ItemStack chest = p.getInventory().getChestplate();
                if (isElytra(chest) && movingAtClick) {
                    long t = System.currentTimeMillis();
                    if (cooldown(id, t)) {
                        flag(p, "Elytra equip while moving", "click=" + e.getClick());
                    }
                }
            });
        }

        evaluateInteraction(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent e) {
        if (!isEnabled()) return;
        if (!(e.getWhoClicked() instanceof Player)) return;

        Player p = (Player) e.getWhoClicked();
        if (!eligible(p)) return;

        UUID id = p.getUniqueId();
        Data d = map.computeIfAbsent(id, k -> new Data());
        long now = System.currentTimeMillis();

        boolean movingNow = isMovingNow(p, d, now);

        if (mayEquipElytraDrag(e)) {
            boolean movingAtDrag = movingNow;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                ItemStack chest = p.getInventory().getChestplate();
                if (isElytra(chest) && movingAtDrag) {
                    long t = System.currentTimeMillis();
                    if (cooldown(id, t)) {
                        flag(p, "Elytra equip while moving", "drag");
                    }
                }
            });
        }

        evaluateInteraction(p);
    }

    private void evaluateInteraction(Player p) {
        UUID id = p.getUniqueId();
        Data d = map.computeIfAbsent(id, k -> new Data());
        long now = System.currentTimeMillis();

        double avgTickSpeed = averageRecentTickSpeed(d.speeds, now, CLICK_WINDOW_MS);
        if (Double.isNaN(avgTickSpeed)) {
            d.lastClickTs = now;
            return;
        }

        double threshold = p.isOnGround() ? THRESH_GROUND_TICK : THRESH_AIR_TICK;
        if (p.isSneaking()) threshold = THRESH_SNEAK_TICK;

        decayScore(d, now);

        if (avgTickSpeed > threshold) {
            if (now - d.lastClickTs <= 1000L) {
                d.movingClicks++;
            } else {
                d.movingClicks = 1;
            }
            d.lastClickTs = now;

            double gain = d.movingClicks >= 3 ? SCORE_GAIN_STREAK : SCORE_GAIN;
            d.score = Math.min(12.0, d.score + gain);

            if (shouldFlag(id, d, now)) {
                flag(p, "Moving while clicking inventory",
                        String.format(Locale.US, "spdTick=%.3f thr=%.3f clicks=%d score=%.1f",
                                avgTickSpeed, threshold, d.movingClicks, d.score));
                d.lastScoreUpdate = now;
                d.score = Math.max(0.0, d.score - 2.0);
            }
        }
    }

    private boolean isRealItemInteraction(InventoryClickEvent e) {
        InventoryAction action = e.getAction();
        if (action == InventoryAction.NOTHING) return false;
        ClickType ct = e.getClick();
        switch (ct) {
            case WINDOW_BORDER_LEFT:
            case WINDOW_BORDER_RIGHT:
            case UNKNOWN:
                return false;
            default:
        }
        return true;
    }

    private double averageRecentTickSpeed(Deque<Sample> speeds, long now, long windowMs) {
        if (speeds == null || speeds.isEmpty()) return Double.NaN;
        int count = 0;
        double sum = 0.0;
        Iterator<Sample> it = speeds.descendingIterator();
        while (it.hasNext()) {
            Sample s = it.next();
            if (now - s.ts > windowMs) break;
            sum += s.tickSpeed;
            count++;
            if (count >= 4) break;
        }
        return count == 0 ? Double.NaN : (sum / count);
    }

    private boolean shouldFlag(UUID id, Data d, long now) {
        Long lastFlag = lastFlagTimes.get(id);
        if (d.score < FLAG_THRESHOLD) return false;
        if (lastFlag != null && now - lastFlag < FLAG_COOLDOWN_MS) return false;
        lastFlagTimes.put(id, now);
        return true;
    }

    private boolean cooldown(UUID id, long now) {
        Long lastFlag = lastFlagTimes.get(id);
        if (lastFlag != null && now - lastFlag < FLAG_COOLDOWN_MS) return false;
        lastFlagTimes.put(id, now);
        return true;
    }

    private void decayScore(Data d, long now) {
        long dt = now - d.lastScoreUpdate;
        if (dt <= 0) return;
        double dec = (dt / 1000.0) * SCORE_DECAY_PER_SEC;
        d.score = Math.max(0.0, d.score - dec);
        d.lastScoreUpdate = now;
    }

    private final Map<UUID, Long> lastFlagTimes = new HashMap<>();

    private boolean eligible(Player p) {
        if (p == null || !p.isOnline()) return false;
        if (p.hasPermission("genac.bypass")) return false;
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return false;
        if (p.isFlying() || p.isGliding()) return false;
        if (p.isInsideVehicle()) return false;
        if (p.isDead()) return false;
        if (isOnIceOrSlime(p)) return false;
        if (isInLiquid(p)) return false;
        return true;
    }

    private boolean isOnIceOrSlime(Player p) {
        Location loc = p.getLocation();
        Material m = loc.clone().subtract(0, 0.1, 0).getBlock().getType();

        Set<Material> iceTypes = VersionUtils.getCompatibleIceTypes();
        return iceTypes.contains(m) || m == Material.SLIME_BLOCK;
    }

    private boolean isInLiquid(Player p) {
        Material b = p.getLocation().getBlock().getType();
        Material b2 = p.getLocation().clone().add(0, 1, 0).getBlock().getType();
        switch (b) {
            case WATER:
            case LAVA:
            case BUBBLE_COLUMN:
                return true;
            default:
        }
        switch (b2) {
            case WATER:
            case LAVA:
            case BUBBLE_COLUMN:
                return true;
            default:
        }
        return false;
    }

    private void flag(Player player, String reason, String details) {
        Alert alert = new Alert(player, "InvMove", AlertType.MOVEMENT,
                getViolationLevel(player), reason + " - " + details);
        plugin.getAlertManager().sendAlert(alert);
        increaseViolationLevel(player);
    }

    private boolean isElytra(ItemStack it) {
        return it != null && it.getType() == Material.ELYTRA;
    }

    private boolean isMovingNow(Player p, Data d, long now) {
        double avg = averageRecentTickSpeed(d.speeds, now, CLICK_WINDOW_MS);
        if (Double.isNaN(avg)) return false;
        double thr = p.isOnGround() ? THRESH_GROUND_TICK : THRESH_AIR_TICK;
        if (p.isSneaking()) thr = THRESH_SNEAK_TICK;
        return avg > thr;
    }

    private boolean isPlayerInventoryClick(InventoryClickEvent e) {
        return e.getClickedInventory() != null && e.getView().getBottomInventory().equals(e.getClickedInventory());
    }

    private boolean mayEquipElytraClick(InventoryClickEvent e) {
        if (!isPlayerInventoryClick(e)) return false;
        if (e.getSlotType() == InventoryType.SlotType.ARMOR) {
            if (isElytra(e.getCursor()) || isElytra(e.getCurrentItem())) return true;
        }
        if ((e.getClick() == ClickType.SHIFT_LEFT || e.getClick() == ClickType.SHIFT_RIGHT) && isElytra(e.getCurrentItem())) {
            return true;
        }
        return false;
    }

    private boolean mayEquipElytraDrag(InventoryDragEvent e) {
        InventoryView view = e.getView();
        boolean hasElytraInNew = e.getNewItems().values().stream().anyMatch(this::isElytra);
        if (!hasElytraInNew && !isElytra(e.getOldCursor())) return false;
        for (int raw : e.getRawSlots()) {
            if (view.getSlotType(raw) == InventoryType.SlotType.ARMOR) return true;
        }
        return false;
    }

    private boolean detectOffhandFromInventory(Player p, InventoryClickEvent e) {
        if (!isPlayerInventoryClick(e)) return false;
        if (e.getClick() != ClickType.SWAP_OFFHAND) return false;
        InventoryType.SlotType t = e.getSlotType();
        if (t == InventoryType.SlotType.CONTAINER) {
            return true;
        }
        if (t == InventoryType.SlotType.QUICKBAR) {
            return false;
        }
        return false;
    }

    @Override
    public void clearPlayerData(Player player) {
        UUID id = player.getUniqueId();
        map.remove(id);
        lastFlagTimes.remove(id);
        resetViolationLevel(player);
    }

    @Override
    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("checks.invmove.enabled", true);
    }
}