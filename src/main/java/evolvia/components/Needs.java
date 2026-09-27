package evolvia.components;

/** Basic needs, each 0..1. */
public final class Needs {

    /** 0 = full, 1 = starving (takes health). */
    public float hunger;
    /** 0 = quenched, 1 = dying of thirst (takes health). */
    public float thirst;
    /** 1 = rested, 0 = exhausted. */
    public float energy = 1f;
    /** How far the local temperature is outside the species comfort range (negative = cold, positive = hot, 0 = fine). */
    public float exposure;
    /** Set while the creature sleeps: energy recovers, hunger and thirst grow slower. */
    public boolean sleeping;
}
