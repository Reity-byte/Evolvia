package evolvia.god;

/**
 * The player's faith (the currency of god powers, DESIGN.md §9) and the god's alignment on the
 * good / evil axis. Faith comes from believing creatures; alignment moves with every power used
 * (kind powers towards good, cruel ones towards evil). Alignment has no effect on the game yet.
 */
public final class Faith {

    private float points;
    private float earned;
    private float perMinute;
    private int believers;
    private float alignment;
    private int kindActs;
    private int cruelActs;

    public Faith(float starting) {
        points = starting;
    }

    /** Faith available to spend. */
    public float points() {
        return points;
    }

    /** Faith earned since the start (without the starting amount). */
    public float earned() {
        return earned;
    }

    /** Current income per game minute. */
    public float perMinute() {
        return perMinute;
    }

    /** Believing creatures at the last income update. */
    public int believers() {
        return believers;
    }

    /** -1 (evil) .. 1 (good). */
    public float alignment() {
        return alignment;
    }

    public int kindActs() {
        return kindActs;
    }

    public int cruelActs() {
        return cruelActs;
    }

    public void add(float amount) {
        if (amount > 0) {
            points += amount;
            earned += amount;
        }
    }

    public boolean canAfford(float cost) {
        return points >= cost;
    }

    /** Pays for a power; returns false (and pays nothing) if there is not enough faith. */
    public boolean spend(float cost) {
        if (points < cost) {
            return false;
        }
        points -= cost;
        return true;
    }

    /** Records a used power's effect on the alignment. */
    public void recordAct(float alignmentShift) {
        alignment = Math.clamp(alignment + alignmentShift, -1f, 1f);
        if (alignmentShift > 0) {
            kindActs++;
        } else if (alignmentShift < 0) {
            cruelActs++;
        }
    }

    /** Called by the faith income once per game second. */
    public void setIncome(int believers, float perMinute) {
        this.believers = believers;
        this.perMinute = perMinute;
    }
}
