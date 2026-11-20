package statfacts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Milestones fire when a player's stat crosses a rung of its unit's ladder (see Settings.ladders).
 * The highest tier each player has reached per stat is remembered, so a rung is only announced once.
 * The first time a stat is seen for a player, its current tier is recorded silently.
 */
final class Milestones {

    /** uuid -> stat id -> highest tier reached. */
    private final Map<String, Map<String, Long>> tiers;
    private boolean dirty;

    Milestones(Map<String, Map<String, Long>> saved) {
        this.tiers = new HashMap<>();
        saved.forEach((uuid, perStat) -> tiers.put(uuid, new HashMap<>(perStat)));
    }

    /** Snapshot for saving. */
    Map<String, Map<String, Long>> snapshot() {
        return tiers;
    }

    /** True if anything changed since the last save. Clears the flag. */
    boolean takeDirty() {
        boolean was = dirty;
        dirty = false;
        return was;
    }

    /** Checks every online player's milestone stats. Returns the lines to announce. */
    List<String> check(List<PlayerStats> online, Settings settings, List<StatDef> defs,
                       Values values, Random random) {
        List<String> out = new ArrayList<>();
        for (PlayerStats p : online) {
            String key = p.uuid().toString();
            for (StatDef d : defs) {
                if (!d.milestones() || !settings.isOn(d)) {
                    continue;
                }
                String ladderKey = Values.ladderKey(d.unit());
                long[] rungs = ladderKey == null ? new long[0] : settings.ladder(ladderKey);
                if (rungs.length == 0) {
                    continue;
                }
                long shown = Values.ladderValue(d.unit(), values.value(d, p));
                int tier = 0;
                for (long rung : rungs) {
                    if (shown >= rung) {
                        tier++;
                    }
                }

                Map<String, Long> perStat = tiers.computeIfAbsent(key, k -> new HashMap<>());
                Long stored = perStat.get(d.id());
                if (stored == null || tier < stored) {
                    // first sight, or the ladder was raised: remember silently
                    perStat.put(d.id(), (long) tier);
                    dirty = true;
                    continue;
                }
                if (tier > stored) {
                    perStat.put(d.id(), (long) tier);
                    dirty = true;
                    long rung = rungs[tier - 1];
                    String phrase = d.template().replace("{n}", Format.groups(rung));
                    String incredible = tier == rungs.length ? " Incredible!" : "";
                    String body = "has now " + phrase + "!" + incredible;
                    String who = p.name();
                    out.add(switch (random.nextInt(3)) {
                        case 0 -> "Milestone reached! " + who + " " + body;
                        case 1 -> "Congratulations to " + who + ", who " + body;
                        default -> "A new milestone for " + who + ", who " + body;
                    });
                }
            }
        }
        return out;
    }
}
