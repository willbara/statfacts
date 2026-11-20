package statfacts;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads <uuid>.json stat files from a folder (the server's players/stats directory).
 * Files are re-read only when their modified time or size changes, so polling is cheap.
 */
final class StatsSource {
    private record Cached(long modified, long size, Map<String, Map<String, Long>> stats) {}

    private final Path folder;
    private final Map<UUID, Cached> cache = new HashMap<>();

    StatsSource(Path folder) {
        this.folder = folder;
    }

    Path folder() {
        return folder;
    }

    /** Every readable stat file, keyed by player UUID. Bad or half-written files keep their last good copy. */
    Map<UUID, Map<String, Map<String, Long>>> readAll() {
        Map<UUID, Map<String, Map<String, Long>>> out = new LinkedHashMap<>();
        if (!Files.isDirectory(folder)) {
            cache.clear();
            return out;
        }
        Set<UUID> seen = new HashSet<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.json")) {
            for (Path file : files) {
                UUID id = uuidOf(file);
                if (id == null) {
                    continue;
                }
                seen.add(id);
                try {
                    long modified = Files.getLastModifiedTime(file).toMillis();
                    long size = Files.size(file);
                    Cached cached = cache.get(id);
                    if (cached == null || cached.modified() != modified || cached.size() != size) {
                        cached = new Cached(modified, size, parse(file));
                        cache.put(id, cached);
                    }
                    out.put(id, cached.stats());
                } catch (IOException | RuntimeException e) {
                    Cached last = cache.get(id);
                    if (last != null) {
                        out.put(id, last.stats());
                    }
                }
            }
        } catch (IOException e) {
            // folder vanished mid-read: report nothing this time
        }
        cache.keySet().retainAll(seen);
        return out;
    }

    private static UUID uuidOf(Path file) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".json")) {
            return null;
        }
        try {
            return UUID.fromString(name.substring(0, name.length() - ".json".length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Map<String, Map<String, Long>> parse(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            Map<String, Map<String, Long>> out = new HashMap<>();
            JsonObject stats = root.has("stats") && root.get("stats").isJsonObject()
                    ? root.getAsJsonObject("stats")
                    : null;
            if (stats == null) {
                return out;
            }
            for (Map.Entry<String, JsonElement> category : stats.entrySet()) {
                Map<String, Long> values = new HashMap<>();
                if (category.getValue().isJsonObject()) {
                    for (Map.Entry<String, JsonElement> stat : category.getValue().getAsJsonObject().entrySet()) {
                        JsonElement value = stat.getValue();
                        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                            values.put(stat.getKey(), value.getAsLong());
                        }
                    }
                }
                out.put(category.getKey(), values);
            }
            return out;
        }
    }
}
