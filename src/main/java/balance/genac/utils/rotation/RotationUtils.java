package balance.genac.utils.rotation;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

public class RotationUtils {


    public static float normalizeYaw(float yaw) {
        while (yaw > 180.0f) {
            yaw -= 360.0f;
        }
        while (yaw < -180.0f) {
            yaw += 360.0f;
        }
        return yaw;
    }


    public static float normalizePitch(float pitch) {
        return Math.max(-90.0f, Math.min(90.0f, pitch));
    }


    public static float getYawDelta(float yaw1, float yaw2) {
        float delta = Math.abs(normalizeYaw(yaw1) - normalizeYaw(yaw2));
        return Math.min(delta, 360.0f - delta);
    }


    public static float getPitchDelta(float pitch1, float pitch2) {
        return Math.abs(normalizePitch(pitch1) - normalizePitch(pitch2));
    }


    public static float[] getRotationToLocation(Location from, Location to) {
        Vector direction = to.toVector().subtract(from.toVector()).normalize();

        float yaw = (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
        float pitch = (float) Math.toDegrees(Math.asin(-direction.getY()));

        return new float[]{normalizeYaw(yaw), normalizePitch(pitch)};
    }


    public static boolean isInFOV(Player player, Location target, float fov) {
        Vector playerDirection = player.getLocation().getDirection();
        Vector targetDirection = target.toVector().subtract(player.getLocation().toVector()).normalize();

        double angle = Math.toDegrees(Math.acos(playerDirection.dot(targetDirection)));
        return angle <= fov / 2.0;
    }


    public static float getGCD(float sensitivity) {
        return sensitivity / 142.0f;
    }


    public static boolean isValidGCD(float rotation, float gcd) {
        return Math.abs(rotation % gcd) < 0.001f;
    }
}