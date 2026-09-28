package evolvia.world;

/** Rules of the tribe's work ({@code data/tribe.json}): gathering materials for the camp (phase 9g). */
public final class Tribe {

    private Tribe() {
    }

    public record Config(Gathering gathering) {
    }

    /**
     * @param ability      the ability that lets adults gather (from the Tools node)
     * @param stockCap     a camp stores at most this much of each material
     * @param workSeconds  time to take one unit from a tree or rock
     * @param radius       materials are gathered within this distance of the camp
     * @param score        utility of gathering (above wandering, below needs)
     * @param deliverScore utility of carrying a load home
     * @param maxNeed      nobody gathers while hunger or thirst is above this
     */
    public record Gathering(String ability, float stockCap, float workSeconds, float radius, float score,
                            float deliverScore, float maxNeed) {
    }
}
