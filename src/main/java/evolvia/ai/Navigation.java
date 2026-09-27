package evolvia.ai;

import evolvia.evolution.Species;
import evolvia.world.Terrain;

/**
 * Walkable space per movement ability: land only, or land plus shallow water for species with the
 * {@value #SWIM} ability. Each has its own {@link Pathfinder} (and land regions: shallow water can
 * connect islands for swimmers).
 */
public final class Navigation {

    /** Ability that lets a species cross shallow water. */
    public static final String SWIM = "swim";
    /** How deep below the water surface a swimming creature floats (world units). */
    public static final float SWIM_DEPTH = 0.3f;

    private final Terrain terrain;
    private final Pathfinder land;
    private final Pathfinder swim;

    /**
     * @param shallowDepth water tiles at most this deep below sea level can be swum
     */
    public Navigation(Terrain terrain, float shallowDepth) {
        this.terrain = terrain;
        this.land = new Pathfinder(terrain, terrain::isPassable);
        this.swim = new Pathfinder(terrain, (tx, tz) -> terrain.isPassable(tx, tz)
                || (terrain.inBounds(tx, tz) && terrain.tileHeight(tx, tz) >= terrain.seaLevel() - shallowDepth));
    }

    /** Pathfinder for the way a species moves. */
    public Pathfinder forSpecies(Species species) {
        return species.hasAbility(SWIM) ? swim : land;
    }

    public Pathfinder land() {
        return land;
    }

    public Pathfinder swim() {
        return swim;
    }

    /** Height a creature stands at: the ground, or just below the surface where it swims. */
    public float groundHeight(float x, float z) {
        return groundHeight(terrain, x, z);
    }

    public static float groundHeight(Terrain terrain, float x, float z) {
        return Math.max(terrain.heightAt(x, z), terrain.seaLevel() - SWIM_DEPTH);
    }
}
