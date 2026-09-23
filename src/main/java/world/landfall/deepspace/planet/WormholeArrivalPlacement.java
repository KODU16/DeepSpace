package world.landfall.deepspace.planet;

/** Computes a deterministic wormhole exit that clears the destination model with the whole payload. */
public final class WormholeArrivalPlacement {
    public static final double DEFAULT_CLEARANCE = 32.0D;

    private WormholeArrivalPlacement() {
    }

    public static Point placeOutside(Bounds wormhole, Bounds relativePayload, Point galaxyCenter, double clearance) {
        Point wormholeCenter = wormhole.center();
        Point centeredPayload = new Point(
                wormholeCenter.x - relativePayload.center().x,
                wormholeCenter.y - relativePayload.center().y,
                wormholeCenter.z - relativePayload.center().z
        );
        return pushOutsideAlongDirection(
                wormhole,
                relativePayload,
                centeredPayload,
                new Point(wormholeCenter.x - galaxyCenter.x, 0.0D, wormholeCenter.z - galaxyCenter.z),
                clearance
        );
    }

    /** Pushes a desired payload pose beyond one horizontal model face while preserving its tangent and height. */
    public static Point pushOutsideAlongDirection(
            Bounds body,
            Bounds relativePayload,
            Point desiredPosition,
            Point outwardDirection,
            double clearance
    ) {
        if (clearance <= 0.0D || !Double.isFinite(clearance)) {
            throw new IllegalArgumentException("Model clearance must be finite and positive");
        }
        double targetX = desiredPosition.x;
        double targetY = desiredPosition.y;
        double targetZ = desiredPosition.z;

        // Clear the dominant outward ecliptic face so even an elongated model cannot contain the payload.
        if (Math.abs(outwardDirection.x) >= Math.abs(outwardDirection.z)) {
            targetX = outwardDirection.x >= 0.0D
                    ? body.maxX + clearance - relativePayload.minX
                    : body.minX - clearance - relativePayload.maxX;
        } else {
            targetZ = outwardDirection.z >= 0.0D
                    ? body.maxZ + clearance - relativePayload.minZ
                    : body.minZ - clearance - relativePayload.maxZ;
        }
        return new Point(targetX, targetY, targetZ);
    }

    public record Point(double x, double y, double z) {
    }

    public record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public Bounds {
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("Invalid bounds");
            }
        }

        public Point center() {
            return new Point((minX + maxX) * 0.5D, (minY + maxY) * 0.5D, (minZ + maxZ) * 0.5D);
        }

        public Bounds move(Point offset) {
            return new Bounds(
                    minX + offset.x, minY + offset.y, minZ + offset.z,
                    maxX + offset.x, maxY + offset.y, maxZ + offset.z
            );
        }

        public boolean intersectsOrTouches(Bounds other) {
            return maxX >= other.minX && minX <= other.maxX
                    && maxY >= other.minY && minY <= other.maxY
                    && maxZ >= other.minZ && minZ <= other.maxZ;
        }

        public double axisGap(Bounds other) {
            double xGap = Math.max(minX - other.maxX, other.minX - maxX);
            double yGap = Math.max(minY - other.maxY, other.minY - maxY);
            double zGap = Math.max(minZ - other.maxZ, other.minZ - maxZ);
            return Math.max(xGap, Math.max(yGap, zGap));
        }
    }
}
