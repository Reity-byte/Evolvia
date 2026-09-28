package evolvia.world;

import java.util.List;
import java.util.Map;

/**
 * Rules of the tribe ({@code data/tribe.json}): gathering materials for the camp (phase 9g), the tribe itself,
 * its roles, morality and buildings (phase 9h).
 */
public final class Tribe {

    private Tribe() {
    }

    public record Config(Gathering gathering, Rules tribe, List<BuildingType> buildings) {

        /** Building type by id, or null. */
        public BuildingType building(String id) {
            for (BuildingType type : buildings) {
                if (type.id().equals(id)) {
                    return type;
                }
            }
            return null;
        }
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

    /**
     * @param ability           the species ability that founds the tribe (Tribe node)
     * @param speechAbility     herd members warn each other (Speech node)
     * @param alarmRadius       with speech, herd mates this close run too
     * @param joinRadius        herds of the people whose leader comes this close to the camp join the tribe
     * @param roleSeconds       how often roles are handed out
     * @param builderShare      share of adults who build while there is a site
     * @param maxBuilders       at most this many builders
     * @param buildScore        utility of building
     * @param siteRadius        new buildings go this far from the camp, [min, max]
     * @param moralityThreshold alignment beyond which the tribe feels its god is good or evil
     * @param goodBirthFactor   pause between young under a good god
     * @param evilBirthFactor   pause between young under an evil god
     * @param evilWorkFactor    work speed under an evil god
     * @param desertionPerMinute chance per member and minute to run away under a fully evil god
     */
    public record Rules(String ability, String speechAbility, float alarmRadius, float joinRadius, float roleSeconds,
                        float builderShare, int maxBuilders, float buildScore, float[] siteRadius, float moralityThreshold,
                        float goodBirthFactor, float evilBirthFactor, float evilWorkFactor, float desertionPerMinute) {
    }

    /**
     * @param cost         materials the tribe pays when it starts building
     * @param buildSeconds work of one builder
     * @param planFaith    faith the god pays for a plan
     * @param effect       {@code warmth}, {@code refuge}, {@code storage} or {@code faith}
     * @param radius       reach of warmth, size of the refuge
     * @param value        warmth added, people per shelter, storage multiplier, faith bonus
     */
    public record BuildingType(String id, String name, String description, Map<String, Float> cost, float buildSeconds,
                               float planFaith, String effect, float radius, float value) {
    }
}
