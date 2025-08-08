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
        description = "Detects hit through walls"
)
public class WallHit extends Check {

    private static final double MAX_CHECK_DISTANCE = 6.0;
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
        Location victimLoc = victim.getLocation();
        if (eye.getWorld() != victimLoc.getWorld()) return;
        if (eye.distanceSquared(victimLoc) > MAX_CHECK_DISTANCE * MAX_CHECK_DISTANCE) return;

        if (canReachAnyPartOfHitbox(eye, victim)) {
            return;
        }

        Block firstSolid = getFirstBlockingBlock(eye, victim);
        if (firstSolid != null) {
            String details = String.format("through:%s @ %d,%d,%d dist:%.2f",
                    firstSolid.getType().name(),
                    firstSolid.getX(), firstSolid.getY(), firstSolid.getZ(),
                    eye.distance(victimLoc));
            flag(player, victim.getUniqueId(), details);
        }
    }

    private boolean canReachAnyPartOfHitbox(Location eye, LivingEntity victim) {
        Location base = victim.getLocation();
        double width = getEntityWidth(victim);
        double height = victim.getHeight();

        double[] xOffsets = {-width/2, 0, width/2};
        double[] yOffsets = {0.1, height * 0.3, height * 0.6, height * 0.9};
        double[] zOffsets = {-width/2, 0, width/2};

        int accessiblePoints = 0;
        int totalPoints = 0;

        for (double xOff : xOffsets) {
            for (double yOff : yOffsets) {
                for (double zOff : zOffsets) {
                    Location targetPoint = base.clone().add(xOff, yOff, zOff);
                    totalPoints++;

                    if (firstBlockingAlongRay(eye, targetPoint) == null) {
                        accessiblePoints++;
                    }
                }
            }
        }

        return accessiblePoints >= totalPoints * 0.1;
    }

    private double getEntityWidth(LivingEntity entity) {
        if (entity instanceof Player) return 0.6;
        try {
            return entity.getBoundingBox().getWidthX();
        } catch (Throwable ignored) {
            return 0.6;
        }
    }

    private Block getFirstBlockingBlock(Location eye, LivingEntity victim) {
        Location center = victim.getLocation().add(0, victim.getHeight() * 0.5, 0);
        return firstBlockingAlongRay(eye, center);
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

            if (p.distanceSquared(end) <= 0.5) return null;

            int bx = (int) Math.floor(p.getX());
            int by = (int) Math.floor(p.getY());
            int bz = (int) Math.floor(p.getZ());
            Block b = w.getBlockAt(bx, by, bz);

            if (shouldIgnore(b)) continue;
            if (isDoorColumn(b)) continue;

            if (isNearBlockEdge(p, bx, by, bz, w, i * stepLen)) continue;

            return b;
        }
        return null;
    }

    private boolean isNearBlockEdge(Vector p, int bx, int by, int bz, World w, double rayDistance) {
        double fx = p.getX() - bx;
        double fy = p.getY() - by;
        double fz = p.getZ() - bz;

        if (fy >= 1.0 - EPS_TOP) return true;

        double edgeEps = rayDistance <= EDGE_ALLOW_DIST ? EPS_FACE * 2 : EPS_FACE;

        boolean nearXNeg = fx <= edgeEps;
        boolean nearXPos = fx >= 1.0 - edgeEps;
        boolean nearZNeg = fz <= edgeEps;
        boolean nearZPos = fz >= 1.0 - edgeEps;

        if (nearXNeg && isPathClear(w, bx - 1, by, bz)) return true;
        if (nearXPos && isPathClear(w, bx + 1, by, bz)) return true;
        if (nearZNeg && isPathClear(w, bx, by, bz - 1)) return true;
        if (nearZPos && isPathClear(w, bx, by, bz + 1)) return true;

        if (nearXNeg && nearZNeg && (isPathClear(w, bx - 1, by, bz) || isPathClear(w, bx, by, bz - 1))) return true;
        if (nearXPos && nearZNeg && (isPathClear(w, bx + 1, by, bz) || isPathClear(w, bx, by, bz - 1))) return true;
        if (nearXNeg && nearZPos && (isPathClear(w, bx - 1, by, bz) || isPathClear(w, bx, by, bz + 1))) return true;
        if (nearXPos && nearZPos && (isPathClear(w, bx + 1, by, bz) || isPathClear(w, bx, by, bz + 1))) return true;

        return false;
    }

    private boolean isPathClear(World w, int x, int y, int z) {
        Block b = w.getBlockAt(x, y, z);
        Block above = w.getBlockAt(x, y + 1, z);
        return isSeamOpen(b) && isSeamOpen(above);
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