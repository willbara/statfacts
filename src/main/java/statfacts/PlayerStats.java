package statfacts;

import java.util.Map;
import java.util.UUID;

/** One player's raw statistics, as read from a players/stats JSON file. */
final class PlayerStats {
    private final UUID uuid;
    private final String name;
    private final Map<String, Map<String, Long>> stats;

    PlayerStats(UUID uuid, String name, Map<String, Map<String, Long>> stats) {
        this.uuid = uuid;
        this.name = name;
        this.stats = stats;
    }

    static PlayerStats empty(UUID uuid, String name) {
        return new PlayerStats(uuid, name, Map.of());
    }

    UUID uuid() {
        return uuid;
    }

    String name() {
        return name;
    }

    /** Raw value of one stat, e.g. raw("minecraft:custom", "minecraft:walk_one_cm"). Missing = 0. */
    long raw(String category, String key) {
        Map<String, Long> inCategory = stats.get(category);
        if (inCategory == null) {
            return 0L;
        }
        return inCategory.getOrDefault(key, 0L);
    }

    /** Same player with one raw stat replaced. Used by tests and by milestone checks. */
    PlayerStats withRaw(String category, String key, long value) {
        Map<String, Map<String, Long>> copy = new java.util.HashMap<>();
        for (Map.Entry<String, Map<String, Long>> e : stats.entrySet()) {
            copy.put(e.getKey(), new java.util.HashMap<>(e.getValue()));
        }
        copy.computeIfAbsent(category, k -> new java.util.HashMap<>()).put(key, value);
        return new PlayerStats(uuid, name, copy);
    }
}
