package evolvia.components;

/** Reproduction state of a creature. */
public final class Reproduction {

    /** Tick from which the creature may reproduce again (cooldown after the last mating). */
    public int readyAtTick;
    /** Number of offspring this creature has had. */
    public int offspring;
}
