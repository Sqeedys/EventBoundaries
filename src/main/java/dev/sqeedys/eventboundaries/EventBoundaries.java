package dev.sqeedys.eventboundaries;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class EventBoundaries implements DedicatedServerModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Event Boundaries");
    private static RuntimeState runtime;

    private record Denial(Boundary boundary, Boundary.Direction direction) {}
    private static final int INGRESS_TICKS = 20;
    private static final int HISTORY_POINTS = 8;

    private static final class Track {
        final ServerPlayer player;
        Boundary.Point accepted;
        final ArrayDeque<Boundary.Point> history = new ArrayDeque<>(HISTORY_POINTS);
        Boundary.Point respawnFrom;
        int ingressTicks;
        long nextMessage;
        long nextCorrectionWarning;

        Track(ServerPlayer player) {
            this.player = player;
            accepted = point(player);
        }

        void update(Boundary.Point next) {
            if (!accepted.equals(next)) {
                if (history.size() == HISTORY_POINTS) {
                    history.removeLast();
                }
                history.addFirst(accepted);
                accepted = next;
            }
        }
    }

    private static final class RuntimeState {
        final BoundaryStore store;
        final Map<UUID, Track> tracks = new HashMap<>();
        ServerPlayer approvedTeleport;

        RuntimeState(BoundaryStore store) {
            this.store = store;
        }
    }

    @Override
    public void onInitializeServer() {
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> BoundaryCommands.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            var stateFile = server.getWorldPath(LevelResource.ROOT).resolve("eventboundaries/state.json");
            try {
                runtime = new RuntimeState(new BoundaryStore(stateFile, System.currentTimeMillis()));
            } catch (IOException ex) {
                throw new UncheckedIOException("Cannot open the event boundary state for this save.", ex);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> runtime = null);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (hasBoundaries()) {
                Track joined = track(handler.player);
                joined.respawnFrom = null;
                joined.ingressTicks = INGRESS_TICKS;
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (runtime != null) {
                Track previous = runtime.tracks.get(handler.player.getUUID());
                if (previous != null && previous.player == handler.player) {
                    runtime.tracks.remove(handler.player.getUUID());
                }
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(EventBoundaries::tick);
    }

    static BoundaryStore store() {
        if (runtime == null) {
            throw new IllegalArgumentException("Event boundaries are still starting.");
        }
        return runtime.store;
    }

    public static boolean hasBoundaries() {
        return runtime != null && !runtime.store.all().isEmpty();
    }

    static void seedTracks(MinecraftServer server) {
        server.getPlayerList().getPlayers().forEach(EventBoundaries::track);
    }

    private static Track track(ServerPlayer player) {
        UUID id = player.getUUID();
        Track previous = runtime.tracks.get(id);
        if (previous != null && previous.player == player) {
            return previous;
        }
        Track replacement = new Track(player);
        if (previous != null) {
            replacement.respawnFrom = previous.accepted;
            replacement.ingressTicks = INGRESS_TICKS;
        }
        runtime.tracks.put(id, replacement);
        return replacement;
    }

    static Boundary.Point point(ServerPlayer player) {
        return point(player.level(), player.position());
    }

    private static Boundary.Point point(ServerLevel level, Vec3 position) {
        return new Boundary.Point(level.dimension().identifier().toString(), position.x, position.y, position.z);
    }

    private static Denial denial(ServerPlayer player, Boundary.Point from, Boundary.Point destination, boolean continuous) {
        if (from.equals(destination)) {
            return null;
        }
        long now = System.currentTimeMillis();
        for (Boundary boundary : runtime.store.all()) {
            if (boundary.active(now)) {
                var direction = boundary.denial(player.getUUID(), from, destination, continuous);
                if (direction != null) {
                    return new Denial(boundary, direction);
                }
            }
        }
        return null;
    }

    private static boolean rejected(ServerPlayer player, Boundary.Point to, boolean continuous) {
        if (!hasBoundaries() || !player.isAlive() || runtime.approvedTeleport == player) {
            return false;
        }
        Boundary.Point from = point(player);
        Track previous = track(player);
        if (!to.equals(from) && previous.ingressTicks > 0 && !unapprovedIngress(previous, from).isEmpty()) {
            return true;
        }
        Denial denied = denial(player, previous.accepted, from, true);
        if (denied != null) {
            correct(previous, denied);
            return true;
        }
        denied = denial(player, from, to, continuous);
        if (denied == null) {
            return false;
        }
        notify(player, denied);
        return true;
    }

    public static boolean rejectWalking(ServerPlayer player, Vec3 destination) {
        return rejected(player, point(player.level(), destination), true);
    }

    public static boolean rejectVehicleMovement(Entity vehicle, Vec3 destination) {
        if (!hasBoundaries()) {
            return false;
        }
        Vec3 offset = destination.subtract(vehicle.position());
        boolean denied = false;
        for (Entity passenger : vehicle.getIndirectPassengers()) {
            if (passenger instanceof ServerPlayer player) {
                denied |= rejectWalking(player, player.position().add(offset));
            }
        }
        return denied;
    }

    public static boolean rejectTeleport(ServerPlayer player, TeleportTransition transition) {
        return rejectTeleport(player, transition.newLevel(), PositionMoveRotation.of(transition), transition.relatives());
    }

    public static boolean rejectTeleport(ServerPlayer player, ServerLevel level,
                                         PositionMoveRotation change, Set<Relative> relatives) {
        if (!hasBoundaries()) {
            return false;
        }
        var destination = PositionMoveRotation.calculateAbsolute(PositionMoveRotation.of(player), change, relatives);
        return rejected(player, point(level, destination.position()), false);
    }

    public static boolean rejectCamera(ServerPlayer player, Entity camera) {
        return hasBoundaries() && camera != null && camera != player && camera.level() instanceof ServerLevel level
                && rejected(player, point(level, camera.position()), false);
    }

    public static boolean rejectCreditsExit(ServerPlayer player) {
        if (!hasBoundaries() || !player.isAlive()) {
            return false;
        }
        Boundary.Point current = point(player);
        Boundary.Point last = track(player).accepted;
        long now = System.currentTimeMillis();
        for (Boundary boundary : runtime.store.all()) {
            if (boundary.active(now) && (boundary.contains(current) || boundary.contains(last))
                    && boundary.blocksExit(player.getUUID())) {
                notify(player, new Denial(boundary, Boundary.Direction.EXIT));
                return true;
            }
        }
        return false;
    }

    public static boolean checkConnectionTeleport(ServerPlayer player) {
        if (!hasBoundaries() || runtime.approvedTeleport == player || !player.isAlive() || player.isRemoved()) {
            return false;
        }
        Track known = runtime.tracks.get(player.getUUID());
        // A new player instance is the vanilla login or respawn path.
        return known != null && known.player == player;
    }

    public static void recordTeleport(ServerPlayer player) {
        if (hasBoundaries() && player.isAlive() && !player.isRemoved()) {
            track(player).update(point(player));
        }
    }

    public static void withApprovedTeleport(ServerPlayer player, Runnable action) {
        if (runtime == null) {
            action.run();
            return;
        }
        ServerPlayer previous = runtime.approvedTeleport;
        runtime.approvedTeleport = player;
        try {
            action.run();
        } finally {
            runtime.approvedTeleport = previous;
        }
    }

    public static void resyncWalking(ServerPlayer player) {
        if (!hasBoundaries()) {
            return;
        }
        withApprovedTeleport(player, () -> player.connection.teleport(
                new PositionMoveRotation(player.position(), player.getDeltaMovement(), player.getYRot(), player.getXRot()),
                Set.of()));
    }

    private static void tick(MinecraftServer server) {
        if (runtime == null) {
            return;
        }
        runtime.store.discardExpired(System.currentTimeMillis());
        if (!hasBoundaries()) {
            runtime.tracks.clear();
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Track track = track(player);
            if (!player.isAlive()) {
                continue;
            }
            Boundary.Point current = point(player);
            if (track.ingressTicks > 0 && checkIngress(track, current)) {
                continue;
            }
            Denial denied = denial(player, track.accepted, current, true);
            if (denied == null) {
                track.update(current);
            } else {
                correct(track, denied);
            }
        }
    }

    private static void correct(Track track, Denial denied) {
        ServerPlayer player = track.player;
        Boundary.Point current = point(player);
        Boundary.Point target = track.accepted;
        notify(player, denied);
        if (canCorrect(player, track.accepted, current, target)) {
            relocate(player, target);
            return;
        }
        for (Boundary.Point earlier : track.history) {
            if (canCorrect(player, track.accepted, current, earlier)) {
                relocate(player, earlier);
                track.accepted = point(player);
                return;
            }
        }
        long now = System.currentTimeMillis();
        if (now >= track.nextCorrectionWarning) {
            track.nextCorrectionWarning = now + 5_000;
            warnNoSafePosition(player, denied.boundary);
        }
        track.accepted = current;
        track.history.clear();
    }

    private static boolean canCorrect(ServerPlayer player, Boundary.Point accepted, Boundary.Point current,
                                      Boundary.Point target) {
        return current.dimension().equals(target.dimension()) && denial(player, accepted, target, true) == null
                && safeCorrection(player, target);
    }

    private static List<Boundary> unapprovedIngress(Track track, Boundary.Point current) {
        long now = System.currentTimeMillis();
        return runtime.store.all().stream()
                .filter(b -> b.active(now) && b.blocksEntry(track.player.getUUID()) && b.contains(current))
                .filter(b -> track.respawnFrom == null || !b.contains(track.respawnFrom))
                .toList();
    }

    private static boolean checkIngress(Track track, Boundary.Point current) {
        List<Boundary> blocked = unapprovedIngress(track, current);
        if (blocked.isEmpty()) {
            track.ingressTicks = 0;
            return false;
        }
        Boundary.Point destination = track.ingressTicks % 5 == 0 || track.ingressTicks == 1
                ? ingressDestination(track.player, current, blocked) : null;
        if (destination != null) {
            notify(track.player, new Denial(blocked.getFirst(), Boundary.Direction.ENTRY));
            relocate(track.player, destination);
            track.accepted = point(track.player);
            track.history.clear();
            track.ingressTicks = 0;
        } else if (--track.ingressTicks == 0) {
            warnNoSafePosition(track.player, blocked.getFirst());
            track.accepted = current;
            track.history.clear();
        }
        return true;
    }

    private static Boundary.Point ingressDestination(ServerPlayer player, Boundary.Point current, List<Boundary> blocked) {
        // The current column can contain a floor immediately above or below a finite-height lock.
        Boundary.Point local = findStanding(player, current, blocked, current.x(), current.z());
        if (local != null) {
            return local;
        }
        int sampled = 0;
        for (Boundary boundary : blocked) {
            double angle = Math.atan2(current.z() - boundary.z(), current.x() - boundary.x());
            for (int step = 0; step < 16; step++) {
                if (sampled++ >= 32) {
                    return null;
                }
                double sample = angle + step * Math.PI / 8;
                double x = boundary.x() + (boundary.radius() + 1.5) * Math.cos(sample);
                double z = boundary.z() + (boundary.radius() + 1.5) * Math.sin(sample);
                Boundary.Point target = findStanding(player, current, blocked, x, z);
                if (target != null) {
                    return target;
                }
            }
        }
        return null;
    }

    private static Boundary.Point findStanding(ServerPlayer player, Boundary.Point current, List<Boundary> blocked,
                                                double x, double z) {
        x = Mth.floor(x) + 0.5;
        z = Mth.floor(z) + 0.5;
        ServerLevel level = player.level();
        int base = Mth.floor(current.y());
        if (level.getChunkSource().getChunkNow(Mth.floor(x) >> 4, Mth.floor(z) >> 4) == null) {
            return null;
        }
        for (int step = 0; step <= 16; step++) {
            int y = base + (step % 2 == 0 ? -(step / 2) : (step + 1) / 2);
            Boundary.Point target = new Boundary.Point(current.dimension(), x, y, z);
            if (approvedIngressDestination(player, current, target, blocked) && safeStanding(player, target)) {
                return target;
            }
        }
        return null;
    }

    private static boolean approvedIngressDestination(ServerPlayer player, Boundary.Point current,
                                                       Boundary.Point target, List<Boundary> blocked) {
        long now = System.currentTimeMillis();
        UUID uuid = player.getUUID();
        for (Boundary zone : runtime.store.all()) {
            if (!zone.active(now)) {
                continue;
            }
            boolean wasInside = zone.contains(current);
            boolean willBeInside = zone.contains(target);
            if (willBeInside && zone.blocksEntry(uuid) && (!wasInside || blocked.contains(zone))
                    || wasInside && !willBeInside && zone.blocksExit(uuid) && !blocked.contains(zone)) {
                return false;
            }
        }
        return true;
    }

    private static boolean safeStanding(ServerPlayer player, Boundary.Point target) {
        ServerLevel level = player.level();
        BlockPos feet = BlockPos.containing(target.x(), target.y(), target.z());
        if (level.isOutsideBuildHeight(feet.below()) || level.isOutsideBuildHeight(feet.above())
                || !safeCorrection(player, target)) {
            return false;
        }
        var floor = level.getBlockState(feet.below());
        if (!floor.isFaceSturdy(level, feet.below(), Direction.UP) || floor.is(Blocks.MAGMA_BLOCK)
                || floor.is(Blocks.CACTUS) || floor.is(Blocks.CAMPFIRE) || floor.is(Blocks.SOUL_CAMPFIRE)) {
            return false;
        }
        return level.isEmptyBlock(feet) && level.isEmptyBlock(feet.above());
    }

    private static void relocate(ServerPlayer player, Boundary.Point target) {
        withApprovedTeleport(player, () -> {
            if (player.getCamera() != player) {
                player.setCamera(null);
            }
            player.stopRiding();
            player.connection.teleport(target.x(), target.y(), target.z(), player.getYRot(), player.getXRot());
            player.connection.resetPosition();
        });
    }

    private static void warnNoSafePosition(ServerPlayer player, Boundary zone) {
        String message = "Event boundary " + zone.id() + " could not safely reposition " + player.getScoreboardName()
                + "; their current position was accepted.";
        LOGGER.warn(message);
        player.sendSystemMessage(Component.literal(message));
        for (ServerPlayer staff : player.level().getServer().getPlayerList().getPlayers()) {
            if (staff != player && BoundaryCommands.canView(staff.createCommandSourceStack())) {
                staff.sendSystemMessage(Component.literal(message));
            }
        }
    }

    private static boolean safeCorrection(ServerPlayer player, Boundary.Point target) {
        ServerLevel level = player.level();
        AABB box = player.getBoundingBox().move(target.x() - player.getX(), target.y() - player.getY(),
                target.z() - player.getZ());
        if (!level.getWorldBorder().isWithinBounds(box)) {
            return false;
        }
        // Check the collision query's neighboring cells before asking the level for block shapes.
        for (int chunkX = (Mth.floor(box.minX - 1) >> 4); chunkX <= (Mth.floor(box.maxX + 1) >> 4); chunkX++) {
            for (int chunkZ = (Mth.floor(box.minZ - 1) >> 4); chunkZ <= (Mth.floor(box.maxZ + 1) >> 4); chunkZ++) {
                if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
                    return false;
                }
            }
        }
        return level.noCollision(player, box);
    }

    private static void notify(ServerPlayer player, Denial denied) {
        Track track = track(player);
        long now = System.currentTimeMillis();
        if (now >= track.nextMessage) {
            track.nextMessage = now + 1_500;
            String action = denied.direction == Boundary.Direction.ENTRY ? "Entry" : "Exit";
            player.sendOverlayMessage(Component.literal(action + " is closed for event boundary " + denied.boundary.id() + "."));
        }
    }
}
