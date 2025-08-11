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
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;

@CheckInfo(
        name = "WallInteract",
        type = CheckType.WORLD,
        description = "Detects interaction with blocks through walls"
)
public class WallInteract extends Check {

    private static final double MAX_INTERACT_DISTANCE = 6.0;
    private static final double STEP = 0.02;
    private static final double MIN_VISIBLE_PERCENTAGE = 0.05;

    private static final Set<Material> INTERACTABLE_BLOCKS = new HashSet<>();
    private static final Set<Material> PASSABLE_BLOCKS = new HashSet<>();

    static {
        addInteractable("FURNACE");
        addInteractable("BLAST_FURNACE");
        addInteractable("SMOKER");
        addInteractable("CHEST");
        addInteractable("TRAPPED_CHEST");
        addInteractable("ENDER_CHEST");
        addInteractable("BARREL");
        addInteractable("ANVIL");
        addInteractable("CHIPPED_ANVIL");
        addInteractable("DAMAGED_ANVIL");
        addInteractable("CRAFTING_TABLE");
        addInteractable("ENCHANTING_TABLE");
        addInteractable("GRINDSTONE");
        addInteractable("SMITHING_TABLE");
        addInteractable("STONECUTTER");
        addInteractable("LECTERN");
        addInteractable("LOOM");
        addInteractable("CARTOGRAPHY_TABLE");
        addInteractable("FLETCHING_TABLE");
        addInteractable("BREWING_STAND");
        addInteractable("DISPENSER");
        addInteractable("DROPPER");
        addInteractable("HOPPER");
        addInteractable("SHULKER_BOX");
        for (String color : new String[]{"WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME", "PINK", "GRAY", "LIGHT_GRAY", "CYAN", "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK"}) {
            addInteractable(color + "_SHULKER_BOX");
        }

        addPassable("AIR");
        addPassable("CAVE_AIR");
        addPassable("VOID_AIR");
        addPassable("WATER");
        addPassable("LAVA");
        addPassable("GRASS");
        addPassable("TALL_GRASS");
        addPassable("FERN");
        addPassable("LARGE_FERN");
        addPassable("SEAGRASS");
        addPassable("TALL_SEAGRASS");
        addPassable("KELP");
        addPassable("KELP_PLANT");
        addPassable("VINE");
        addPassable("LILY_PAD");
        addPassable("SUGAR_CANE");
        addPassable("COBWEB");
        addPassable("TORCH");
        addPassable("WALL_TORCH");
        addPassable("REDSTONE_TORCH");
        addPassable("REDSTONE_WALL_TORCH");
        addPassable("SOUL_TORCH");
        addPassable("SOUL_WALL_TORCH");
        addPassable("LEVER");
        addPassable("BUTTON");
        addPassable("STONE_BUTTON");
        addPassable("OAK_BUTTON");
        addPassable("SPRUCE_BUTTON");
        addPassable("BIRCH_BUTTON");
        addPassable("JUNGLE_BUTTON");
        addPassable("ACACIA_BUTTON");
        addPassable("DARK_OAK_BUTTON");
        addPassable("CRIMSON_BUTTON");
        addPassable("WARPED_BUTTON");
        addPassable("SIGN");
        addPassable("WALL_SIGN");
        addPassable("LADDER");
        addPassable("RAIL");
        addPassable("POWERED_RAIL");
        addPassable("DETECTOR_RAIL");
        addPassable("ACTIVATOR_RAIL");
        addPassable("REDSTONE_WIRE");
        addPassable("TRIPWIRE");
        addPassable("TRIPWIRE_HOOK");
        addPassable("FLOWER_POT");
        addPassable("CARPET");
        addPassable("SNOW");
        addPassable("PRESSURE_PLATE");
        addPassable("STONE_PRESSURE_PLATE");
        addPassable("LIGHT_WEIGHTED_PRESSURE_PLATE");
        addPassable("HEAVY_WEIGHTED_PRESSURE_PLATE");
        addPassable("OAK_PRESSURE_PLATE");
        addPassable("SPRUCE_PRESSURE_PLATE");
        addPassable("BIRCH_PRESSURE_PLATE");
        addPassable("JUNGLE_PRESSURE_PLATE");
        addPassable("ACACIA_PRESSURE_PLATE");
        addPassable("DARK_OAK_PRESSURE_PLATE");
        addPassable("CRIMSON_PRESSURE_PLATE");
        addPassable("WARPED_PRESSURE_PLATE");
    }

    public WallInteract(GenAC plugin) {
        super(plugin);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        Block clickedBlock = event.getClickedBlock();

        if (clickedBlock == null) return;
        if (!isEnabled()) return;
        if (player.hasPermission("genac.bypass")) return;
        if (!INTERACTABLE_BLOCKS.contains(clickedBlock.getType())) return;

        Location eyeLocation = player.getEyeLocation();

        double distance = eyeLocation.distance(clickedBlock.getLocation().add(0.5, 0.5, 0.5));
        if (distance > MAX_INTERACT_DISTANCE) return;

        BlockFace clickedFace = event.getBlockFace();
        if (canSeeFace(eyeLocation, clickedBlock, clickedFace)) {
            return;
        }

        double visiblePercentage = calculateVisiblePercentage(eyeLocation, clickedBlock);

        if (visiblePercentage >= MIN_VISIBLE_PERCENTAGE) {
            return;
        }

        event.setCancelled(true);

        String details = String.format("Block: %s at %d,%d,%d | Distance: %.2f | Visible: %.1f%%",
                clickedBlock.getType().name(),
                clickedBlock.getX(), clickedBlock.getY(), clickedBlock.getZ(),
                distance,
                visiblePercentage * 100);

        flag(player, details);
    }

    private boolean canSeeFace(Location eyeLocation, Block block, BlockFace face) {
        Location blockLoc = block.getLocation();
        double[][] faceOffsets = getFaceOffsets(face);

        int visiblePoints = 0;
        for (double[] offset : faceOffsets) {
            Location checkPoint = blockLoc.clone().add(offset[0], offset[1], offset[2]);
            if (hasLineOfSight(eyeLocation, checkPoint, block)) {
                visiblePoints++;
            }
        }

        return visiblePoints >= 2;
    }

    private double[][] getFaceOffsets(BlockFace face) {
        switch (face) {
            case NORTH:
                return new double[][]{{0.1, 0.1, 0}, {0.9, 0.1, 0}, {0.1, 0.9, 0}, {0.9, 0.9, 0}, {0.5, 0.5, 0}};
            case SOUTH:
                return new double[][]{{0.1, 0.1, 1}, {0.9, 0.1, 1}, {0.1, 0.9, 1}, {0.9, 0.9, 1}, {0.5, 0.5, 1}};
            case EAST:
                return new double[][]{{1, 0.1, 0.1}, {1, 0.9, 0.1}, {1, 0.1, 0.9}, {1, 0.9, 0.9}, {1, 0.5, 0.5}};
            case WEST:
                return new double[][]{{0, 0.1, 0.1}, {0, 0.9, 0.1}, {0, 0.1, 0.9}, {0, 0.9, 0.9}, {0, 0.5, 0.5}};
            case UP:
                return new double[][]{{0.1, 1, 0.1}, {0.9, 1, 0.1}, {0.1, 1, 0.9}, {0.9, 1, 0.9}, {0.5, 1, 0.5}};
            case DOWN:
                return new double[][]{{0.1, 0, 0.1}, {0.9, 0, 0.1}, {0.1, 0, 0.9}, {0.9, 0, 0.9}, {0.5, 0, 0.5}};
            default:
                return new double[][]{{0.5, 0.5, 0.5}};
        }
    }

    private double calculateVisiblePercentage(Location eyeLocation, Block targetBlock) {
        Location blockLoc = targetBlock.getLocation();
        int totalPoints = 0;
        int visiblePoints = 0;

        for (BlockFace face : BlockFace.values()) {
            if (face == BlockFace.SELF) continue;

            double[][] offsets = getFaceOffsets(face);
            for (double[] offset : offsets) {
                Location checkPoint = blockLoc.clone().add(offset[0], offset[1], offset[2]);
                totalPoints++;

                if (hasLineOfSight(eyeLocation, checkPoint, targetBlock)) {
                    visiblePoints++;
                }
            }
        }

        double step = 0.25;
        for (double x = 0.1; x <= 0.9; x += step) {
            for (double y = 0.1; y <= 0.9; y += step) {
                for (double z = 0.1; z <= 0.9; z += step) {
                    Location checkPoint = blockLoc.clone().add(x, y, z);
                    totalPoints++;

                    if (hasLineOfSight(eyeLocation, checkPoint, targetBlock)) {
                        visiblePoints++;
                    }
                }
            }
        }

        return (double) visiblePoints / totalPoints;
    }

    private boolean hasLineOfSight(Location from, Location to, Block targetBlock) {
        if (from.getWorld() != to.getWorld()) return false;

        World world = from.getWorld();
        Vector start = from.toVector();
        Vector end = to.toVector();
        Vector direction = end.clone().subtract(start);
        double distance = direction.length();

        if (distance < 0.01) return true;

        direction.normalize();
        double currentDistance = 0;

        while (currentDistance < distance) {
            currentDistance += STEP;
            if (currentDistance > distance) currentDistance = distance;

            Vector point = start.clone().add(direction.clone().multiply(currentDistance));

            int blockX = (int) Math.floor(point.getX());
            int blockY = (int) Math.floor(point.getY());
            int blockZ = (int) Math.floor(point.getZ());

            if (blockX == targetBlock.getX() && blockY == targetBlock.getY() && blockZ == targetBlock.getZ()) {
                continue;
            }

            Block block = world.getBlockAt(blockX, blockY, blockZ);

            if (!isPassable(block)) {
                double localX = point.getX() - blockX;
                double localY = point.getY() - blockY;
                double localZ = point.getZ() - blockZ;

                boolean nearEdge = localX < 0.1 || localX > 0.9 || localY < 0.1 || localY > 0.9 || localZ < 0.1 || localZ > 0.9;

                if (!nearEdge || !checkAdjacentBlocks(world, blockX, blockY, blockZ, point)) {
                    return false;
                }
            }
        }

        return true;
    }

    private boolean checkAdjacentBlocks(World world, int x, int y, int z, Vector point) {
        double localX = point.getX() - x;
        double localY = point.getY() - y;
        double localZ = point.getZ() - z;

        if (localX < 0.1 && isPassable(world.getBlockAt(x - 1, y, z))) return true;
        if (localX > 0.9 && isPassable(world.getBlockAt(x + 1, y, z))) return true;
        if (localY < 0.1 && isPassable(world.getBlockAt(x, y - 1, z))) return true;
        if (localY > 0.9 && isPassable(world.getBlockAt(x, y + 1, z))) return true;
        if (localZ < 0.1 && isPassable(world.getBlockAt(x, y, z - 1))) return true;
        if (localZ > 0.9 && isPassable(world.getBlockAt(x, y, z + 1))) return true;

        return false;
    }

    private boolean isPassable(Block block) {
        if (block == null) return true;

        Material type = block.getType();
        if (PASSABLE_BLOCKS.contains(type)) return true;

        try {
            if (block.isPassable()) return true;
        } catch (NoSuchMethodError e) {
            if (!type.isSolid()) return true;
        }

        String name = type.name();
        if (name.contains("DOOR") || name.contains("GATE") || name.contains("FENCE_GATE")) {
            return true;
        }

        if (name.contains("SLAB") || name.contains("STAIRS") || name.contains("WALL") || name.contains("FENCE")) {
            return true;
        }

        if (name.contains("GLASS") || name.contains("PANE")) {
            return true;
        }

        return false;
    }

    private void flag(Player player, String details) {
        Alert alert = new Alert(
                player,
                "WallInteract",
                AlertType.MEDIUM,
                getViolationLevel(player),
                "Interacting through wall | " + details
        );
        plugin.getAlertManager().sendAlert(alert);
        increaseViolationLevel(player);
    }

    private static void addInteractable(String name) {
        try {
            Material material = Material.valueOf(name);
            if (material != null) {
                INTERACTABLE_BLOCKS.add(material);
            }
        } catch (IllegalArgumentException ignored) {}
    }

    private static void addPassable(String name) {
        try {
            Material material = Material.valueOf(name);
            if (material != null) {
                PASSABLE_BLOCKS.add(material);
            }
        } catch (IllegalArgumentException ignored) {}
    }

    @Override
    public void clearPlayerData(Player player) {
        resetViolationLevel(player);
    }

    @Override
    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("checks.wallinteract.enabled", true);
    }
}