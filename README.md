<h1 align="center">StatFacts</h1>

<p align="center">
  Random player stat facts and milestones for Paper servers, read live from each player's stats file.
</p>

<p align="center">
  <img alt="Paper 26.3" src="https://img.shields.io/badge/Paper-26.3-blue">
  <img alt="Java 25" src="https://img.shields.io/badge/Java-25-orange">
  <img alt="License: MIT" src="https://img.shields.io/badge/License-MIT-green">
</p>

---

StatFacts announces random stats in chat, compares players who are online, and celebrates milestones.

```text
Did you know that Steve has walked 15.1 km?
Server trivia: Alex has traded with villagers 5 times!
Congratulations to Alex, who has now walked 1,000 km!
```

New players are picked up automatically as soon as their stats file exists. It is a port of the StatFacts datapack.

The stat list lives in [`StatCatalog.java`](src/main/java/statfacts/StatCatalog.java).

## Commands

All commands need the `statfacts.admin` permission, which operators have by default.

| Command | What it does |
|---|---|
| `/statfacts status` | Show settings, milestone rungs and player counts |
| `/statfacts announce` | Announce one fact now |
| `/statfacts set <key> <value>` | Change a setting |
| `/statfacts stat <id> on\|off` | Turn one stat on or off, including its milestones |
| `/statfacts stats` | List the stats that are off |
| `/statfacts exclude <player> add\|remove` | Keep a player out of announcements |
| `/statfacts reload` | Re-read `config.yml` |

**Setting keys:** `enabled`, `interval` (seconds), `min-players`, `multi-chance` (0-100), `include-offline`, `milestones`, `milestone-seconds`.

> Commands rewrite `config.yml` and drop its comments. Edit the file by hand first, then run `reload`.

## Milestones

A milestone fires when a stat crosses a rung in its ladder, measured in the stat's own unit.

| Ladder | Default rungs | Used by |
|---|---|---|
| `km` | 100, 1,000, 10,000 | walking, swimming, boats |
| `count` | 1,000, 10,000, 100,000 | items used, mobs killed |
| `hours` | 100, 1,000 | playtime, sneaking |
| `hearts` | 1,000, 10,000 | damage dealt, taken, blocked |

The top rung says "Incredible!". Edit the rungs in `config.yml`. Progress is saved to `plugins/StatFacts/milestones.yml`. A stat's first check records the current tier silently, so old milestones don't flood chat.

## Tests

The tests need a folder of stat files with at least two players, such as a copy of a server's `players/stats`. Don't commit player data.

```bash
./build.sh
GSON=$(find ~/.gradle/caches -name 'gson-2.14.0.jar' | head -n1)
mkdir -p build/test
java -m jdk.compiler/com.sun.tools.javac.Main -cp build/classes:$GSON -d build/test src/test/java/statfacts/SelfTest.java
java -cp build/classes:$GSON:build/test statfacts.SelfTest path/to/players/stats
```

## License

[MIT](LICENSE) © willbara
