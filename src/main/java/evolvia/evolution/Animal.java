package evolvia.evolution;

import java.util.List;
import java.util.Map;

/**
 * What makes a species wild game rather than the player's people (phase 9f, {@code data/animals.json}):
 * its role, whom it hunts, when it is active, how it is placed in a new world and how it looks.
 *
 * @param role      {@link #PREY} or {@link #PREDATOR}
 * @param prey      ids of the species it hunts (empty for prey)
 * @param nocturnal sleeps by day, active at night
 * @param herds     herds placed in a new world
 * @param herdSize  smallest and largest herd
 * @param biomes    where herds are placed
 * @param visuals   look (like the visual effects of evolution nodes)
 */
public record Animal(String role, List<String> prey, boolean nocturnal, int herds, int[] herdSize, List<String> biomes,
                     Map<String, String> visuals) {

    public static final String PREY = "prey";
    public static final String PREDATOR = "predator";

    public boolean isPrey() {
        return PREY.equals(role);
    }

    public boolean isPredator() {
        return PREDATOR.equals(role);
    }
}
