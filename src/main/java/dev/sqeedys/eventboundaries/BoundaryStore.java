package dev.sqeedys.eventboundaries;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class BoundaryStore {
    private static final int VERSION = 1;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).serializeNulls()
            .setPrettyPrinting().create();

    private record State(int version, List<Boundary> boundaries) {}

    private final Path path;
    private Map<String, Boundary> boundaries;

    BoundaryStore(Path path, long now) throws IOException {
        this.path = path;
        boundaries = read(path, now);
    }

    Collection<Boundary> all() {
        return boundaries.values();
    }

    List<Boundary> active(long now) {
        return boundaries.values().stream().filter(boundary -> boundary.active(now)).toList();
    }

    Boundary require(String id, long now) {
        Boundary result = boundaries.get(id);
        if (result == null || !result.active(now)) {
            throw new IllegalArgumentException("No active boundary named " + id + ".");
        }
        return result;
    }

    void create(Boundary boundary, long now) throws IOException {
        var next = activeMap(now);
        if (next.putIfAbsent(boundary.id(), boundary) != null) {
            throw new IllegalArgumentException("That boundary ID is already active.");
        }
        save(next);
    }

    void update(Boundary boundary, long now) throws IOException {
        require(boundary.id(), now);
        var next = activeMap(now);
        next.put(boundary.id(), boundary);
        save(next);
    }

    void release(String id, long now) throws IOException {
        require(id, now);
        var next = activeMap(now);
        next.remove(id);
        save(next);
    }

    void discardExpired(long now) {
        if (boundaries.values().stream().anyMatch(boundary -> !boundary.active(now))) {
            boundaries = Collections.unmodifiableMap(activeMap(now));
        }
    }

    private Map<String, Boundary> activeMap(long now) {
        var active = new LinkedHashMap<String, Boundary>();
        for (Boundary boundary : boundaries.values()) {
            if (boundary.active(now)) {
                active.put(boundary.id(), boundary);
            }
        }
        return active;
    }

    private static Map<String, Boundary> read(Path path, long now) throws IOException {
        if (Files.notExists(path)) {
            return Map.of();
        }
        if (Files.size(path) > MAX_BYTES) {
            throw new IOException("Event boundary state exceeds the file size limit.");
        }
        try {
            JsonObject document = JSON.fromJson(Files.readString(path), JsonObject.class);
            validateMembers(document);
            State state = JSON.fromJson(document, State.class);
            if (state == null || state.version != VERSION || state.boundaries == null
                    || state.boundaries.size() > Boundary.MAX_BOUNDARIES) {
                throw new IllegalArgumentException("Unsupported event boundary state version or boundary count.");
            }
            var result = new TreeMap<String, Boundary>();
            for (Boundary boundary : state.boundaries) {
                if (boundary == null || result.putIfAbsent(boundary.id(), boundary) != null) {
                    throw new IllegalArgumentException("Missing or duplicate boundary.");
                }
            }
            result.values().removeIf(boundary -> !boundary.active(now));
            return Collections.unmodifiableMap(result);
        } catch (JsonParseException | IllegalArgumentException ex) {
            throw new IOException("Invalid event boundary state.", ex);
        }
    }

    private static void validateMembers(JsonObject document) {
        if (document == null || !number(document.get("version")) || !document.get("version").getAsString().equals("1")
                || !document.has("boundaries") || !document.get("boundaries").isJsonArray()) {
            throw new IllegalArgumentException("Expected event boundary state version 1 and a boundaries array.");
        }
        var boundaries = document.getAsJsonArray("boundaries");
        if (boundaries.size() > Boundary.MAX_BOUNDARIES) {
            throw new IllegalArgumentException("Too many saved boundaries.");
        }
        for (JsonElement element : boundaries) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("Expected a boundary object.");
            }
            JsonObject boundary = element.getAsJsonObject();
            for (String name : List.of("x", "y", "z", "radius", "expiresAt")) {
                if (!number(boundary.get(name))) {
                    throw new IllegalArgumentException("Expected a number for " + name + ".");
                }
            }
            if (!boundary.get("expiresAt").getAsString().matches("[1-9][0-9]{0,18}")) {
                throw new IllegalArgumentException("Expected an integer wall-clock expiration.");
            }
            JsonElement height = boundary.get("halfHeight");
            if (height == null || !height.isJsonNull() && !number(height)) {
                throw new IllegalArgumentException("Expected halfHeight to be a number or explicit null.");
            }
            for (String name : List.of("id", "dimension", "mode")) {
                if (!string(boundary.get(name))) {
                    throw new IllegalArgumentException("Expected a string for " + name + ".");
                }
            }
            for (String name : List.of("entries", "exits", "roster")) {
                JsonElement list = boundary.get(name);
                if (list == null || !list.isJsonArray() || list.getAsJsonArray().size() > Boundary.MAX_MEMBERS) {
                    throw new IllegalArgumentException("Expected a bounded UUID array for " + name + ".");
                }
                for (JsonElement member : list.getAsJsonArray()) {
                    if (!string(member) || !java.util.UUID.fromString(member.getAsString()).toString()
                            .equalsIgnoreCase(member.getAsString())) {
                        throw new IllegalArgumentException("Expected a canonical UUID in " + name + ".");
                    }
                }
            }
        }
    }

    private static boolean number(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber();
    }

    private static boolean string(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    private void save(Map<String, Boundary> next) throws IOException {
        if (next.size() > Boundary.MAX_BOUNDARIES) {
            throw new IllegalArgumentException("Release a boundary before exceeding " + Boundary.MAX_BOUNDARIES + " active boundaries.");
        }
        var sorted = new TreeMap<>(next);
        byte[] bytes = (JSON.toJson(new State(VERSION, new ArrayList<>(sorted.values()))) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "eventboundaries-", ".tmp");
        try {
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    file.write(buffer);
                }
                file.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            boundaries = Collections.unmodifiableMap(sorted);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
