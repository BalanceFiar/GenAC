package balance.genac.check.impl.combat.hit;

import balance.genac.GenAC;
import balance.genac.alert.Alert;
import balance.genac.alert.AlertType;
import balance.genac.check.Check;
import balance.genac.check.CheckInfo;
import balance.genac.check.CheckType;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.*;

@CheckInfo(
        name = "Reach",
        type = CheckType.COMBAT,
        description = "Detects attacks beyond maximum reach distance"
)
public class Reach extends Check {

    private static final Set<EntityType> BLACKLISTED = new HashSet<>(Arrays.asList(
            EntityType.BOAT,
            EntityType.SHULKER
    ));

    private static final double DEFAULT_REACH = 3.0;
    private static final double CREATIVE_REACH = 5.0;
    private static final double THRESHOLD = 0.0005;
    private static final double MOVEMENT_THRESHOLD = 0.03;
    private static final double MOVEMENT_REACH_BONUS = 0.3;

    private final Map<UUID, AttackData> recentAttacks = new HashMap<>();
    private final Map<UUID, Double> cancelBuffer = new HashMap<>();
    private final Map<UUID, Vector> lastPositions = new HashMap<>();

    public Reach(GenAC plugin) {
        super(plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        if (!isEnabled()) return;

        Player player = (Player) event.getDamager();
        Entity target = event.getEntity();

        if (player.hasPermission("genac.bypass")) return;
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;
        if (player.isInsideVehicle()) return;
        if (target.isInsideVehicle()) return;

        if (isBlacklisted(target)) return;
        if (!isValidTarget(target)) return;

        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();

        AttackData lastAttack = recentAttacks.get(playerId);
        if (lastAttack != null && currentTime - lastAttack.time < 50) {
            if (shouldCancelHits()) {
                event.setCancelled(true);
            }
            return;
        }

        recentAttacks.put(playerId, new AttackData(currentTime, target.getUniqueId()));

        CheckResult result = checkReach(player, target);

        double buffer = cancelBuffer.getOrDefault(playerId, 0.0);
        boolean shouldCancel = false;

        if (result.isFlag()) {
            String details = String.format("%.3f blocks (max: %.1f) target: %s",
                    result.distance, result.maxReach, target.getType().name());
            flag(player, target.getUniqueId(), details);

            cancelBuffer.put(playerId, 1.0);
            shouldCancel = shouldCancelHits();
        } else if (buffer > 0) {
            CheckResult bufferResult = checkReachWithBuffer(player, target);
            if (bufferResult.isFlag()) {
                shouldCancel = shouldCancelHits();
            }
            cancelBuffer.put(playerId, Math.max(0, buffer - 0.25));
        }

        lastPositions.put(playerId, player.getLocation().toVector());

        if (shouldCancel) {
            event.setCancelled(true);
        }
    }

    private CheckResult checkReach(Player player, Entity target) {
        Location eyeLocation = player.getEyeLocation();
        BoundingBox targetBox = getTargetBoundingBox(target);

        double maxReach = getMaxReach(player);
        double movementBonus = getMovementBonus(player);
        maxReach += movementBonus;

        targetBox = expandBoundingBox(targetBox, THRESHOLD + MOVEMENT_THRESHOLD);

        double minDistance = calculateMinDistance(eyeLocation, targetBox);

        if (minDistance > maxReach) {
            return new CheckResult(true, minDistance, maxReach);
        }

        return new CheckResult(false, minDistance, maxReach);
    }

    private CheckResult checkReachWithBuffer(Player player, Entity target) {
        Location eyeLocation = player.getEyeLocation();
        BoundingBox targetBox = getTargetBoundingBox(target);

        double maxReach = getMaxReach(player);
        double movementBonus = getMovementBonus(player);
        maxReach += movementBonus;

        double minDistance = calculateMinDistance(eyeLocation, targetBox);

        if (minDistance > maxReach) {
            return new CheckResult(true, minDistance, maxReach);
        }

        return new CheckResult(false, minDistance, maxReach);
    }

    private double getMovementBonus(Player player) {
        UUID playerId = player.getUniqueId();
        Vector currentPos = player.getLocation().toVector();
        Vector lastPos = lastPositions.get(playerId);

        if (lastPos == null) {
            return 0.0;
        }

        double movement = currentPos.distance(lastPos);
        if (movement > 0.1) {
            return MOVEMENT_REACH_BONUS;
        }

        return 0.0;
    }

    private double calculateMinDistance(Location eyeLocation, BoundingBox targetBox) {
        Vector eyePos = eyeLocation.toVector();
        Vector lookDirection = eyeLocation.getDirection();

        if (isInsideBox(eyePos, targetBox)) {
            return 0.0;
        }

        Vector boxCenter = new Vector(
                (targetBox.getMinX() + targetBox.getMaxX()) / 2,
                (targetBox.getMinY() + targetBox.getMaxY()) / 2,
                (targetBox.getMinZ() + targetBox.getMaxZ()) / 2
        );

        double directDistance = eyePos.distance(boxCenter);
        double minDistance = directDistance;

        Vector intercept = calculateBoxIntercept(eyePos, lookDirection, targetBox);
        if (intercept != null) {
            minDistance = Math.min(minDistance, eyePos.distance(intercept));
        }

        double closestPoint = getClosestPointDistance(eyePos, targetBox);
        minDistance = Math.min(minDistance, closestPoint);

        return minDistance;
    }

    private double getClosestPointDistance(Vector point, BoundingBox box) {
        double x = Math.max(box.getMinX(), Math.min(point.getX(), box.getMaxX()));
        double y = Math.max(box.getMinY(), Math.min(point.getY(), box.getMaxY()));
        double z = Math.max(box.getMinZ(), Math.min(point.getZ(), box.getMaxZ()));

        return point.distance(new Vector(x, y, z));
    }

    private Vector calculateBoxIntercept(Vector start, Vector direction, BoundingBox box) {
        Vector min = new Vector(box.getMinX(), box.getMinY(), box.getMinZ());
        Vector max = new Vector(box.getMaxX(), box.getMaxY(), box.getMaxZ());

        double tMin = Double.NEGATIVE_INFINITY;
        double tMax = Double.POSITIVE_INFINITY;

        for (int i = 0; i < 3; i++) {
            double startCoord = getVectorComponent(start, i);
            double dirCoord = getVectorComponent(direction, i);
            double minCoord = getVectorComponent(min, i);
            double maxCoord = getVectorComponent(max, i);

            if (Math.abs(dirCoord) < 1e-10) {
                if (startCoord < minCoord || startCoord > maxCoord) {
                    return null;
                }
            } else {
                double t1 = (minCoord - startCoord) / dirCoord;
                double t2 = (maxCoord - startCoord) / dirCoord;

                if (t1 > t2) {
                    double temp = t1;
                    t1 = t2;
                    t2 = temp;
                }

                tMin = Math.max(tMin, t1);
                tMax = Math.min(tMax, t2);

                if (tMin > tMax) {
                    return null;
                }
            }
        }

        if (tMin >= 0 && tMin <= 10.0) {
            return start.clone().add(direction.clone().multiply(tMin));
        } else if (tMax >= 0 && tMax <= 10.0) {
            return start.clone().add(direction.clone().multiply(tMax));
        }

        return null;
    }

    private double getVectorComponent(Vector vector, int index) {
        switch (index) {
            case 0: return vector.getX();
            case 1: return vector.getY();
            case 2: return vector.getZ();
            default: throw new IllegalArgumentException("Invalid component index: " + index);
        }
    }

    private boolean isInsideBox(Vector point, BoundingBox box) {
        return point.getX() >= box.getMinX() && point.getX() <= box.getMaxX() &&
                point.getY() >= box.getMinY() && point.getY() <= box.getMaxY() &&
                point.getZ() >= box.getMinZ() && point.getZ() <= box.getMaxZ();
    }

    private BoundingBox getTargetBoundingBox(Entity target) {
        if (target.getType() == EntityType.ENDER_CRYSTAL) {
            Location loc = target.getLocation();
            return new BoundingBox(
                    loc.getX() - 1, loc.getY(), loc.getZ() - 1,
                    loc.getX() + 1, loc.getY() + 2, loc.getZ() + 1
            );
        }

        BoundingBox box = target.getBoundingBox();
        return new BoundingBox(
                box.getMinX(), box.getMinY(), box.getMinZ(),
                box.getMaxX(), box.getMaxY(), box.getMaxZ()
        );
    }

    private BoundingBox expandBoundingBox(BoundingBox box, double expansion) {
        return new BoundingBox(
                box.getMinX() - expansion, box.getMinY() - expansion, box.getMinZ() - expansion,
                box.getMaxX() + expansion, box.getMaxY() + expansion, box.getMaxZ() + expansion
        );
    }

    private double getMaxReach(Player player) {
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) {
            return CREATIVE_REACH;
        }
        return DEFAULT_REACH;
    }

    private boolean shouldCancelHits() {
        return plugin.getConfig().getBoolean("checks.reach.cancel", true);
    }

    private boolean isBlacklisted(Entity entity) {
        return BLACKLISTED.contains(entity.getType());
    }

    private boolean isValidTarget(Entity entity) {
        if (entity instanceof LivingEntity) return true;
        if (entity.getType() == EntityType.ENDER_CRYSTAL) return true;
        return false;
    }

    private void flag(Player player, UUID targetId, String details) {
        Alert alert = new Alert(
                player,
                "Reach",
                AlertType.HIGH,
                getViolationLevel(player),
                "Reach violation | " + details
        );
        plugin.getAlertManager().sendAlert(alert);
        increaseViolationLevel(player);
    }

    @Override
    public void clearPlayerData(Player player) {
        UUID playerId = player.getUniqueId();
        recentAttacks.remove(playerId);
        cancelBuffer.remove(playerId);
        lastPositions.remove(playerId);
        resetViolationLevel(player);
    }

    @Override
    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("checks.reach.enabled", true);
    }

    private static class AttackData {
        final long time;
        final UUID targetId;

        AttackData(long time, UUID targetId) {
            this.time = time;
            this.targetId = targetId;
        }
    }

    private static class CheckResult {
        final boolean flag;
        final double distance;
        final double maxReach;

        CheckResult(boolean flag, double distance, double maxReach) {
            this.flag = flag;
            this.distance = distance;
            this.maxReach = maxReach;
        }

        boolean isFlag() {
            return flag;
        }
    }
}