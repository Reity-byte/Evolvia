package evolvia.components;

/** A load a creature carries to its herd's camp (phase 9g): one material and how much of it. */
public final class Carrying {

    public String material;
    public float amount;

    public Carrying(String material, float amount) {
        this.material = material;
        this.amount = amount;
    }
}
