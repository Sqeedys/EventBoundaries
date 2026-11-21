package dev.sqeedys.eventboundaries;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.permission.v1.PermissionContextOwner;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.PermissionLevel;

import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

final class BoundaryCommands {
    private static final Identifier VIEW = Identifier.fromNamespaceAndPath("eventboundaries", "command/view");
    private static final Identifier MANAGE = Identifier.fromNamespaceAndPath("eventboundaries", "command/manage");
    private static final int PAGE_SIZE = 10;

    private BoundaryCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("eventboundary").requires(BoundaryCommands::canView);
        root.then(Commands.literal("list").executes(c -> run(c, () -> list(c.getSource(), 1)))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(c -> run(c, () -> list(c.getSource(), IntegerArgumentType.getInteger(c, "page"))))));
        root.then(Commands.literal("info").then(activeId().executes(c -> run(c, () -> info(c)))));
        root.then(Commands.literal("members").then(activeId()
                .then(memberList("roster")).then(memberList("entry")).then(memberList("exit"))));

        var createId = Commands.argument("id", StringArgumentType.word());
        for (Boundary.Mode mode : Boundary.Mode.values()) {
            var duration = Commands.argument("seconds", IntegerArgumentType.integer(1, Boundary.MAX_DURATION_SECONDS))
                    .executes(c -> run(c, () -> create(c, mode, null)))
                    .then(Commands.literal("height")
                            .then(Commands.argument("half_height", DoubleArgumentType.doubleArg(0.5, Boundary.MAX_HALF_HEIGHT))
                                    .executes(c -> run(c, () -> create(c, mode, DoubleArgumentType.getDouble(c, "half_height"))))));
            createId.then(Commands.literal(lower(mode))
                    .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1, Boundary.MAX_RADIUS)).then(duration)));
        }
        root.then(manage("create").then(createId));
        root.then(manage("release").then(activeId().executes(c -> run(c, () -> {
            String id = id(c);
            EventBoundaries.store().release(id, System.currentTimeMillis());
            say(c.getSource(), "Released event boundary " + id + ".");
            return 1;
        }))));
        root.then(manage("freeze").then(activeId().executes(c -> run(c, () -> freeze(c)))));
        root.then(manage("pass").then(activeId()
                .then(passOperation("grant", true)).then(passOperation("revoke", false))));
        dispatcher.register(root);
    }

    static boolean canView(CommandSourceStack source) {
        return permitted(source, VIEW) || permitted(source, MANAGE);
    }

    private static boolean permitted(CommandSourceStack source, Identifier node) {
        return ((PermissionContextOwner) source).checkPermission(node, PermissionLevel.OWNERS);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> manage(String name) {
        return Commands.literal(name).requires(source -> permitted(source, MANAGE));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> activeId() {
        return Commands.argument("id", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(EventBoundaries.store().active(System.currentTimeMillis())
                        .stream().map(Boundary::id), b));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> memberList(String name) {
        return Commands.literal(name)
                .executes(c -> run(c, () -> members(c, name, 1)))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(c -> run(c, () -> members(c, name, IntegerArgumentType.getInteger(c, "page")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> passOperation(String name, boolean grant) {
        var operation = Commands.literal(name);
        for (Boundary.Direction direction : Boundary.Direction.values()) {
            operation.then(Commands.literal(lower(direction))
                    .then(Commands.literal("uuid").then(Commands.argument("uuid", UuidArgument.uuid())
                            .executes(c -> run(c, () -> pass(c, direction, grant, UuidArgument.getUuid(c, "uuid"))))))
                    .then(Commands.literal("player").then(Commands.argument("name", StringArgumentType.word())
                            .suggests((c, b) -> SharedSuggestionProvider.suggest(c.getSource().getOnlinePlayerNames(), b))
                            .executes(c -> run(c, () -> {
                                String nameArgument = StringArgumentType.getString(c, "name");
                                var player = c.getSource().getServer().getPlayerList().getPlayerByName(nameArgument);
                                if (player == null) {
                                    throw new IllegalArgumentException("No online player named " + nameArgument + ".");
                                }
                                return pass(c, direction, grant, player.getUUID());
                            })))));
        }
        return operation;
    }

    private static int create(CommandContext<CommandSourceStack> context, Boundary.Mode mode, Double halfHeight) throws IOException {
        CommandSourceStack source = context.getSource();
        var pos = source.getPosition();
        long now = System.currentTimeMillis();
        long expiresAt = now + 1_000L * IntegerArgumentType.getInteger(context, "seconds");
        var boundary = new Boundary(id(context), source.getLevel().dimension().identifier().toString(),
                pos.x, pos.y, pos.z, DoubleArgumentType.getDouble(context, "radius"), halfHeight,
                mode, expiresAt, Set.of(), Set.of(), Set.of());
        EventBoundaries.store().create(boundary, now);
        EventBoundaries.seedTracks(source.getServer());
        say(source, "Created event boundary " + boundary.id() + " (" + lower(mode) + ").");
        return 1;
    }

    private static int list(CommandSourceStack source, int page) {
        long now = System.currentTimeMillis();
        var active = EventBoundaries.store().active(now);
        int start = start(page, active.size());
        say(source, "Active event boundaries " + active.size() + " (page " + page + " of " + pageCount(active.size()) + ").");
        for (Boundary boundary : active.subList(start, Math.min(start + PAGE_SIZE, active.size()))) {
            say(source, boundary.id() + " · " + lower(boundary.mode()) + " · " + boundary.dimension()
                    + " · " + secondsLeft(boundary, now) + "s left");
        }
        return active.size();
    }

    private static int info(CommandContext<CommandSourceStack> context) {
        long now = System.currentTimeMillis();
        Boundary boundary = EventBoundaries.store().require(id(context), now);
        String height = boundary.halfHeight() == null ? "full height" : "Y " + (boundary.y() - boundary.halfHeight())
                + " through " + (boundary.y() + boundary.halfHeight());
        say(context.getSource(), boundary.id() + " · " + lower(boundary.mode()) + " · " + boundary.dimension()
                + " · center " + boundary.x() + ", " + boundary.y() + ", " + boundary.z()
                + " · radius " + boundary.radius() + " · " + height + " · " + secondsLeft(boundary, now) + "s left");
        say(context.getSource(), "Entry passes " + boundary.entries().size() + " · Exit passes " + boundary.exits().size()
                + " · Frozen roster " + boundary.roster().size());
        return 1;
    }

    private static int members(CommandContext<CommandSourceStack> context, String name, int page) {
        Boundary boundary = EventBoundaries.store().require(id(context), System.currentTimeMillis());
        var members = switch (name) {
            case "roster" -> boundary.roster();
            case "entry" -> boundary.entries();
            case "exit" -> boundary.exits();
            default -> throw new AssertionError(name);
        };
        var ordered = members.stream().map(UUID::toString).sorted().toList();
        int start = start(page, ordered.size());
        say(context.getSource(), boundary.id() + " " + name + " · " + ordered.size() + " UUIDs (page "
                + page + " of " + pageCount(ordered.size()) + ").");
        for (String uuid : ordered.subList(start, Math.min(start + PAGE_SIZE, ordered.size()))) {
            say(context.getSource(), uuid);
        }
        return ordered.size();
    }

    private static int freeze(CommandContext<CommandSourceStack> context) throws IOException {
        long now = System.currentTimeMillis();
        var source = context.getSource();
        Boundary boundary = EventBoundaries.store().require(id(context), now);
        Set<UUID> roster = new HashSet<>();
        for (var player : source.getServer().getPlayerList().getPlayers()) {
            if (player.isAlive() && boundary.contains(EventBoundaries.point(player))) {
                roster.add(player.getUUID());
            }
        }
        EventBoundaries.store().update(boundary.withRoster(roster), now);
        say(source, "Captured " + roster.size() + " online occupants for reentry to " + boundary.id() + ".");
        return roster.size();
    }

    private static int pass(CommandContext<CommandSourceStack> context, Boundary.Direction direction, boolean grant,
                            UUID uuid) throws IOException {
        long now = System.currentTimeMillis();
        var store = EventBoundaries.store();
        Boundary boundary = store.require(id(context), now);
        Set<UUID> members = new HashSet<>(direction == Boundary.Direction.ENTRY ? boundary.entries() : boundary.exits());
        boolean changed = grant ? members.add(uuid) : members.remove(uuid);
        if (changed) {
            store.update(boundary.withPass(direction, members), now);
        }
        say(context.getSource(), (grant ? "Granted " : "Revoked ") + lower(direction) + " pass for " + uuid
                + " at " + boundary.id() + (changed ? "." : " (already in that state)."));
        return 1;
    }

    private static int start(int page, int count) {
        int max = pageCount(count);
        if (page > max) {
            throw new IllegalArgumentException("Choose a page from 1 to " + max + ".");
        }
        return (page - 1) * PAGE_SIZE;
    }

    private static int pageCount(int count) {
        return Math.max(1, (count + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private static long secondsLeft(Boundary boundary, long now) {
        return Math.ceilDiv(boundary.expiresAt() - now, 1_000);
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static String id(CommandContext<CommandSourceStack> context) {
        return StringArgumentType.getString(context, "id");
    }

    private static void say(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }

    @FunctionalInterface
    private interface Command {
        int run() throws IOException;
    }

    private static int run(CommandContext<CommandSourceStack> context, Command command) {
        try {
            return command.run();
        } catch (IllegalArgumentException ex) {
            context.getSource().sendFailure(Component.literal(ex.getMessage()));
            return 0;
        } catch (IOException ex) {
            context.getSource().sendFailure(Component.literal("Event boundary state could not be written. Check the server log."));
            org.slf4j.LoggerFactory.getLogger("Event Boundaries").error("Failed to persist an event boundary command.", ex);
            return 0;
        }
    }
}
