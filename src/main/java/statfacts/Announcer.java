package statfacts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Picks and words one announcement. Ports announce_core, pick, pick_multi and the multi/ files
 * from the datapack. Pure logic: no Bukkit, so it can be tested directly.
 */
final class Announcer {

    /** Sentence openers and endings for single-player facts, in the datapack's order. */
    private static final String[][] STYLES = {
            {"", "!"},
            {"Did you know that ", "?"},
            {"Fun fact: ", "!"},
            {"Stat check: ", "!"},
            {"Believe it or not, ", "!"},
            {"The records show that ", "."},
            {"Here's one for the books: ", "!"},
            {"Quick stat: ", "."},
            {"Server trivia: ", "!"},
            {"Just so you know, ", "."},
            {"According to the stats, ", "."},
    };

    private record Entry(PlayerStats player, long value) {}

    private final Settings settings;
    private final Values values;
    private final List<StatDef> defs;
    private final Random random;

    Announcer(Settings settings, Values values, List<StatDef> defs, Random random) {
        this.settings = settings;
        this.values = values;
        this.defs = defs;
        this.random = random;
    }

    /**
     * One announcement, as lines to show to everyone. Empty when nothing qualifies.
     *
     * @param online players online right now, excluded players already removed
     * @param known  every player with stats (online or not), excluded players already removed
     */
    List<String> run(List<PlayerStats> online, List<PlayerStats> known) {
        if (online.size() >= 2 && random.nextInt(100) < settings.multiChance) {
            List<String> comparison = comparison(online);
            if (!comparison.isEmpty()) {
                return comparison;
            }
        }
        List<PlayerStats> pool = new ArrayList<>(settings.includeOffline ? known : online);
        Collections.shuffle(pool, random);
        for (PlayerStats p : pool) {
            Optional<String> line = single(p);
            if (line.isPresent()) {
                return List.of(line.get());
            }
        }
        return List.of();
    }

    /** A fact about one player. Empty if the player has nothing announceable. */
    Optional<String> single(PlayerStats p) {
        List<StatDef> eligible = new ArrayList<>();
        for (StatDef d : defs) {
            if (settings.isOn(d) && values.value(d, p) >= d.minValue()) {
                eligible.add(d);
            }
        }
        if (eligible.isEmpty()) {
            return Optional.empty();
        }
        StatDef d = eligible.get(random.nextInt(eligible.size()));
        long value = values.value(d, p);
        int style = random.nextInt(STYLES.length);
        boolean useAlt = d.altUnit() != null && random.nextInt(2) == 1;
        if (d.forceFirstBelow() >= 0 && value <= d.forceFirstBelow()) {
            useAlt = false;
        }
        StatDef.Unit unit = useAlt ? d.altUnit() : d.unit();
        String template = useAlt ? d.altTemplate() : d.template();
        String phrase = template.replace("{n}", Values.render(unit, value));
        String verb = d.presentTense() ? phrase : "has " + phrase;
        return Optional.of(STYLES[style][0] + p.name() + " " + verb + STYLES[style][1]);
    }

    /** Leader, head-to-head or combined total for one stat that two or more players have. */
    private List<String> comparison(List<PlayerStats> online) {
        List<StatDef> candidates = new ArrayList<>();
        for (StatDef d : defs) {
            if (!d.comparable() || !settings.isOn(d)) {
                continue;
            }
            if (entries(d, online).size() >= 2) {
                candidates.add(d);
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        StatDef d = candidates.get(random.nextInt(candidates.size()));
        List<Entry> eligible = entries(d, online);

        long max = eligible.stream().mapToLong(Entry::value).max().orElse(0);
        long topCount = eligible.stream().filter(e -> e.value() == max).count();
        int kind = random.nextInt(3);     // 0 leader, 1 head-to-head, 2 total
        int style = random.nextInt(3);
        if (topCount >= 2 && kind == 0) {
            kind = 2;                      // a tied lead becomes a total, as in the datapack
        }

        if (kind == 0) {
            Entry leader = eligible.stream().filter(e -> e.value() == max).findFirst().orElseThrow();
            String phrase = "has " + d.template().replace("{n}", Values.render(d.unit(), max));
            String who = leader.player().name();
            return List.of(switch (style) {
                case 0 -> "Of everyone online, " + who + " " + phrase + ", more than anyone else here!";
                case 1 -> "Out in front: " + who + " " + phrase + ", the most of anyone online!";
                default -> "Did you know that " + who + " " + phrase + ", more than anyone else online?";
            });
        }

        if (kind == 1) {
            Collections.shuffle(eligible, random);
            Entry a = eligible.get(0);
            Entry b = eligible.get(1);
            String ta = "has " + d.template().replace("{n}", Values.render(d.unit(), a.value()));
            String tb = "has " + d.template().replace("{n}", Values.render(d.unit(), b.value()));
            String nameA = a.player().name();
            String nameB = b.player().name();
            return List.of(switch (style) {
                case 0 -> nameA + " " + ta + ", while " + nameB + " " + tb + ".";
                case 1 -> "Head to head: " + nameA + " " + ta + ", " + nameB + " " + tb + ".";
                default -> "Did you know that " + nameA + " " + ta + ", while " + nameB + " " + tb + "?";
            });
        }

        long sum = eligible.stream().mapToLong(Entry::value).sum();
        String phrase = "have " + d.template().replace("{n}", Values.render(d.unit(), sum));
        int count = eligible.size();
        return List.of(switch (style) {
            case 0 -> "Together, the " + count + " players online " + phrase + "!";
            case 1 -> "Between them, the " + count + " players online " + phrase + "!";
            default -> "Fun fact: combined, the " + count + " players online " + phrase + "!";
        });
    }

    private List<Entry> entries(StatDef d, List<PlayerStats> online) {
        List<Entry> out = new ArrayList<>();
        for (PlayerStats p : online) {
            long v = values.value(d, p);
            if (v >= d.multiMin()) {
                out.add(new Entry(p, v));
            }
        }
        return out;
    }
}
