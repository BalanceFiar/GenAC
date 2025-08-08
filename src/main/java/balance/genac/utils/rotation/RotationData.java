package balance.genac.utils.rotation;

public class RotationData {
    private final float yaw;
    private final float pitch;
    private final long timestamp;
    private final float yawDelta;
    private final float pitchDelta;

    public RotationData(float yaw, float pitch, float yawDelta, float pitchDelta) {
        this.yaw = yaw;
        this.pitch = pitch;
        this.yawDelta = yawDelta;
        this.pitchDelta = pitchDelta;
        this.timestamp = System.currentTimeMillis();
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public float getYawDelta() {
        return yawDelta;
    }

    public float getPitchDelta() {
        return pitchDelta;
    }

    public float getTotalDelta() {
        return yawDelta + pitchDelta;
    }

    public boolean isSignificantRotation() {
        return yawDelta > 1.0f || pitchDelta > 1.0f;
    }
}