package balance.genac.check.impl.world;

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
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;

@CheckInfo(
        name = "WallBuild",
        type = CheckType.WORLD,
        description = "Detects placing and breaking blocks through walls"
)
public class WallBuild extends Check {

    private static final double MAX_BUILD_DISTANCE = 5.5;
    private static final double STEP_SIZE = 0.1;
    private static final double MIN_VISIBLE_PERCENTAGE = 0.01;

    private static final Set<Material> TRANSPARENT_BLOCKS = new HashSet<>();

    static {
        addTransparent("AIR");
        addTransparent("CAVE_AIR");
        addTransparent("VOID_AIR");
        addTransparent("WATER");
        addTransparent("LAVA");
        addTransparent("GRASS");
        addTransparent("TALL_GRASS");
        addTransparent("FERN");
        addTransparent("LARGE_FERN");
        addTransparent("SEAGRASS");
        addTransparent("TALL_SEAGRASS");
        addTransparent("KELP");
        addTransparent("KELP_PLANT");
        addTransparent("VINE");
        addTransparent("SUGAR_CANE");
        addTransparent("COBWEB");
        addTransparent("TORCH");
        addTransparent("WALL_TORCH");
        addTransparent("REDSTONE_TORCH");
        addTransparent("REDSTONE_WALL_TORCH");
        addTransparent("SOUL_TORCH");
        addTransparent("SOUL_WALL_TORCH");
        addTransparent("LEVER");
        addTransparent("TRIPWIRE");
        addTransparent("TRIPWIRE_HOOK");
        addTransparent("FLOWER_POT");
        addTransparent("SNOW");
        addTransparent("LADDER");
        addTransparent("RAIL");
        addTransparent("POWERED_RAIL");
        addTransparent("DETECTOR_RAIL");
        addTransparent("ACTIVATOR_RAIL");
        addTransparent("REDSTONE_WIRE");
        addTransparent("SIGN");
        addTransparent("WALL_SIGN");

        addTransparent("STONE_BUTTON");
        addTransparent("OAK_BUTTON");
        addTransparent("SPRUCE_BUTTON");
        addTransparent("BIRCH_BUTTON");
        addTransparent("JUNGLE_BUTTON");
        addTransparent("ACACIA_BUTTON");
        addTransparent("DARK_OAK_BUTTON");
        addTransparent("CRIMSON_BUTTON");
        addTransparent("WARPED_BUTTON");

        addTransparent("STONE_PRESSURE_PLATE");
        addTransparent("LIGHT_WEIGHTED_PRESSURE_PLATE");
        addTransparent("HEAVY_WEIGHTED_PRESSURE_PLATE");
        addTransparent("OAK_PRESSURE_PLATE");
        addTransparent("SPRUCE_PRESSURE_PLATE");
        addTransparent("BIRCH_PRESSURE_PLATE");
        addTransparent("JUNGLE_PRESSURE_PLATE");
        addTransparent("ACACIA_PRESSURE_PLATE");
        addTransparent("DARK_OAK_PRESSURE_PLATE");
        addTransparent("CRIMSON_PRESSURE_PLATE");
        addTransparent("WARPED_PRESSURE_PLATE");

        addTransparent("WHITE_CARPET");
        addTransparent("ORANGE_CARPET");
        addTransparent("MAGENTA_CARPET");
        addTransparent("LIGHT_BLUE_CARPET");
        addTransparent("YELLOW_CARPET");
        addTransparent("LIME_CARPET");
        addTransparent("PINK_CARPET");
        addTransparent("GRAY_CARPET");
        addTransparent("LIGHT_GRAY_CARPET");
        addTransparent("CYAN_CARPET");
        addTransparent("PURPLE_CARPET");
        addTransparent("BLUE_CARPET");
        addTransparent("BROWN_CARPET");
        addTransparent("GREEN_CARPET");
        addTransparent("RED_CARPET");
        addTransparent("BLACK_CARPET");

        addTransparent("DANDELION");
        addTransparent("POPPY");
        addTransparent("BLUE_ORCHID");
        addTransparent("ALLIUM");
        addTransparent("AZURE_BLUET");
        addTransparent("RED_TULIP");
        addTransparent("ORANGE_TULIP");
        addTransparent("WHITE_TULIP");
        addTransparent("PINK_TULIP");
        addTransparent("OXEYE_DAISY");
        addTransparent("CORNFLOWER");
        addTransparent("LILY_OF_THE_VALLEY");
        addTransparent("WITHER_ROSE");
        addTransparent("SUNFLOWER");
        addTransparent("LILAC");
        addTransparent("ROSE_BUSH");
        addTransparent("PEONY");


        addTransparent("OAK_SAPLING");
        addTransparent("SPRUCE_SAPLING");
        addTransparent("BIRCH_SAPLING");
        addTransparent("JUNGLE_SAPLING");
        addTransparent("ACACIA_SAPLING");
        addTransparent("DARK_OAK_SAPLING");


        addTransparent("BROWN_MUSHROOM");
        addTransparent("RED_MUSHROOM");
        addTransparent("CRIMSON_FUNGUS");
        addTransparent("WARPED_FUNGUS");


        addTransparent("WHITE_BANNER");
        addTransparent("WHITE_WALL_BANNER");


        addTransparent("SKELETON_SKULL");
        addTransparent("SKELETON_WALL_SKULL");
        addTransparent("WITHER_SKELETON_SKULL");
        addTransparent("WITHER_SKELETON_WALL_SKULL");
        addTransparent("ZOMBIE_HEAD");
        addTransparent("ZOMBIE_WALL_HEAD");
        addTransparent("PLAYER_HEAD");
        addTransparent("PLAYER_WALL_HEAD");
        addTransparent("CREEPER_HEAD");
        addTransparent("CREEPER_WALL_HEAD");
        addTransparent("DRAGON_HEAD");
        addTransparent("DRAGON_WALL_HEAD");
    }

    public WallBuild(GenAC plugin) {
        super(plugin);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!isEnabled()) return;

        Player player = event.getPlayer();
        if (player.hasPermission("genac.bypass")) return;

        Block placedBlock = event.getBlockPlaced();
        Block againstBlock = event.getBlockAgainst();

        if (!checkBlockAction(player, placedBlock, againstBlock, "PLACE")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!isEnabled()) return;

        Player player = event.getPlayer();
        if (player.hasPermission("genac.bypass")) return;

        Block brokenBlock = event.getBlock();

        if (!checkBlockAction(player, brokenBlock, null, "BREAK")) {
            event.setCancelled(true);
        }
    }

    private boolean checkBlockAction(Player player, Block targetBlock, Block againstBlock, String action) {
        Location eyeLocation = player.getEyeLocation();
        Location blockLocation = targetBlock.getLocation().add(0.5, 0.5, 0.5);

        double distance = eyeLocation.distance(blockLocation);


        if (distance > MAX_BUILD_DISTANCE) {
            flag(player, action, targetBlock, distance, 0.0, "Too far");
            return false;
        }


        if (hasDirectLineOfSight(eyeLocation, blockLocation, targetBlock, againstBlock)) {
            return true;
        }


        double visiblePercentage = calculateBlockVisibility(eyeLocation, targetBlock, againstBlock);

        if (visiblePercentage < MIN_VISIBLE_PERCENTAGE) {
            flag(player, action, targetBlock, distance, visiblePercentage, "Through wall");
            return false;
        }

        return true;
    }

    private double calculateBlockVisibility(Location eyeLocation, Block targetBlock, Block againstBlock) {
        Location blockLoc = targetBlock.getLocation();
        int totalChecks = 0;
        int visibleChecks = 0;


        for (BlockFace face : new BlockFace[]{
                BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
                BlockFace.WEST, BlockFace.UP, BlockFace.DOWN}) {

            double[][] points = getFaceCheckPoints(face);

            for (double[] point : points) {
                Location checkLoc = blockLoc.clone().add(point[0], point[1], point[2]);
                totalChecks++;

                if (hasDirectLineOfSight(eyeLocation, checkLoc, targetBlock, againstBlock)) {
                    visibleChecks++;
                }
            }
        }


        double step = 0.33;
        for (double x = 0.2; x <= 0.8; x += step) {
            for (double y = 0.2; y <= 0.8; y += step) {
                for (double z = 0.2; z <= 0.8; z += step) {
                    Location checkLoc = blockLoc.clone().add(x, y, z);
                    totalChecks++;

                    if (hasDirectLineOfSight(eyeLocation, checkLoc, targetBlock, againstBlock)) {
                        visibleChecks++;
                    }
                }
            }
        }

        return (double) visibleChecks / totalChecks;
    }

    private double[][] getFaceCheckPoints(BlockFace face) {
        switch (face) {
            case NORTH:
                return new double[][]{
                        {0.2, 0.2, 0}, {0.5, 0.2, 0}, {0.8, 0.2, 0},
                        {0.2, 0.5, 0}, {0.5, 0.5, 0}, {0.8, 0.5, 0},
                        {0.2, 0.8, 0}, {0.5, 0.8, 0}, {0.8, 0.8, 0}
                };
            case SOUTH:
                return new double[][]{
                        {0.2, 0.2, 1}, {0.5, 0.2, 1}, {0.8, 0.2, 1},
                        {0.2, 0.5, 1}, {0.5, 0.5, 1}, {0.8, 0.5, 1},
                        {0.2, 0.8, 1}, {0.5, 0.8, 1}, {0.8, 0.8, 1}
                };
            case EAST:
                return new double[][]{
                        {1, 0.2, 0.2}, {1, 0.5, 0.2}, {1, 0.8, 0.2},
                        {1, 0.2, 0.5}, {1, 0.5, 0.5}, {1, 0.8, 0.5},
                        {1, 0.2, 0.8}, {1, 0.5, 0.8}, {1, 0.8, 0.8}
                };
            case WEST:
                return new double[][]{
                        {0, 0.2, 0.2}, {0, 0.5, 0.2}, {0, 0.8, 0.2},
                        {0, 0.2, 0.5}, {0, 0.5, 0.5}, {0, 0.8, 0.5},
                        {0, 0.2, 0.8}, {0, 0.5, 0.8}, {0, 0.8, 0.8}
                };
            case UP:
                return new double[][]{
                        {0.2, 1, 0.2}, {0.5, 1, 0.2}, {0.8, 1, 0.2},
                        {0.2, 1, 0.5}, {0.5, 1, 0.5}, {0.8, 1, 0.5},
                        {0.2, 1, 0.8}, {0.5, 1, 0.8}, {0.8, 1, 0.8}
                };
            case DOWN:
                return new double[][]{
                        {0.2, 0, 0.2}, {0.5, 0, 0.2}, {0.8, 0, 0.2},
                        {0.2, 0, 0.5}, {0.5, 0, 0.5}, {0.8, 0, 0.5},
                        {0.2, 0, 0.8}, {0.5, 0, 0.8}, {0.8, 0, 0.8}
                };
            default:
                return new double[][]{{0.5, 0.5, 0.5}};
        }
    }

    private boolean hasDirectLineOfSight(Location from, Location to, Block targetBlock, Block againstBlock) {
        if (from.getWorld() != to.getWorld()) return false;

        World world = from.getWorld();
        Vector start = from.toVector();
        Vector end = to.toVector();
        Vector direction = end.clone().subtract(start);
        double totalDistance = direction.length();

        if (totalDistance < 0.01) return true;

        direction.normalize();
        double currentDistance = 0;

        while (currentDistance < totalDistance) {
            currentDistance += STEP_SIZE;
            if (currentDistance > totalDistance) {
                currentDistance = totalDistance;
            }

            Vector currentPoint = start.clone().add(direction.clone().multiply(currentDistance));

            Block blockAtPoint = world.getBlockAt(
                    (int) Math.floor(currentPoint.getX()),
                    (int) Math.floor(currentPoint.getY()),
                    (int) Math.floor(currentPoint.getZ())
            );


            if (blockAtPoint.equals(targetBlock)) continue;
            if (againstBlock != null && blockAtPoint.equals(againstBlock)) continue;


            if (!isTransparent(blockAtPoint)) {

                double localX = currentPoint.getX() - Math.floor(currentPoint.getX());
                double localY = currentPoint.getY() - Math.floor(currentPoint.getY());
                double localZ = currentPoint.getZ() - Math.floor(currentPoint.getZ());

                boolean onEdge = localX < 0.05 || localX > 0.95 ||
                        localY < 0.05 || localY > 0.95 ||
                        localZ < 0.05 || localZ > 0.95;

                if (!onEdge) {
                    return false;
                }


                if (!checkAdjacentTransparency(world, blockAtPoint, currentPoint)) {
                    return false;
                }
            }
        }

        return true;
    }

    private boolean checkAdjacentTransparency(World world, Block block, Vector point) {
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        double localX = point.getX() - x;
        double localY = point.getY() - y;
        double localZ = point.getZ() - z;


        if (localX < 0.05 && isTransparent(world.getBlockAt(x - 1, y, z))) return true;
        if (localX > 0.95 && isTransparent(world.getBlockAt(x + 1, y, z))) return true;
        if (localY < 0.05 && isTransparent(world.getBlockAt(x, y - 1, z))) return true;
        if (localY > 0.95 && isTransparent(world.getBlockAt(x, y + 1, z))) return true;
        if (localZ < 0.05 && isTransparent(world.getBlockAt(x, y, z - 1))) return true;
        if (localZ > 0.95 && isTransparent(world.getBlockAt(x, y, z + 1))) return true;

        return false;
    }

    private boolean isTransparent(Block block) {
        if (block == null) return true;

        Material type = block.getType();


        if (TRANSPARENT_BLOCKS.contains(type)) return true;


        if (!type.isOccluding()) return true;

        String name = type.name();


        if (name.contains("GLASS") || name.contains("PANE")) return true;
        if (name.contains("DOOR") || name.contains("GATE")) return true;
        if (name.contains("SLAB") || name.contains("STAIRS")) return true;
        if (name.contains("FENCE") && !name.contains("GATE")) return true;
        if (name.contains("WALL") && !name.contains("BANNER") && !name.contains("SIGN") && !name.contains("SKULL") && !name.contains("HEAD") && !name.contains("TORCH")) return true;
        if (name.contains("LEAVES")) return true;
        if (name.contains("BARS")) return true;

        return false;
    }

    private void flag(Player player, String action, Block block, double distance, double visibility, String reason) {
        String details = String.format("%s %s at %d,%d,%d | Distance: %.2f | Visible: %.1f%% | %s",
                action,
                block.getType().name(),
                block.getX(), block.getY(), block.getZ(),
                distance,
                visibility * 100,
                reason
        );

        Alert alert = new Alert(
                player,
                "WallBuild",
                AlertType.HIGH,
                getViolationLevel(player),
                details
        );

        plugin.getAlertManager().sendAlert(alert);
        increaseViolationLevel(player);
    }

    private static void addTransparent(String name) {
        try {
            Material material = Material.valueOf(name);
            if (material != null) {
                TRANSPARENT_BLOCKS.add(material);
            }
        } catch (IllegalArgumentException ignored) {}
    }

    @Override
    public void clearPlayerData(Player player) {
        resetViolationLevel(player);
    }

    @Override
    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("checks.wallbuild.enabled", true);
    }
}