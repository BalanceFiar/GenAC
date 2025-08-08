package balance.genac.utils.rotation;

import java.util.ArrayList;
import java.util.List;

public class RotationAnalyzer {

    public static RotationPattern analyzePattern(List<RotationData> rotations) {
        if (rotations.size() < 3) {
            return RotationPattern.INSUFFICIENT_DATA;
        }

        float avgYawDelta = 0;
        float avgPitchDelta = 0;
        float variance = 0;
        int significantRotations = 0;

        for (RotationData rotation : rotations) {
            avgYawDelta += rotation.getYawDelta();
            avgPitchDelta += rotation.getPitchDelta();
            if (rotation.isSignificantRotation()) {
                significantRotations++;
            }
        }

        avgYawDelta /= rotations.size();
        avgPitchDelta /= rotations.size();

        for (RotationData rotation : rotations) {
            float deviation = rotation.getTotalDelta() - (avgYawDelta + avgPitchDelta);
            variance += deviation * deviation;
        }
        variance /= rotations.size();

        if (variance < 0.5f && avgYawDelta > 5.0f) {
            return RotationPattern.SMOOTH_AIM;
        } else if (hasSnapPattern(rotations)) {
            return RotationPattern.SNAP_AIM;
        } else if (hasRobotPattern(rotations)) {
            return RotationPattern.ROBOTIC;
        } else if (significantRotations == 0) {
            return RotationPattern.NO_ROTATION;
        }

        return RotationPattern.LEGITIMATE;
    }

    private static boolean hasSnapPattern(List<RotationData> rotations) {
        int snapCount = 0;
        for (RotationData rotation : rotations) {
            if (rotation.getYawDelta() > 45.0f || rotation.getPitchDelta() > 30.0f) {
                snapCount++;
            }
        }
        return snapCount >= rotations.size() * 0.6;
    }

    private static boolean hasRobotPattern(List<RotationData> rotations) {
        float lastYawDelta = -1;
        float lastPitchDelta = -1;
        int identicalCount = 0;

        for (RotationData rotation : rotations) {
            if (lastYawDelta != -1 && lastPitchDelta != -1) {
                if (Math.abs(rotation.getYawDelta() - lastYawDelta) < 0.1f &&
                        Math.abs(rotation.getPitchDelta() - lastPitchDelta) < 0.1f) {
                    identicalCount++;
                }
            }
            lastYawDelta = rotation.getYawDelta();
            lastPitchDelta = rotation.getPitchDelta();
        }

        return identicalCount >= rotations.size() * 0.7;
    }

    public static boolean hasInhumanPrecision(List<RotationData> rotations) {
        int preciseCount = 0;
        for (RotationData rotation : rotations) {
            if (isExtremePreciseRotation(rotation.getYawDelta()) || isExtremePreciseRotation(rotation.getPitchDelta())) {
                preciseCount++;
            }
        }
        return preciseCount >= rotations.size() * 0.8;
    }

    private static boolean isExtremePreciseRotation(float delta) {
        return delta > 1.0f && delta < 20.0f && 
               ((delta % 1.0f) < 0.001f ||
                (delta % 0.5f) < 0.001f && delta > 3.0f);
    }

    private static boolean isPreciseRotation(float delta) {
        return isExtremePreciseRotation(delta);
    }

    public static boolean hasIdenticalRotations(List<RotationData> significantRotations) {
        if (significantRotations.size() < 6) return false;

        int identicalCount = 0;
        RotationData previousRotation = null;

        for (RotationData rotation : significantRotations) {
            if (previousRotation != null) {
                if (Math.abs(rotation.getYawDelta() - previousRotation.getYawDelta()) < 0.01f &&
                        Math.abs(rotation.getPitchDelta() - previousRotation.getPitchDelta()) < 0.01f &&
                        Math.abs(rotation.getTotalDelta() - previousRotation.getTotalDelta()) < 0.02f &&
                        rotation.getTotalDelta() > 2.0f) {
                    identicalCount++;
                }
            }
            previousRotation = rotation;
        }

        return identicalCount >= significantRotations.size() * 0.9;
    }

    public enum RotationPattern {
        SMOOTH_AIM,
        SNAP_AIM,
        ROBOTIC,
        NO_ROTATION,
        LEGITIMATE,
        INSUFFICIENT_DATA
    }
}