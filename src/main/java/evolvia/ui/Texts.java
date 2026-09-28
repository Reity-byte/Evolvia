package evolvia.ui;

import evolvia.ai.ActionType;
import evolvia.evolution.Effect;
import evolvia.evolution.Species;
import evolvia.evolution.Stat;

import java.util.Locale;

/** Czech UI texts for game concepts (branches, actions, stats, effects). */
public final class Texts {

    private Texts() {
    }

    public static String branch(String branch) {
        return switch (branch) {
            case "body" -> "Tělo";
            case "diet" -> "Potrava";
            case "adaptation" -> "Adaptace";
            case "mind" -> "Mysl";
            default -> branch;
        };
    }

    public static String action(ActionType type) {
        if (type == null) {
            return "Nic nedělá";
        }
        return switch (type) {
            case WANDER -> "Bloudí";
            case SEEK_FOOD -> "Hledá jídlo";
            case EAT -> "Jí";
            case SEEK_WATER -> "Hledá vodu";
            case DRINK -> "Pije";
            case SLEEP -> "Spí";
            case SEEK_MATE -> "Hledá partnera";
            case FLEE -> "Utíká v hrůze";
            case FOLLOW_LEADER -> "Jde za vůdcem";
        };
    }

    public static String status(Species.NodeStatus status) {
        return switch (status) {
            case UNLOCKED -> "Odemčeno";
            case AVAILABLE -> "Dostupné – klikni pro odemčení";
            case LOCKED -> "Zamčeno";
            case EXCLUDED -> "Vyloučeno";
        };
    }

    public static String ability(String ability) {
        return switch (ability) {
            case "swim" -> "plavání";
            case "memory" -> "paměť";
            case "groups" -> "stáda";
            case "hands" -> "šikovné ruce";
            default -> ability;
        };
    }

    public static String part(String part) {
        return switch (part) {
            case "legs" -> "nohy";
            case "body" -> "tělo";
            case "skin" -> "kůže";
            case "fur" -> "srst";
            case "feet" -> "chodidla";
            case "teeth" -> "zuby";
            case "eyes" -> "oči";
            case "ears" -> "uši";
            case "head" -> "hlava";
            case "belly" -> "břicho";
            case "posture" -> "postoj";
            case "hands" -> "ruce";
            case "face" -> "tvář";
            default -> part;
        };
    }

    public static String stat(Stat stat) {
        return switch (stat) {
            case SIZE -> "Velikost";
            case SPEED -> "Rychlost";
            case SIGHT -> "Dohled";
            case MAX_HEALTH -> "Zdraví";
            case HUNGER_RATE -> "Hladovění";
            case THIRST_RATE -> "Žíznivost";
            case ENERGY_DRAIN -> "Únava";
            case LIFESPAN -> "Délka života";
            case REPRODUCTION_COOLDOWN -> "Pauza mezi mláďaty";
            case LITTER_SIZE -> "Počet mláďat";
            case COMFORT_MIN -> "Spodní hranice teploty";
            case COMFORT_MAX -> "Horní hranice teploty";
            case PLANT_NUTRITION -> "Výživnost rostlin";
            case MEAT_NUTRITION -> "Výživnost masa";
        };
    }

    /** One effect as a line of text, e.g. "Rychlost +20 %". */
    public static String effect(Effect effect) {
        return switch (effect) {
            case Effect.StatMul mul -> mul.value() == 0f
                    ? stat(mul.stat()) + " ×0 (nejí)"
                    : stat(mul.stat()) + " " + percent(mul.value() - 1f);
            case Effect.StatAdd add -> switch (add.stat()) {
                case COMFORT_MIN -> add.value() < 0 ? "Snese chlad o " + number(-add.value()) + " víc"
                        : "Snese chlad o " + number(add.value()) + " míň";
                case COMFORT_MAX -> add.value() > 0 ? "Snese horko o " + number(add.value()) + " víc"
                        : "Snese horko o " + number(-add.value()) + " míň";
                default -> stat(add.stat()) + " " + (add.value() >= 0 ? "+" : "−") + number(Math.abs(add.value()));
            };
            case Effect.UnlockAbility ability -> "Nová schopnost: " + ability(ability.ability());
            case Effect.UnlockAction action -> "Nová akce: " + action.action();
            case Effect.Visual visual -> "Vzhled: " + part(visual.part());
        };
    }

    /** Relative change as a signed percentage, e.g. "+20 %" or "−25 %". */
    public static String percent(float change) {
        long rounded = Math.round(change * 100f);
        return (rounded >= 0 ? "+" : "−") + Math.abs(rounded) + " %";
    }

    public static String number(float value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
