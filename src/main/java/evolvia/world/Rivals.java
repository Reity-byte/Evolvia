package evolvia.world;

import evolvia.evolution.Species;

import java.util.List;

/**
 * The rival people (phase 11, {@code data/rivals.json}): another species of "beast people" with its own look, living
 * far from the player's people and evolving by itself along a fixed plan.
 */
public final class Rivals {

    private Rivals() {
    }

    /**
     * One step of the rival's evolution plan: at game minute {@code minute} it gets the node {@code node}.
     */
    public record Step(float minute, String node) {
    }

    /**
     * @param species  the rival species (its own look from {@code start})
     * @param start    evolution nodes it has from the beginning (its look)
     * @param plan     further nodes over time, ordered by minute
     * @param herds    herds at the start
     * @param herdSize members per herd [min, max]
     * @param tribeMinute the rival founds its tribe at this game minute at the earliest, and only once the player has one
     * @param buildings buildings its tribe knows
     */
    public record Config(Species species, List<String> start, List<Step> plan, int herds, int[] herdSize,
                         float tribeMinute, List<String> buildings) {
    }
}
