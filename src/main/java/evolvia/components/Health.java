package evolvia.components;

/** Hit points; the creature dies at 0. */
public final class Health {

    public float hp;
    public float maxHp;

    public Health(float maxHp) {
        this.hp = maxHp;
        this.maxHp = maxHp;
    }
}
