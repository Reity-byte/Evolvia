package evolvia.evolution;

/** What unlocking an evolution node does (DESIGN.md §7.2). */
public sealed interface Effect {

    /** Adds {@code value} to a stat (applied before all multiplications). */
    record StatAdd(Stat stat, float value) implements Effect {
    }

    /** Multiplies a stat by {@code value} (applied after all additions). */
    record StatMul(Stat stat, float value) implements Effect {
    }

    /** Gives the species an ability, e.g. {@code swim} or {@code memory}. */
    record UnlockAbility(String ability) implements Effect {
    }

    /** Changes a visual body part (drawn by the procedural creature mesh, {@code CreatureMeshBuilder}). */
    record Visual(String part, String variant) implements Effect {
    }

    /** Makes an AI action available (actions from later phases, e.g. {@code Hunt}). */
    record UnlockAction(String action) implements Effect {
    }
}
