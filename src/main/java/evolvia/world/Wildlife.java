package evolvia.world;

import evolvia.evolution.Species;

import java.util.List;

/** Wild game (phase 9f, {@code data/animals.json}): its species and the rules of hunting, shared by all hunters. */
public final class Wildlife {

    private Wildlife() {
    }

    /**
     * @param hungerThreshold a carnivore hunts from this hunger on
     * @param chaseSeconds    gives up a chase after this long
     * @param score           utility of hunting at the threshold (grows with hunger)
     * @param nightBonus      extra utility for nocturnal hunters at night
     * @param scareRadius     prey within this distance of a hunter chasing it runs
     * @param scareSeconds    how long it runs
     * @param fleeDistance    how far it runs
     * @param biteFactor      a hunter's bite does this many times the damage of a fight
     */
    public record Hunting(float hungerThreshold, float chaseSeconds, float score, float nightBonus, float scareRadius,
                          float scareSeconds, float fleeDistance, float biteFactor) {
    }

    public record Config(Hunting hunting, List<Species> species) {

        /** Species by id, or null. */
        public Species byId(String id) {
            for (Species s : species) {
                if (s.id().equals(id)) {
                    return s;
                }
            }
            return null;
        }
    }
}
