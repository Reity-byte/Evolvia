package evolvia.components;

/**
 * Individual variation around the species stats (DESIGN.md §7.3): each gene is a multiplier near 1
 * (within the species' genome variation). Offspring get the parents' average plus a small mutation.
 * Not the main evolution mechanism (that is the evolution tree), but gives diversity.
 */
public final class Genome {

    /** Body size multiplier; bigger creatures also need more food. */
    public float size = 1f;
    /** Walking speed multiplier. */
    public float speed = 1f;
    /** Color shade multiplier (visual only). */
    public float tint = 1f;
    /** 0 for the starting population, otherwise one more than the older parent. */
    public int generation;
}
