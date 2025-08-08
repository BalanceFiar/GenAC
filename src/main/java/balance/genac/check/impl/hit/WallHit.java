package balance.genac.check.impl.hit;

import balance.genac.GenAC;
import balance.genac.alert.Alert;
import balance.genac.alert.AlertType;
import balance.genac.check.Check;
import balance.genac.check.CheckInfo;
import balance.genac.check.CheckType;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.material.Door;
import org.bukkit.material.Gate;
import org.bukkit.material.MaterialData;
import org.bukkit.material.TrapDoor;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@CheckInfo(
        name = "WallHit",
        type = CheckType.COMBAT,
        description = "Detects melee hits through solid walls while ignoring legit doors/door-columns and edge/seam cases."
)
public class WallHit extends Check {

    private static final double MAX_CHECK_DISTANCE = 6.0;
    private static final double[] AIM_Y_FRAC = {0.85, 0.65, 0.50};
    private static final double STEP = 0.12;
    private static final double EPS_TOP = 0.06;
    private static final double EPS_FACE = 0.06;
    private static final double EDGE_ALLOW_DIST = 0.55;

    private static final Set<Material> WHITELIST = new HashSet<>();
    static {
        addIfExists("AIR");
        addIfExists("CAVE_AIR");
        addIfExists("VOID_AIR");
        addIfExists("WATER");
        addIfExists("LAVA");
        addIfExists("GRASS");
        addIfExists("TALL_GRASS");
        addIfExists("FERN");
        addIfExists("LARGE_FERN");
        addIfExists("SEAGRASS");
        addIfExists("KELP");
        addIfExists("KELP_PLANT");
        addIfExists("VINE");
        addIfExists("LILY_PAD");
        addIfExists("SUGAR_CANE");
        addIfExists("COBWEB");
    }

    public WallHit(GenAC plugin) { super(plugin); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player)) return;
        if (!(e.getEntity() instanceof LivingEntity)) return;

        Player player = (Player) e.getDamager();
        LivingEntity victim = (LivingEntity) e.getEntity();
        if (!isEnabled()) return;
        if (player.hasPermission("genac.bypass")) return;

        Location eye = player.getEyeLocation();
        Location mid = victim.getLocation().add(0, victim.getHeight() * 0.5, 0);
        if (eye.getWorld() != mid.getWorld()) return;
        if (eye.distanceSquared(mid) > MAX_CHECK_DISTANCE * MAX_CHECK_DISTANCE) return;

        boolean blockedAll = true;
        Block firstSolid = null;

        for (double frac : AIM_Y_FRAC) {
            Location aim = victim.getLocation().add(0, Math.max(0.1, victim.getHeight() * frac), 0);
            Block hit = firstBlockingAlongRay(eye, aim);
            if (hit == null) {
                blockedAll = false;
                break;
            } else if (firstSolid == null) {
                firstSolid = hit;
            }
        }

        if (blockedAll && firstSolid != null) {
            String details = String.format("through:%s @ %d,%d,%d dist:%.2f",
                    firstSolid.getType().name(),
                    firstSolid.getX(), firstSolid.getY(), firstSolid.getZ(),
                    eye.distance(mid));
            flag(player, victim.getUniqueId(), details);
        }
    }

    private Block firstBlockingAlongRay(Location from, Location to) {
        if (from == null || to == null) return null;
        World w = from.getWorld();
        if (w == null || w != to.getWorld()) return null;

        Vector start = from.toVector();
        Vector end = to.toVector();
        Vector dir = end.clone().subtract(start);
        double dist = dir.length();
        if (dist <= 1e-6) return null;
        dir.normalize();

        int steps = Math.max(1, (int) Math.ceil(dist / STEP));
        double stepLen = dist / steps;

        for (int i = 0; i <= steps; i++) {
            Vector p = start.clone().add(dir.clone().multiply(stepLen * i));
            if (p.distanceSquared(end) <= 0.25) return null;

            int bx = (int) Math.floor(p.getX());
            int by = (int) Math.floor(p.getY());
            int bz = (int) Math.floor(p.getZ());
            Block b = w.getBlockAt(bx, by, bz);

            if (shouldIgnore(b)) continue;
            if (isDoorColumn(b)) continue;

            double topY = by + 1.0;
            if (p.getY() >= topY - EPS_TOP) continue;

            double fx = p.getX() - bx;
            double fz = p.getZ() - bz;

            boolean seamXNeg = fx <= EPS_FACE && (isSeamColumnOpen(w, bx - 1, by, bz) || (i * stepLen <= EDGE_ALLOW_DIST && isSeamOpen(w.getBlockAt(bx - 1, by, bz))));
            boolean seamXPos = fx >= 1.0 - EPS_FACE && (isSeamColumnOpen(w, bx + 1, by, bz) || (i * stepLen <= EDGE_ALLOW_DIST && isSeamOpen(w.getBlockAt(bx + 1, by, bz))));
            boolean seamZNeg = fz <= EPS_FACE && (isSeamColumnOpen(w, bx, by, bz - 1) || (i * stepLen <= EDGE_ALLOW_DIST && isSeamOpen(w.getBlockAt(bx, by, bz - 1))));
            boolean seamZPos = fz >= 1.0 - EPS_FACE && (isSeamColumnOpen(w, bx, by, bz + 1) || (i * stepLen <= EDGE_ALLOW_DIST && isSeamOpen(w.getBlockAt(bx, by, bz + 1))));

            if (seamXNeg || seamXPos || seamZNeg || seamZPos) continue;

            return b;
        }
        return null;
    }

    private boolean isSeamColumnOpen(World w, int x, int y, int z) {
        Block b0 = w.getBlockAt(x, y, z);
        Block b1 = w.getBlockAt(x, y + 1, z);
        return isSeamOpen(b0) && isSeamOpen(b1);
    }

    private boolean isSeamOpen(Block b) {
        return b == null || shouldIgnore(b) || isDoorColumn(b);
    }

    private boolean shouldIgnore(Block b) {
        Material m = b.getType();
        if (m == null) return true;
        if (WHITELIST.contains(m)) return true;
        try { if (b.isPassable()) return true; } catch (Throwable ignored) {}
        if (isAnyOpenable(b)) return true;
        try { if (!m.isOccluding()) return true; } catch (Throwable ignored) {}
        return false;
    }

    private boolean isAnyOpenable(Block b) {
        try {
            BlockData bd = b.getBlockData();
            if (bd instanceof Openable) return true;
        } catch (Throwable ignored) {}
        try {
            MaterialData md = b.getState().getData();
            if (md instanceof Door) return true;
            if (md instanceof TrapDoor) return true;
            if (md instanceof Gate) return true;
        } catch (Throwable ignored) {}
        return isDoorMaterial(b.getType());
    }

    private boolean isDoorColumn(Block b) {
        if (isDoorBlock(b)) return true;
        Block below1 = b.getRelative(0, -1, 0);
        if (isDoorBlock(below1)) return true;
        Block below2 = b.getRelative(0, -2, 0);
        if (isDoorBlock(below2)) return true;
        return false;
    }

    private boolean isDoorBlock(Block b) {
        if (b == null) return false;
        try {
            BlockData bd = b.getBlockData();
            if (bd != null && bd.getClass().getName().endsWith(".Door")) return true;
        } catch (Throwable ignored) {}
        try {
            MaterialData md = b.getState().getData();
            if (md instanceof Door) return true;
        } catch (Throwable ignored) {}
        return isDoorMaterial(b.getType());
    }

    private boolean isDoorMaterial(Material m) {
        if (m == null) return false;
        String name = m.name();
        if (name.contains("TRAPDOOR") || name.contains("TRAP_DOOR")) return false;
        return name.endsWith("_DOOR") || name.equals("WOOD_DOOR") || name.equals("WOODEN_DOOR") || name.equals("IRON_DOOR") || name.equals("IRON_DOOR_BLOCK");
    }

    private void flag(Player player, UUID victimId, String details) {
        Alert alert = new Alert(
                player,
                "WallHit",
                AlertType.HIGH,
                getViolationLevel(player),
                "Hit through wall | " + details
        );
        plugin.getAlertManager().sendAlert(alert);
        increaseViolationLevel(player);
    }

    private static void addIfExists(String name) {
        try {
            Material m = Material.valueOf(name);
            if (m != null) WHITELIST.add(m);
        } catch (Throwable ignored) {}
    }

    @Override
    public void clearPlayerData(Player player) {
        resetViolationLevel(player);
    }

    @Override
    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("checks.wallhit.enabled", true);
    }
}