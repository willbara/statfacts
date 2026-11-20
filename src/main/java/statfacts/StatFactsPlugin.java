package statfacts;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** StatFacts: announces random player stats and milestones, read live from the players/stats folder. */
public final class StatFactsPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    private static final String PREFIX = "[StatFacts] ";
    private static final List<String> SUBCOMMANDS =
            List.of("status", "reload", "announce", "set", "stat", "stats", "exclude");
    private static final List<String> LADDERS = List.of("km", "count", "hours", "hearts");

    /** User-facing keys for "set", mapped to config paths and their value type. */
    private enum SetType { BOOL, MIN_ONE, PERCENT, NON_NEGATIVE }
    private record SetKey(String path, SetType type) {}
    private static final Map<String, SetKey> SET_KEYS = new LinkedHashMap<>();
    static {
        SET_KEYS.put("enabled", new SetKey("enabled", SetType.BOOL));
        SET_KEYS.put("interval", new SetKey("interval-seconds", SetType.MIN_ONE));
        SET_KEYS.put("min-players", new SetKey("min-players", SetType.NON_NEGATIVE));
        SET_KEYS.put("multi-chance", new SetKey("multi-chance", SetType.PERCENT));
        SET_KEYS.put("include-offline", new SetKey("include-offline", SetType.BOOL));
        SET_KEYS.put("milestones", new SetKey("milestones.enabled", SetType.BOOL));
        SET_KEYS.put("milestone-seconds", new SetKey("milestones.check-seconds", SetType.MIN_ONE));
    }

    private record Snapshot(List<PlayerStats> online, List<PlayerStats> known) {}

    private final Settings settings = new Settings();
    private final Random random = new Random();
    private final Map<UUID, String> names = new HashMap<>();
    private final Values values = new Values(StatCatalog.ALL);
    private StatsSource source;
    private Milestones milestones;
    private File milestonesFile;
    private long secondsSinceAnnounce;
    private long secondsSinceMilestones;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        milestonesFile = new File(getDataFolder(), "milestones.yml");
        milestones = new Milestones(loadMilestones());
        loadSettings();
        getServer().getScheduler().runTaskTimer(this, this::secondTick, 20L, 20L);
        if (getCommand("statfacts") != null) {
            getCommand("statfacts").setExecutor(this);
            getCommand("statfacts").setTabCompleter(this);
        }
        getLogger().info("Ready: " + StatCatalog.ALL.size() + " stats, reading " + source.folder());
    }

    @Override
    public void onDisable() {
        saveMilestones();
    }

    // ----- settings -----

    private void loadSettings() {
        reloadConfig();
        var cfg = getConfig();
        settings.enabled = cfg.getBoolean("enabled", true);
        settings.intervalSeconds = Math.max(1, cfg.getLong("interval-seconds", 600));
        settings.minPlayers = Math.max(0, cfg.getInt("min-players", 1));
        settings.multiChance = Math.min(100, Math.max(0, cfg.getInt("multi-chance", 25)));
        settings.includeOffline = cfg.getBoolean("include-offline", true);
        settings.milestones = cfg.getBoolean("milestones.enabled", true);
        settings.milestoneSeconds = Math.max(1, cfg.getLong("milestones.check-seconds", 5));
        settings.statsFolder = cfg.getString("stats-folder", "players/stats");

        settings.ladders.clear();
        for (String key : LADDERS) {
            List<Long> rungs = new ArrayList<>();
            for (Object o : cfg.getList("milestones." + key, List.of())) {
                if (o instanceof Number n) {
                    rungs.add(n.longValue());
                }
            }
            rungs.sort(null);
            settings.ladders.put(key, rungs.stream().mapToLong(Long::longValue).toArray());
        }

        settings.excluded.clear();
        for (String name : cfg.getStringList("excluded-players")) {
            settings.excluded.add(name.toLowerCase(Locale.ROOT));
        }

        settings.statsOn.clear();
        for (StatDef d : StatCatalog.ALL) {
            if (cfg.getBoolean("stats." + d.id(), true)) {
                settings.statsOn.add(d.id());
            }
        }

        File folder = new File(settings.statsFolder);
        if (!folder.isAbsolute()) {
            folder = new File(Bukkit.getWorldContainer(), settings.statsFolder);
        }
        source = new StatsSource(folder.toPath());
    }

    private void saveConfigValue(String path, Object value) {
        getConfig().set(path, value);
        saveConfig();
        loadSettings();
    }

    // ----- the clock -----

    /** Runs once a second on the main thread. */
    private void secondTick() {
        if (!settings.enabled) {
            return;
        }
        secondsSinceAnnounce++;
        secondsSinceMilestones++;

        if (secondsSinceAnnounce >= settings.intervalSeconds) {
            secondsSinceAnnounce = 0;
            Snapshot snap = snapshot();
            if (snap.online().size() >= settings.minPlayers) {
                for (String line : announcement(snap)) {
                    broadcast(line);
                }
            }
        }

        if (settings.milestones && secondsSinceMilestones >= settings.milestoneSeconds) {
            secondsSinceMilestones = 0;
            List<String> lines = milestones.check(snapshot().online(), settings, StatCatalog.ALL, values, random);
            if (milestones.takeDirty()) {
                saveMilestones();
            }
            lines.forEach(this::broadcast);
        }
    }

    private List<String> announcement(Snapshot snap) {
        return new Announcer(settings, values, StatCatalog.ALL, random).run(snap.online(), snap.known());
    }

    // ----- reading players -----

    /** Players from the stats folder plus everyone online, minus excluded players. */
    private Snapshot snapshot() {
        Map<UUID, Map<String, Map<String, Long>>> files = source.readAll();
        Map<UUID, PlayerStats> known = new LinkedHashMap<>();
        for (Map.Entry<UUID, Map<String, Map<String, Long>>> e : files.entrySet()) {
            known.put(e.getKey(), new PlayerStats(e.getKey(), nameOf(e.getKey()), e.getValue()));
        }
        List<PlayerStats> online = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            names.put(id, player.getName());
            PlayerStats ps = new PlayerStats(id, player.getName(), files.getOrDefault(id, Map.of()));
            known.put(id, ps);
            online.add(ps);
        }
        return new Snapshot(
                online.stream().filter(p -> !settings.isExcluded(p)).toList(),
                known.values().stream().filter(p -> !settings.isExcluded(p)).toList());
    }

    private String nameOf(UUID id) {
        String cached = names.get(id);
        if (cached != null) {
            return cached;
        }
        String name = Bukkit.getOfflinePlayer(id).getName();
        if (name == null) {
            return "player-" + id.toString().substring(0, 8);
        }
        names.put(id, name);
        return name;
    }

    // ----- announcing -----

    private void broadcast(String line) {
        Component message = Component.text(line, NamedTextColor.YELLOW);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(message);
        }
    }

    /** One announcement right now (manual). Replies to the sender when nothing is said. */
    private void announceNow(CommandSender sender) {
        Snapshot snap = snapshot();
        List<String> lines = announcement(snap);
        if (lines.isEmpty()) {
            if (!settings.includeOffline && snap.online().isEmpty()) {
                send(sender, "Nothing announced: nobody eligible is online, and offline players are switched off (include-offline is false).");
            } else if (!settings.includeOffline) {
                send(sender, "Nothing announced: no online player has enough tracked stats yet. Run /statfacts status.");
            } else {
                send(sender, "Nothing announced: no player has enough tracked stats yet. Run /statfacts status.");
            }
            return;
        }
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            send(sender, "Announced, but nobody is online to see it (chat only goes to online players).");
            return;
        }
        lines.forEach(this::broadcast);
    }

    // ----- milestone file -----

    private Map<String, Map<String, Long>> loadMilestones() {
        Map<String, Map<String, Long>> out = new HashMap<>();
        if (!milestonesFile.exists()) {
            return out;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(milestonesFile);
        for (String uuid : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(uuid);
            if (section == null) {
                continue;
            }
            Map<String, Long> perStat = new HashMap<>();
            for (String stat : section.getKeys(false)) {
                perStat.put(stat, section.getLong(stat));
            }
            out.put(uuid, perStat);
        }
        return out;
    }

    private void saveMilestones() {
        YamlConfiguration yaml = new YamlConfiguration();
        milestones.snapshot().forEach((uuid, perStat) ->
                perStat.forEach((stat, tier) -> yaml.set(uuid + "." + stat, tier)));
        try {
            getDataFolder().mkdirs();
            yaml.save(milestonesFile);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not save milestones.yml", e);
        }
    }

    // ----- commands (operators only: see plugin.yml) -----

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            send(sender, "Usage: /statfacts <" + String.join("|", SUBCOMMANDS) + ">");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> status(sender);
            case "reload" -> {
                loadSettings();
                send(sender, "Config reloaded.");
            }
            case "announce" -> announceNow(sender);
            case "set" -> set(sender, args);
            case "stat" -> stat(sender, args);
            case "stats" -> listStats(sender);
            case "exclude" -> exclude(sender, args);
            default -> send(sender, "Unknown subcommand. Usage: /statfacts <" + String.join("|", SUBCOMMANDS) + ">");
        }
        return true;
    }

    private void status(CommandSender sender) {
        Snapshot snap = snapshot();
        int files = source.readAll().size();
        long on = StatCatalog.ALL.stream().filter(settings::isOn).count();
        send(sender, "Running: " + (settings.enabled ? "yes" : "no (enabled is false)"));
        send(sender, "Announcing every " + settings.intervalSeconds + " s, next in "
                + Math.max(0, settings.intervalSeconds - secondsSinceAnnounce) + " s, once "
                + settings.minPlayers + " player(s) are online.");
        send(sender, "Comparisons: " + settings.multiChance + "% chance when 2+ players are online. Offline players: "
                + (settings.includeOffline ? "yes" : "no") + ".");
        send(sender, "Milestones: " + (settings.milestones ? "on" : "off") + ", checked every "
                + settings.milestoneSeconds + " s. Rungs: km " + rungs("km") + ", count " + rungs("count")
                + ", hours " + rungs("hours") + ", hearts " + rungs("hearts") + ".");
        send(sender, "Stats folder: " + source.folder() + " (" + files + " player files)");
        send(sender, "Online and eligible: " + snap.online().size() + ". Known players: " + snap.known().size()
                + ". Stats on: " + on + " of " + StatCatalog.ALL.size() + ".");
        if (!settings.excluded.isEmpty()) {
            send(sender, "Excluded: " + String.join(", ", settings.excluded));
        }
    }

    private String rungs(String key) {
        return java.util.Arrays.stream(settings.ladder(key))
                .mapToObj(Format::groups)
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private void set(CommandSender sender, String[] args) {
        if (args.length != 3 || !SET_KEYS.containsKey(args[1].toLowerCase(Locale.ROOT))) {
            send(sender, "Usage: /statfacts set <" + String.join("|", SET_KEYS.keySet()) + "> <value>");
            return;
        }
        String userKey = args[1].toLowerCase(Locale.ROOT);
        SetKey key = SET_KEYS.get(userKey);
        Object value;
        try {
            value = switch (key.type()) {
                case BOOL -> parseBool(args[2]);
                case MIN_ONE -> Math.max(1, Long.parseLong(args[2]));
                case NON_NEGATIVE -> Math.max(0, Integer.parseInt(args[2]));
                case PERCENT -> Math.min(100, Math.max(0, Integer.parseInt(args[2])));
            };
        } catch (IllegalArgumentException e) {
            send(sender, "'" + args[2] + "' is not a valid value for " + userKey + ".");
            return;
        }
        saveConfigValue(key.path(), value);
        send(sender, userKey + " is now " + value + ".");
    }

    private void stat(CommandSender sender, String[] args) {
        if (args.length != 3) {
            send(sender, "Usage: /statfacts stat <id> <on|off>");
            return;
        }
        StatDef d = findStat(args[1]);
        if (d == null) {
            send(sender, "No stat called '" + args[1] + "'. Run /statfacts stats to list them.");
            return;
        }
        boolean on = parseBool(args[2]);
        saveConfigValue("stats." + d.id(), on);
        send(sender, d.id() + " is now " + (on ? "on" : "off") + ".");
    }

    private void listStats(CommandSender sender) {
        List<String> off = StatCatalog.ALL.stream().filter(d -> !settings.isOn(d)).map(StatDef::id).toList();
        long on = StatCatalog.ALL.size() - off.size();
        send(sender, "Stats on: " + on + " of " + StatCatalog.ALL.size() + ". Change one with /statfacts stat <id> on|off.");
        if (!off.isEmpty()) {
            send(sender, "Off: " + String.join(", ", off));
        }
    }

    private void exclude(CommandSender sender, String[] args) {
        if (args.length != 3 || !(args[2].equalsIgnoreCase("add") || args[2].equalsIgnoreCase("remove"))) {
            send(sender, "Usage: /statfacts exclude <player> <add|remove>");
            return;
        }
        String name = args[1].toLowerCase(Locale.ROOT);
        List<String> list = new ArrayList<>(getConfig().getStringList("excluded-players"));
        list.removeIf(s -> s.equalsIgnoreCase(name));
        boolean add = args[2].equalsIgnoreCase("add");
        if (add) {
            list.add(name);
        }
        saveConfigValue("excluded-players", list);
        send(sender, add ? args[1] + " is excluded from StatFacts." : args[1] + " is included again.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (sub) {
                case "set" -> filter(List.copyOf(SET_KEYS.keySet()), args[1]);
                case "stat" -> filter(StatCatalog.ALL.stream().map(StatDef::id).toList(), args[1]);
                case "exclude" -> filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3) {
            return switch (sub) {
                case "stat" -> filter(List.of("on", "off"), args[2]);
                case "exclude" -> filter(List.of("add", "remove"), args[2]);
                case "set" -> {
                    SetKey key = SET_KEYS.get(args[1].toLowerCase(Locale.ROOT));
                    yield key != null && key.type() == SetType.BOOL ? filter(List.of("on", "off"), args[2]) : List.of();
                }
                default -> List.of();
            };
        }
        return List.of();
    }

    // ----- helpers -----

    private static List<String> filter(List<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    private static StatDef findStat(String id) {
        String wanted = id.toLowerCase(Locale.ROOT);
        return StatCatalog.ALL.stream().filter(d -> d.id().equals(wanted)).findFirst().orElse(null);
    }

    private static boolean parseBool(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes" -> true;
            case "off", "false", "no" -> false;
            default -> throw new IllegalArgumentException("not a boolean: " + text);
        };
    }

    private void send(CommandSender sender, String text) {
        sender.sendMessage(Component.text(PREFIX + text, NamedTextColor.YELLOW));
    }
}
