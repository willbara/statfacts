package statfacts;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Plugin settings as plain values, so the core logic can be tested without a server. */
final class Settings {
    boolean enabled = true;
    long intervalSeconds = 600;
    int minPlayers = 1;
    int multiChance = 25;
    boolean includeOffline = true;
    boolean milestones = true;
    long milestoneSeconds = 5;
    String statsFolder = "players/stats";

    /** Rung values per ladder: "km", "count", "hours", "hearts". Ascending. */
    final Map<String, long[]> ladders = new HashMap<>();

    /** Excluded players: lowercase names or UUID strings. */
    final Set<String> excluded = new HashSet<>();

    /** Ids of stats that may be announced. */
    final Set<String> statsOn = new HashSet<>();

    boolean isOn(StatDef d) {
        return statsOn.contains(d.id());
    }

    boolean isExcluded(PlayerStats p) {
        return excluded.contains(p.name().toLowerCase(Locale.ROOT))
                || excluded.contains(p.uuid().toString());
    }

    long[] ladder(String key) {
        long[] rungs = ladders.get(key);
        return rungs == null ? new long[0] : rungs;
    }
}
