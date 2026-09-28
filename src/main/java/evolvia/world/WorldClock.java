package evolvia.world;

import evolvia.core.Time;

/**
 * Day and night (DESIGN.md §11, 9d), computed from the tick alone (nothing to save): time of day 0..1
 * (0 = midnight, 0.25 = sunrise, 0.5 = noon, 0.75 = sunset).
 */
public final class WorldClock {

    /** Night lasts from this time of day ... */
    public static final float NIGHT_START = 0.78f;
    /** ... until this one. */
    public static final float NIGHT_END = 0.22f;
    /** Herds look for a refuge from this time of day (dusk) until the night ends. */
    public static final float DUSK = 0.68f;

    private final WorldConfig.TimeSettings settings;

    public WorldClock(WorldConfig.TimeSettings settings) {
        this.settings = settings;
    }

    public WorldConfig.TimeSettings settings() {
        return settings;
    }

    private double days(double tick) {
        return tick / (settings.dayLengthSeconds() * Time.TICKS_PER_SECOND) + settings.startTimeOfDay();
    }

    /** Time of day 0..1 at a (fractional) tick. */
    public float timeOfDay(double tick) {
        double days = days(tick);
        return (float) (days - Math.floor(days));
    }

    /** Day number, starting at 1. */
    public int day(double tick) {
        return (int) Math.floor(days(tick)) + 1;
    }

    public boolean isNight(double tick) {
        float t = timeOfDay(tick);
        return t >= NIGHT_START || t < NIGHT_END;
    }

    /** Evening or night: herds should be at a refuge. */
    public boolean isShelterTime(double tick) {
        float t = timeOfDay(tick);
        return t >= DUSK || t < NIGHT_END;
    }

    /** 0 by day, 1 in the middle of the night, smooth in between (for the night cold). */
    public float nightness(double tick) {
        float elevation = sunElevation(timeOfDay(tick));
        return Math.clamp(-elevation * 2f + 0.2f, 0f, 1f);
    }

    /** Sine of the sun's height: 1 at noon, -1 at midnight. */
    public static float sunElevation(float timeOfDay) {
        return (float) Math.sin((timeOfDay - 0.25) * 2 * Math.PI);
    }
}
