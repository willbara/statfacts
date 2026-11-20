package statfacts;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * Computes stat values and renders them. The arithmetic mirrors the datapack's scoreboard math
 * (integer division, tenths for hours and averages) so the numbers come out the same.
 */
final class Values {
    /** Playtime is counted in ticks; 72000 ticks is one hour. */
    static final long TICKS_PER_HOUR = 72_000L;

    /** Stats added together for the "traveled X km in total" stat. */
    private static final List<String> TRAVEL_IDS = List.of(
            "walk", "sprint", "crouch", "swim", "seafloor", "elytra",
            "fly", "boat", "horse", "happyghast", "minecart", "climb");

    private final Map<String, StatDef> byId = new HashMap<>();

    Values(List<StatDef> defs) {
        for (StatDef d : defs) {
            byId.put(d.id(), d);
        }
    }

    StatDef def(String id) {
        StatDef d = byId.get(id);
        if (d == null) {
            throw new IllegalStateException("missing stat " + id);
        }
        return d;
    }

    /** Raw value of a plain JSON stat, looked up by its catalog id. */
    private long raw(String id, PlayerStats p) {
        StatDef d = def(id);
        return p.raw(d.category(), d.key());
    }

    /** The value a stat is judged and announced by (raw units, or tenths for derived rates). */
    long value(StatDef d, PlayerStats p) {
        return switch (d.kind()) {
            case RAW -> p.raw(d.category(), d.key());
            case TRAVEL -> {
                long total = 0;
                for (String id : TRAVEL_IDS) {
                    total += raw(id, p);
                }
                yield total;
            }
            case RATE_WALK -> rate(p, q -> (raw("walk", q) / 100) * 100);
            case RATE_MOBS -> rate(p, q -> raw("mobkills", q) * 100);
            case RATE_JUMP -> rate(p, q -> raw("jump", q) * 100);
            case RATE_CHESTS -> rate(p, q -> raw("chests", q) * 100);
            case RATE_DMG -> rate(p, q -> (raw("dmgdealt", q) / 20) * 100);
        };
    }

    /** Per-hour average in tenths, like the datapack's derive/r_* functions. */
    private long rate(PlayerStats p, ToLongFunction<PlayerStats> numerator) {
        long playtime = raw("playtime", p);
        if (playtime < TICKS_PER_HOUR) {
            return 0;
        }
        long tenthsOfHours = playtime / 7200L;
        return numerator.applyAsLong(p) / tenthsOfHours;
    }

    /** Renders a value in the given unit, e.g. KM 12345 cm -> "0.1". */
    static String render(StatDef.Unit unit, long value) {
        return switch (unit) {
            case KM -> Format.tenths(value / 10_000L);
            case BLOCKS -> Format.groups(value / 100L);
            case COUNT -> Format.groups(value);
            case HOURS -> Format.tenths(value / 7_200L);
            case DAYS -> Format.tenths(value / 172_800L);
            case HEARTS -> Format.tenths(value / 2L);
            case TENTHS -> Format.tenths(value);
        };
    }

    /** Milestone rung ladder name for a unit, or null when the unit has no milestone ladder. */
    static String ladderKey(StatDef.Unit unit) {
        return switch (unit) {
            case KM, BLOCKS -> "km";
            case COUNT -> "count";
            case HOURS -> "hours";
            case HEARTS -> "hearts";
            case DAYS, TENTHS -> null;
        };
    }

    /** Whole milestone units for a raw value: km, count, hours or hearts. */
    static long ladderValue(StatDef.Unit unit, long raw) {
        return switch (unit) {
            case KM, BLOCKS -> raw / 100_000L;
            case COUNT -> raw;
            case HOURS -> raw / TICKS_PER_HOUR;
            case HEARTS -> raw / 20L;
            case DAYS, TENTHS -> 0L;
        };
    }
}
