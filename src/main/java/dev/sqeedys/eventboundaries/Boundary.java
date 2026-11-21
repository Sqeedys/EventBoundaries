package dev.sqeedys.eventboundaries;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

record Boundary(String id, String dimension, double x, double y, double z, double radius, Double halfHeight,
                Mode mode, long expiresAt, Set<UUID> entries, Set<UUID> exits, Set<UUID> roster) {
    static final int MAX_BOUNDARIES = 64;
    static final int MAX_MEMBERS = 256;
    static final int MAX_DURATION_SECONDS = 86_400;
    static final double MAX_RADIUS = 1_024;
    static final double MAX_HALF_HEIGHT = 1_024;
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    private static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    record Point(String dimension, double x, double y, double z) {}

    enum Mode {
        ENTRY(true, false),
        EXIT(false, true),
        LOCKED(true, true);

        final boolean blocksEntry;
        final boolean blocksExit;

        Mode(boolean blocksEntry, boolean blocksExit) {
            this.blocksEntry = blocksEntry;
            this.blocksExit = blocksExit;
        }
    }

    enum Direction { ENTRY, EXIT }

    Boundary {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Use 1–32 lowercase letters, digits, underscores or hyphens for the ID.");
        }
        if (dimension == null || !DIMENSION.matcher(dimension).matches() || dimension.length() > 128
                || !within(x, 30_000_000) || !within(y, 20_000_000) || !within(z, 30_000_000)
                || !within(radius, MAX_RADIUS) || radius < 1
                || halfHeight != null && (!within(halfHeight, MAX_HALF_HEIGHT) || halfHeight < 0.5)
                || mode == null || expiresAt <= 0) {
            throw new IllegalArgumentException("Invalid boundary geometry, dimension, mode or expiration.");
        }
        entries = checkedMembers(entries);
        exits = checkedMembers(exits);
        roster = checkedMembers(roster);
    }

    private static boolean within(double value, double limit) {
        return Double.isFinite(value) && Math.abs(value) <= limit;
    }

    private static Set<UUID> checkedMembers(Set<UUID> members) {
        if (members == null || members.size() > MAX_MEMBERS || members.stream().anyMatch(uuid -> uuid == null)) {
            throw new IllegalArgumentException("A membership list may hold at most " + MAX_MEMBERS + " UUIDs.");
        }
        return Set.copyOf(members);
    }

    boolean active(long now) {
        return now < expiresAt;
    }

    boolean contains(Point point) {
        return dimension.equals(point.dimension)
                && (halfHeight == null || Math.abs(point.y - y) <= halfHeight)
                && distanceSquared(point.x - x, point.z - z) <= radius * radius;
    }

    private static double distanceSquared(double x, double z) {
        return x * x + z * z;
    }

    boolean blocksEntry(UUID subject) {
        return mode.blocksEntry && !entries.contains(subject) && !roster.contains(subject);
    }

    boolean blocksExit(UUID subject) {
        return mode.blocksExit && !exits.contains(subject);
    }

    Direction denial(UUID subject, Point from, Point to, boolean continuous) {
        boolean outsideEntry = blocksEntry(subject);
        boolean insideExit = blocksExit(subject);
        if (!outsideEntry && !insideExit) {
            return null;
        }
        boolean wasInside = contains(from);
        boolean isInside = contains(to);
        if (wasInside != isInside) {
            return isInside ? (outsideEntry ? Direction.ENTRY : null) : (insideExit ? Direction.EXIT : null);
        }
        if (!wasInside && continuous && crossesInterior(from, to)) {
            return outsideEntry ? Direction.ENTRY : Direction.EXIT;
        }
        return null;
    }

    private boolean crossesInterior(Point from, Point to) {
        if (!dimension.equals(from.dimension) || !dimension.equals(to.dimension)) {
            return false;
        }
        double start = 0;
        double end = 1;
        if (halfHeight != null) {
            double dy = to.y - from.y;
            if (dy == 0) {
                if (Math.abs(from.y - y) > halfHeight) {
                    return false;
                }
            } else {
                double bottom = (y - halfHeight - from.y) / dy;
                double top = (y + halfHeight - from.y) / dy;
                start = Math.max(start, Math.min(bottom, top));
                end = Math.min(end, Math.max(bottom, top));
                if (start > end) {
                    return false;
                }
            }
        }
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double lengthSquared = distanceSquared(dx, dz);
        double nearest = lengthSquared == 0 ? start : ((x - from.x) * dx + (z - from.z) * dz) / lengthSquared;
        double t = Math.clamp(nearest, start, end);
        return distanceSquared(from.x + t * dx - x, from.z + t * dz - z) <= radius * radius;
    }

    Boundary withRoster(Set<UUID> newRoster) {
        return new Boundary(id, dimension, x, y, z, radius, halfHeight, mode, expiresAt, entries, exits, newRoster);
    }

    Boundary withPass(Direction direction, Set<UUID> members) {
        return new Boundary(id, dimension, x, y, z, radius, halfHeight, mode, expiresAt,
                direction == Direction.ENTRY ? members : entries,
                direction == Direction.EXIT ? members : exits, roster);
    }
}
