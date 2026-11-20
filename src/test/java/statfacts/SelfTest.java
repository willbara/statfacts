package statfacts;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Standalone checks for the core logic (no server needed).
 * Usage: java -cp build/classes:gson.jar statfacts.SelfTest <path to players/stats>
 */
public final class SelfTest {
    private static int checks = 0;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            throw new AssertionError("FAILED: " + what);
        }
    }

    public static void main(String[] args) {
        Path folder = Path.of(args.length > 0 ? args[0] : "players/stats");

        // ----- formatting -----
        check(Format.groups(1234567).equals("1,234,567"), "groups 1234567");
        check(Format.groups(999).equals("999"), "groups 999");
        check(Format.groups(0).equals("0"), "groups 0");
        check(Format.tenths(15).equals("1.5"), "tenths 15 -> 1.5");
        check(Format.tenths(5).equals("0.5"), "tenths 5 -> 0.5");
        check(Format.tenths(1234).equals("123.4"), "tenths 1234 -> 123.4");
        check(Values.render(StatDef.Unit.KM, 1_513_978L).equals("15.1"), "km render 1513978 cm = 15.1 km");
        check(Values.render(StatDef.Unit.BLOCKS, 1_513_978L).equals("15,139"), "blocks render 1513978 cm = 15,139");
        check(Values.render(StatDef.Unit.HOURS, 1_513_978L).equals("21.0"), "hours render playtime ticks");
        check(Values.render(StatDef.Unit.HEARTS, 3_220L).equals("161.0"), "hearts render 3220 units = 161.0 hearts");

        // ----- catalog shape -----
        List<StatDef> all = StatCatalog.ALL;
        check(all.size() == 136, "catalog has 136 stats (got " + all.size() + ")");
        Set<String> ids = new HashSet<>();
        for (StatDef d : all) {
            check(ids.add(d.id()), "unique id " + d.id());
        }
        long milestoneCount = all.stream().filter(StatDef::milestones).count();
        check(milestoneCount == 128, "128 milestone-capable stats (got " + milestoneCount + ")");
        for (StatDef d : all) {
            if (d.kind() == StatDef.Kind.RAW) {
                check(!d.category().isEmpty() && !d.key().isEmpty(), "raw stat has JSON key: " + d.id());
            }
            if (d.altUnit() != null) {
                check(d.altTemplate() != null && d.altTemplate().contains("{n}"), "alt template: " + d.id());
            }
            check(d.template().contains("{n}"), "template has {n}: " + d.id());
            if (d.milestones()) {
                check(Values.ladderKey(d.unit()) != null, "milestone stat has a ladder: " + d.id());
            }
        }

        // ----- read the copied players/stats folder -----
        StatsSource source = new StatsSource(folder);
        Map<UUID, Map<String, Map<String, Long>>> files = source.readAll();
        check(!files.isEmpty(), "found stat files in " + folder + " (" + files.size() + ")");
        Values values = new Values(all);

        Map<UUID, PlayerStats> players = new LinkedHashMap<>();
        for (Map.Entry<UUID, Map<String, Map<String, Long>>> e : files.entrySet()) {
            String name = "player-" + e.getKey().toString().substring(0, 8);
            PlayerStats p = new PlayerStats(e.getKey(), name, e.getValue());
            players.put(e.getKey(), p);
            System.out.printf("     %s walk=%d playtime=%d mobkills=%d travel=%d r_walk=%d r_dmg=%d%n",
                    name,
                    p.raw("minecraft:custom", "minecraft:walk_one_cm"),
                    p.raw("minecraft:custom", "minecraft:play_time"),
                    p.raw("minecraft:killed", "minecraft:zombie"),
                    values.value(values.def("travel"), p),
                    values.value(values.def("r_walk"), p),
                    values.value(values.def("r_dmg"), p));
        }

        // travel is the sum of the twelve distance stats (in cm)
        for (PlayerStats p : players.values()) {
            long sum = 0;
            for (String id : List.of("walk", "sprint", "crouch", "swim", "seafloor", "elytra",
                    "fly", "boat", "horse", "happyghast", "minecart", "climb")) {
                StatDef d = values.def(id);
                sum += p.raw(d.category(), d.key());
            }
            check(values.value(values.def("travel"), p) == sum, "travel sums distance stats for " + p.uuid());
        }

        // per-hour averages: tenths of a unit per hour of playtime, like derive/r_*.mcfunction
        for (PlayerStats p : players.values()) {
            long playtime = p.raw("minecraft:custom", "minecraft:play_time");
            long walk = p.raw("minecraft:custom", "minecraft:walk_one_cm");
            long expectedWalk = playtime < 72_000
                    ? 0
                    : ((walk / 100) * 100) / (playtime / 7_200);
            check(values.value(values.def("r_walk"), p) == expectedWalk, "r_walk formula for " + p.uuid());
            long expectedDmg = playtime < 72_000
                    ? 0
                    : ((p.raw("minecraft:custom", "minecraft:damage_dealt") / 20) * 100) / (playtime / 7_200);
            check(values.value(values.def("r_dmg"), p) == expectedDmg, "r_dmg formula for " + p.uuid());
        }

        // ----- settings -----
        Settings settings = new Settings();
        settings.statsOn.addAll(ids);
        settings.includeOffline = true;
        settings.multiChance = 25;
        settings.excluded.add("nobody-excluded-by-this-name");
        settings.ladders.put("km", new long[] {100, 1000, 10_000});
        settings.ladders.put("count", new long[] {1000, 10_000, 100_000});
        settings.ladders.put("hours", new long[] {100, 1000});
        settings.ladders.put("hearts", new long[] {1000, 10_000});
        check(!settings.isExcluded(players.values().iterator().next()), "exclusion does not match unrelated players");

        // ----- announcements -----
        List<PlayerStats> online = new ArrayList<>(players.values());
        Announcer announcer = new Announcer(settings, values, all, new Random(12345));
        int produced = 0;
        int comparisons = 0;
        int badLines = 0;
        String firstBad = null;
        Set<String> styleEnds = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            List<String> lines = announcer.run(online, online);
            if (lines.isEmpty()) {
                continue;
            }
            produced++;
            String line = lines.get(0);
            boolean clean = !line.contains("{n}") && !line.contains("null") && !line.contains("  ")
                    && (line.endsWith(".") || line.endsWith("!") || line.endsWith("?"));
            if (!clean) {
                badLines++;
                firstBad = firstBad == null ? line : firstBad;
            }
            if (line.contains("players online") || line.contains("Head to head") || line.contains("Of everyone")
                    || line.contains("Out in front") || line.contains("Together")
                    || line.contains("Between them") || line.contains("while")) {
                comparisons++;
            }
            styleEnds.add(line.substring(line.length() - 1));
            if (i < 12) {
                System.out.println("     > " + line);
            }
        }
        check(badLines == 0, "every announcement is clean (bad: " + badLines + (firstBad == null ? "" : ", e.g. " + firstBad) + ")");
        check(produced > 4900, "almost every roll produced an announcement (" + produced + "/5000)");
        check(comparisons > 0, "some announcements are comparisons (" + comparisons + ")");
        check(styleEnds.containsAll(Set.of(".", "!", "?")), "all three endings used");

        // a player with nothing eligible gets no fact
        PlayerStats empty = PlayerStats.empty(UUID.randomUUID(), "nobody");
        check(announcer.single(empty).isEmpty(), "empty player yields no fact");

        // ----- milestones -----
        PlayerStats first = players.values().iterator().next();
        Milestones milestones = new Milestones(Map.of());
        Random mr = new Random(7);
        List<PlayerStats> one = List.of(first);
        List<String> baseline = milestones.check(one, settings, all, values, mr);
        check(baseline.isEmpty(), "first sight is silent (baseline)");

        PlayerStats walked1000 = first.withRaw("minecraft:custom", "minecraft:walk_one_cm", 100_000_000L);
        List<String> crossed = milestones.check(List.of(walked1000), settings, all, values, mr);
        check(crossed.size() == 1, "one milestone when crossing 1,000 km (got " + crossed.size() + ")");
        check(crossed.get(0).contains("walked 1,000 km") && !crossed.get(0).contains("Incredible"),
                "1,000 km milestone reads correctly: " + crossed.get(0));
        System.out.println("     > " + crossed.get(0));

        check(milestones.check(List.of(walked1000), settings, all, values, mr).isEmpty(),
                "no repeat for the same tier");

        PlayerStats walked10000 = first.withRaw("minecraft:custom", "minecraft:walk_one_cm", 1_000_000_000L);
        List<String> top = milestones.check(List.of(walked10000), settings, all, values, mr);
        check(top.size() == 1 && top.get(0).contains("walked 10,000 km! Incredible!"),
                "top rung says Incredible: " + (top.isEmpty() ? "none" : top.get(0)));
        System.out.println("     > " + top.get(0));

        // a count stat at the old thresholds (10 bells) must not fire any more
        PlayerStats bells = first.withRaw("minecraft:custom", "minecraft:bell_ring", 10L);
        Milestones fresh = new Milestones(Map.of());
        fresh.check(List.of(first), settings, all, values, mr);
        check(fresh.check(List.of(bells), settings, all, values, mr).isEmpty(),
                "10 bells is no longer a milestone");
        PlayerStats bells1000 = first.withRaw("minecraft:custom", "minecraft:bell_ring", 1000L);
        check(fresh.check(List.of(bells1000), settings, all, values, mr).size() == 1,
                "1,000 bells is a milestone");

        // the milestone file round-trip keeps tiers
        Map<String, Map<String, Long>> saved = milestones.snapshot();
        Milestones reloaded = new Milestones(saved);
        check(reloaded.check(List.of(walked10000), settings, all, values, mr).isEmpty(),
                "reloaded tiers suppress repeats");

        System.out.println();
        System.out.println("all " + checks + " checks passed");
    }
}
