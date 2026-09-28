package evolvia.world;

import evolvia.core.Time;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Seasons, weather, disease settings and natural disasters (phase 9e, {@code data/nature.json}). Holds the
 * saved state (weather, fires, flood, blizzard, the next disaster); {@code NatureSystem} advances it.
 */
public final class Nature {

    public record Config(int seasonDays, List<Season> seasons, WeatherConfig weather, Disease disease,
                         Disasters disasters) {
    }

    /**
     * @param regrow      food regrowth multiplier
     * @param temperature added to every tile's temperature
     * @param thirst      thirst multiplier
     * @param weather     weights of {@link Weather} ids
     * @param disasters   chance per day of each {@link Disaster} id
     */
    public record Season(String id, String name, float regrow, float temperature, float thirst,
                         Map<String, Float> weather, Map<String, Float> disasters) {
        public float weatherWeight(Weather w) {
            return weather.getOrDefault(w.id, 0f);
        }

        public float disasterChance(Disaster d) {
            return disasters.getOrDefault(d.id, 0f);
        }
    }

    public record WeatherConfig(float minSeconds, float maxSeconds, Rain rain, Storm storm, Snow snow) {
    }

    public record Rain(float regrow, float temperature, float thirstReliefPerSecond) {
    }

    public record Storm(float strikeSeconds, float killRadius, float scareRadius, float scareSeconds, float igniteChance) {
    }

    public record Snow(float temperature) {
    }

    public record Disease(float spoilSeconds, float infectChance, float durationSeconds, float immuneSeconds,
                          float damagePerSecond, float energyDrainFactor, float spreadRadius, float spreadChancePerSecond) {
    }

    public record Disasters(int graceDays, Fire fire, Flood flood, Blizzard blizzard) {
    }

    /** @param flammable chance per second that fire spreads to a neighbouring tile of the biome */
    public record Fire(Map<String, Float> flammable, float burnSeconds, float rainFactor, float burntSeconds,
                       float damagePerSecond, float fearRadius, int maxTiles) {
    }

    public record Flood(float rise, float riseSeconds, float holdSeconds, float damagePerSecond) {
    }

    public record Blizzard(float minSeconds, float maxSeconds, float temperature) {
    }

    public enum Weather {
        CLEAR("clear", "jasno"), RAIN("rain", "déšť"), STORM("storm", "bouřka"), SNOW("snow", "sněžení");

        public final String id;
        public final String label;

        Weather(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public boolean wet() {
            return this == RAIN || this == STORM;
        }
    }

    public enum Disaster {
        FIRE("fire", "Požár! Oheň se šíří krajinou."),
        FLOOD("flood", "Záplava! Voda stoupá, bytosti prchají výš."),
        BLIZZARD("blizzard", "Vánice! Kdo není v úkrytu, mrzne.");

        public final String id;
        public final String announcement;

        Disaster(String id, String announcement) {
            this.id = id;
            this.announcement = announcement;
        }
    }

    private final Config config;
    private final WorldClock clock;
    private final Terrain terrain;

    // ---- saved state
    Weather weather = Weather.CLEAR;
    int weatherUntilTick;
    int nextStrikeTick;
    /** Ticks each tile keeps burning (0 = not burning). */
    final int[] burnLeft;
    /** Burnt ground until this tick (dark, nothing regrows). */
    final int[] burntUntil;
    /** Burning tiles in the order they caught fire (deterministic spreading). */
    final List<Integer> burning = new ArrayList<>();
    int floodStartTick = -1;
    int blizzardUntilTick;
    /** Last day for which a disaster was rolled. */
    int rolledDay;
    Disaster pendingDisaster;
    int pendingTick;
    /** Tiles burnt so far (never decreases; only makes {@link #hasBurntGround()} cheap). */
    private int burntTiles;

    // ---- not saved
    private final List<String> announcements = new ArrayList<>();

    public Nature(Config config, WorldClock clock, Terrain terrain) {
        this.config = config;
        this.clock = clock;
        this.terrain = terrain;
        burnLeft = new int[terrain.width() * terrain.depth()];
        burntUntil = new int[terrain.width() * terrain.depth()];
        weatherUntilTick = seconds(config.weather().minSeconds()); // the game starts in clear weather
    }

    public Config config() {
        return config;
    }

    public Terrain terrain() {
        return terrain;
    }

    public WorldClock clock() {
        return clock;
    }

    static int seconds(float seconds) {
        return Math.round(seconds * Time.TICKS_PER_SECOND);
    }

    // ---------------------------------------------------------------- seasons

    /** 0 = spring, 1 = summer, 2 = autumn, 3 = winter. */
    public int seasonIndex(double tick) {
        return ((clock.day(tick) - 1) / config.seasonDays()) % config.seasons().size();
    }

    public Season season(double tick) {
        return config.seasons().get(seasonIndex(tick));
    }

    /** Year number, starting at 1. */
    public int year(double tick) {
        return (clock.day(tick) - 1) / (config.seasonDays() * config.seasons().size()) + 1;
    }

    /** How far into the current season, 0..1. */
    public float seasonProgress(double tick) {
        double days = tick / (clock.settings().dayLengthSeconds() * Time.TICKS_PER_SECOND) + clock.settings().startTimeOfDay();
        double inSeason = days % config.seasonDays();
        return (float) (inSeason / config.seasonDays());
    }

    public boolean isWinter(double tick) {
        return "winter".equals(season(tick).id());
    }

    // ---------------------------------------------------------------- weather

    public Weather weather() {
        return weather;
    }

    public boolean blizzard(int tick) {
        return tick < blizzardUntilTick;
    }

    /** Added to the temperature everywhere; {@code sheltered} creatures feel only the season. */
    public float temperatureOffset(int tick, boolean sheltered) {
        float offset = season(tick).temperature();
        if (sheltered) {
            return offset;
        }
        if (weather.wet()) {
            offset += config.weather().rain().temperature();
        } else if (weather == Weather.SNOW) {
            offset += config.weather().snow().temperature();
        }
        if (blizzard(tick)) {
            offset += config.disasters().blizzard().temperature();
        }
        return offset;
    }

    public float regrowMultiplier(int tick) {
        return season(tick).regrow() * (weather.wet() ? config.weather().rain().regrow() : 1f);
    }

    public float thirstMultiplier(int tick) {
        return season(tick).thirst();
    }

    /** Thirst relief per tick from rain (nothing in dry weather). */
    public float rainRelief() {
        return weather.wet() ? config.weather().rain().thirstReliefPerSecond() / Time.TICKS_PER_SECOND : 0f;
    }

    /**
     * How white the ground is, 0..1 (rendering): snow falls in winter and melts in early spring;
     * snowing and blizzards add to it.
     */
    public float snowCover(double tick) {
        float progress = seasonProgress(tick);
        float cover = 0f;
        if (isWinter(tick)) {
            cover = Math.min(0.7f, progress * 3f);
        } else if ("spring".equals(season(tick).id()) && year(tick) > 1) {
            cover = Math.max(0f, 0.7f - progress * 4f);
        }
        if (weather == Weather.SNOW) {
            cover += 0.15f;
        }
        if (blizzard((int) tick)) {
            cover = 1f;
        }
        return Math.min(1f, cover);
    }

    /** Clouds dim the light, 0..1 (rendering). */
    public float overcast(double tick) {
        if (blizzard((int) tick)) {
            return 0.85f;
        }
        return switch (weather) {
            case CLEAR -> 0f;
            case RAIN -> 0.45f;
            case STORM -> 0.75f;
            case SNOW -> 0.35f;
        };
    }

    // ---------------------------------------------------------------- fire

    public float flammability(int tx, int tz) {
        return config.disasters().fire().flammable().getOrDefault(terrain.biome(tx, tz).id(), 0f);
    }

    int index(int tx, int tz) {
        return tz * terrain.width() + tx;
    }

    public boolean isBurning(int tx, int tz) {
        return terrain.inBounds(tx, tz) && burnLeft[index(tx, tz)] > 0;
    }

    /** Some ground was burnt recently (cheap check before per-tile lookups). */
    public boolean hasBurntGround() {
        return burntTiles > 0 || !burning.isEmpty();
    }

    /** Burning now or burnt recently: nothing grows there. */
    public boolean isBurnt(int tx, int tz, int tick) {
        return terrain.inBounds(tx, tz) && (burntUntil[index(tx, tz)] > tick || burnLeft[index(tx, tz)] > 0);
    }

    /** Burning tiles, as {@code tz * width + tx}, in ignition order. */
    public List<Integer> burningTiles() {
        return burning;
    }

    /** How scorched a tile looks, 0..1 (rendering): 1 while burning, fading out at the end of the burnt time. */
    public float scorch(int index, int tick) {
        if (burnLeft[index] > 0) {
            return 1f;
        }
        int left = burntUntil[index] - tick;
        if (left <= 0) {
            return 0f;
        }
        return Math.min(0.85f, left / (seconds(config.disasters().fire().burntSeconds()) * 0.3f));
    }

    public int tileCount() {
        return burnLeft.length;
    }

    /** Burnt ground (for rendering), as tile indices. */
    public void forEachBurnt(int tick, IntConsumer action) {
        for (int i = 0; i < burntUntil.length; i++) {
            if (burntUntil[i] > tick) {
                action.accept(i);
            }
        }
    }

    /** Sets a tile on fire if it can burn. @return true if it caught fire */
    public boolean ignite(int tx, int tz, int tick) {
        if (!terrain.inBounds(tx, tz) || !terrain.isPassable(tx, tz) || flammability(tx, tz) <= 0f) {
            return false;
        }
        int i = index(tx, tz);
        if (burnLeft[i] > 0 || burntUntil[i] > tick || burning.size() >= config.disasters().fire().maxTiles()) {
            return false;
        }
        burnLeft[i] = seconds(config.disasters().fire().burnSeconds());
        burning.add(i);
        return true;
    }

    /** Puts out every fire within the radius (the god's rain). */
    public void extinguish(float x, float z, float radius, int tick) {
        for (int n = burning.size() - 1; n >= 0; n--) {
            int i = burning.get(n);
            float cx = i % terrain.width() + 0.5f;
            float cz = i / terrain.width() + 0.5f;
            if (Math.hypot(cx - x, cz - z) <= radius) {
                burnOut(n, tick);
            }
        }
    }

    void burnOut(int n, int tick) {
        int i = burning.remove(n);
        burnLeft[i] = 0;
        burntTiles++;
        burntUntil[i] = tick + seconds(config.disasters().fire().burntSeconds());
    }

    // ---------------------------------------------------------------- flood

    /** How far the sea has risen now (0 without a flood). */
    public float floodLevel(double tick) {
        if (floodStartTick < 0) {
            return 0f;
        }
        Flood flood = config.disasters().flood();
        double t = (tick - floodStartTick) / Time.TICKS_PER_SECOND;
        if (t < 0) {
            return 0f;
        }
        if (t < flood.riseSeconds()) {
            return (float) (flood.rise() * t / flood.riseSeconds());
        }
        t -= flood.riseSeconds();
        if (t < flood.holdSeconds()) {
            return flood.rise();
        }
        t -= flood.holdSeconds();
        return (float) Math.max(0.0, flood.rise() * (1.0 - t / flood.riseSeconds()));
    }

    public boolean floodOver(int tick) {
        Flood flood = config.disasters().flood();
        return floodStartTick >= 0 && tick - floodStartTick > seconds(2 * flood.riseSeconds() + flood.holdSeconds());
    }

    public boolean flooding() {
        return floodStartTick >= 0;
    }

    /** Land under the risen sea. */
    public boolean isFlooded(int tx, int tz, int tick) {
        float level = floodLevel(tick);
        return level > 0f && terrain.inBounds(tx, tz) && terrain.isPassable(tx, tz)
                && terrain.tileHeight(tx, tz) < terrain.seaLevel() + level;
    }

    // ---------------------------------------------------------------- announcements

    public void announceDisaster(Disaster disaster) {
        announcements.add(disaster.announcement);
    }

    /** Messages for the player since the last call (disasters). */
    public List<String> takeAnnouncements() {
        List<String> list = List.copyOf(announcements);
        announcements.clear();
        return list;
    }

    // ---------------------------------------------------------------- state changes (NatureSystem)

    public int weatherUntilTick() {
        return weatherUntilTick;
    }

    public int nextStrikeTick() {
        return nextStrikeTick;
    }

    public void setNextStrike(int tick) {
        nextStrikeTick = tick;
    }

    public Disaster pendingDisaster() {
        return pendingDisaster;
    }

    public int pendingTick() {
        return pendingTick;
    }

    public void clearPending() {
        pendingDisaster = null;
    }

    public int rolledDay() {
        return rolledDay;
    }

    public void setRolledDay(int day) {
        rolledDay = day;
    }

    public void startFlood(int tick) {
        floodStartTick = tick;
    }

    public void endFlood() {
        floodStartTick = -1;
    }

    public void startBlizzard(int untilTick) {
        blizzardUntilTick = untilTick;
        weather = Weather.SNOW;
        weatherUntilTick = Math.max(weatherUntilTick, untilTick);
    }

    /** Every burning tile burns for {@code ticks} more; burnt-out tiles become burnt ground. */
    public void burnDown(int ticks, int tick) {
        for (int n = burning.size() - 1; n >= 0; n--) {
            int i = burning.get(n);
            burnLeft[i] -= ticks;
            if (burnLeft[i] <= 0) {
                burnOut(n, tick);
            }
        }
    }

    // ---------------------------------------------------------------- save games

    public record State(String weather, int weatherUntilTick, int nextStrikeTick, List<int[]> burning, List<int[]> burnt,
                        int floodStartTick, int blizzardUntilTick, int rolledDay, String pendingDisaster, int pendingTick) {
    }

    public State state(int tick) {
        List<int[]> fires = new ArrayList<>();
        for (int i : burning) {
            fires.add(new int[]{i, burnLeft[i]});
        }
        List<int[]> burnt = new ArrayList<>();
        for (int i = 0; i < burntUntil.length; i++) {
            if (burntUntil[i] > tick) {
                burnt.add(new int[]{i, burntUntil[i]});
            }
        }
        return new State(weather.name(), weatherUntilTick, nextStrikeTick, fires, burnt, floodStartTick, blizzardUntilTick,
                rolledDay, pendingDisaster != null ? pendingDisaster.name() : null, pendingTick);
    }

    public void restore(State s) {
        weather = Weather.valueOf(s.weather());
        weatherUntilTick = s.weatherUntilTick();
        nextStrikeTick = s.nextStrikeTick();
        burning.clear();
        Arrays.fill(burnLeft, 0);
        Arrays.fill(burntUntil, 0);
        for (int[] fire : s.burning()) {
            burnLeft[fire[0]] = fire[1];
            burning.add(fire[0]);
        }
        for (int[] b : s.burnt()) {
            burntUntil[b[0]] = b[1];
        }
        burntTiles = s.burnt().size();
        floodStartTick = s.floodStartTick();
        blizzardUntilTick = s.blizzardUntilTick();
        rolledDay = s.rolledDay();
        pendingDisaster = s.pendingDisaster() != null ? Disaster.valueOf(s.pendingDisaster()) : null;
        pendingTick = s.pendingTick();
    }

    // ---------------------------------------------------------------- tests / debug

    /** Starts a disaster at the given tick (tests, debug). */
    public void schedule(Disaster disaster, int tick) {
        pendingDisaster = disaster;
        pendingTick = tick;
    }

    /** Sets the weather until the given tick (tests, debug). */
    public void setWeather(Weather weather, int untilTick) {
        this.weather = weather;
        this.weatherUntilTick = untilTick;
    }
}
