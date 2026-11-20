package statfacts;

/**
 * One announceable stat. The list of them lives in {@link StatCatalog}, which is generated
 * from the original datapack so the thresholds and wording match it exactly.
 *
 * @param id             short id used in config.yml and commands, e.g. "walk" or "u_dpick"
 * @param kind           how the value is computed (plain JSON stat, or derived from several)
 * @param category       JSON category, e.g. "minecraft:custom" (empty for derived stats)
 * @param key            JSON key, e.g. "minecraft:walk_one_cm" (empty for derived stats)
 * @param unit           primary display unit
 * @param altUnit        second display unit picked at random, or null
 * @param template       verb phrase with {n} for the number, e.g. "walked {n} km"
 * @param altTemplate    template used with altUnit, or null
 * @param presentTense   true for "X walks an average of..." (no "has")
 * @param minValue       raw value a player needs before this stat can be announced
 * @param multiMin       raw value per player needed for comparisons; -1 = never compared
 * @param milestones     whether this stat can raise milestones
 * @param forceFirstBelow when >= 0, the primary unit is used while the value is at or below this
 */
record StatDef(
        String id,
        Kind kind,
        String category,
        String key,
        Unit unit,
        Unit altUnit,
        String template,
        String altTemplate,
        boolean presentTense,
        long minValue,
        long multiMin,
        boolean milestones,
        long forceFirstBelow) {

    enum Kind { RAW, TRAVEL, RATE_WALK, RATE_MOBS, RATE_JUMP, RATE_CHESTS, RATE_DMG }

    /** Display units. Raw values are in game units; see {@link Values#render}. */
    enum Unit { KM, BLOCKS, COUNT, HOURS, DAYS, HEARTS, TENTHS }

    boolean comparable() {
        return multiMin >= 0;
    }
}
