package dev.sqeedys.eventboundaries;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;

public final class BoundaryTest {
    private static final String WORLD = "minecraft:overworld";
    private static final UUID VISITOR = UUID.fromString("f0000000-0000-4000-8000-000000000001");
    private static final UUID MEMBER = UUID.fromString("f0000000-0000-4000-8000-000000000002");
    private static final Boundary.Point CENTER = point(0, 0, 0);

    public static void main(String[] args) throws IOException {
        geometry();
        permissions();
        persistence();
        System.out.println("BoundaryTest passed geometry, directional membership, and persistence checks.");
    }

    private static void geometry() {
        Boundary zone = zone("room", 5.0, Boundary.Mode.LOCKED, Set.of(), Set.of(), Set.of());
        check(zone.contains(point(10, 5, 0)), "inclusive radial and vertical boundaries");
        check(!zone.contains(point(10.01, 5, 0)), "outside radius");
        check(!zone.contains(point(0, 5.01, 0)), "above room");
        check(!zone.contains(new Boundary.Point("minecraft:the_nether", 0, 0, 0)), "different dimension");
        check(zone.denial(VISITOR, point(-30, 0, 0), point(30, 0, 0), true) == Boundary.Direction.ENTRY,
                "outside-to-outside traversal across cylinder");
        check(zone.denial(VISITOR, point(-30, 0, 0), point(30, 0, 0), false) == null,
                "outside-to-outside teleport skips interior");
        check(zone.denial(VISITOR, point(0, -10, 0), point(0, 10, 0), true) == Boundary.Direction.ENTRY,
                "outside-to-outside vertical traversal");
        check(zone.denial(VISITOR, point(-30, 10, 0), point(30, 10, 0), true) == null,
                "horizontal traversal above finite height");
        check(zone.denial(VISITOR, point(-30, 9, 0), point(30, 2, 0), true) == Boundary.Direction.ENTRY,
                "enters finite vertical interval within radial sweep");
        check(zone.denial(VISITOR, point(-30, 20, 0), point(30, 1, 0), true) == null,
                "enters finite vertical interval beyond radial sweep");
        check(zone("event", null, Boundary.Mode.ENTRY, Set.of(), Set.of(), Set.of()).contains(point(1, 200, 1)),
                "full-height boundary");
    }

    private static void permissions() {
        Boundary.Point out = point(0, 0, 11);
        Boundary zone = zone("arena", null, Boundary.Mode.LOCKED, Set.of(), Set.of(), Set.of(MEMBER));
        check(zone.denial(VISITOR, out, CENTER, true) == Boundary.Direction.ENTRY, "visitor denied");
        check(zone.denial(MEMBER, out, CENTER, true) == null, "captured occupant can reenter");
        check(zone.denial(MEMBER, CENTER, out, true) == Boundary.Direction.EXIT, "roster leaves exit restriction intact");
        check(zone.withPass(Boundary.Direction.EXIT, Set.of(MEMBER)).denial(MEMBER, CENTER, out, true) == null, "exit pass");
        check(zone.withPass(Boundary.Direction.ENTRY, Set.of(VISITOR)).denial(VISITOR, out, CENTER, true) == null,
                "explicit entry pass");
        check(zone.withRoster(Set.of()).denial(MEMBER, out, CENTER, true) == Boundary.Direction.ENTRY, "refreeze replaces");
        check(zone("out", null, Boundary.Mode.EXIT, Set.of(), Set.of(), Set.of())
                .denial(VISITOR, out, CENTER, true) == null, "hold-in permits entry");
        check(zone("in", null, Boundary.Mode.ENTRY, Set.of(), Set.of(), Set.of())
                .denial(VISITOR, CENTER, out, true) == null, "keep-out permits exit");
    }

    private static void persistence() throws IOException {
        Path directory = Files.createTempDirectory("event-boundaries-test-");
        Path file = directory.resolve("boundaries.json");
        long before = 1_000;
        try {
            var store = new BoundaryStore(file, before);
            var entry = zone("first", null, Boundary.Mode.LOCKED, Set.of(VISITOR), Set.of(MEMBER), Set.of(MEMBER));
            store.create(entry, before);
            check(new BoundaryStore(file, before).require("first", before).equals(entry), "loaded zone, passes and roster");
            check(new BoundaryStore(file, 2_000).all().isEmpty(), "expired during downtime");
            String complete = Files.readString(file);
            rejects(file, complete.replace("\"x\": 0.0,", ""));
            rejects(file, complete.replace("\"halfHeight\": null,", ""));
            rejects(file, complete.replace("\"z\": 0.0", "\"z\": \"0.0\""));
            rejects(file, complete.replace("\"expiresAt\": 2000", "\"expiresAt\": 2000.5"));
            Files.writeString(file, complete);
            store.release("first", before);
            check(new BoundaryStore(file, before).all().isEmpty(), "manual release persisted");
            store.create(entry, before);
            store.discardExpired(2_000);
            check(store.all().isEmpty(), "expiry removes active state in memory");
            store.create(entry, before);
            check(store.all().size() == 1, "reuse expired ID");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void rejects(Path path, String json) throws IOException {
        Files.writeString(path, json);
        try {
            new BoundaryStore(path, 1_000);
            throw new AssertionError("Accepted incomplete or malformed state.");
        } catch (IOException expected) {
            check(Files.readString(path).equals(json), "bad file left untouched");
        }
    }

    private static Boundary zone(String id, Double height, Boundary.Mode mode, Set<UUID> entries,
                                 Set<UUID> exits, Set<UUID> roster) {
        return new Boundary(id, WORLD, 0, 0, 0, 10, height, mode, 2_000, entries, exits, roster);
    }

    private static Boundary.Point point(double x, double y, double z) {
        return new Boundary.Point(WORLD, x, y, z);
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
