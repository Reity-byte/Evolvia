package evolvia.systems;

import evolvia.components.Genome;
import evolvia.components.Health;
import evolvia.components.Needs;
import evolvia.components.Sick;
import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;
import evolvia.evolution.SpeciesDefinition;
import evolvia.evolution.SpeciesDefinition.Climate;
import evolvia.evolution.SpeciesDefinition.NeedRates;
import evolvia.world.Nature;
import evolvia.world.Refuges;
import evolvia.world.Terrain;
import evolvia.world.WorldClock;

/**
 * Grows hunger and thirst, drains energy while awake and restores it while sleeping.
 * Climate: outside the species' comfort range cold makes creatures hungrier and heat thirstier,
 * and far outside it they lose health. A creature at full hunger or thirst loses health;
 * otherwise health slowly regenerates. Rates come from the creature's (evolved) species stats.
 */
public final class NeedsSystem implements GameSystem {

    private final Terrain terrain;
    private final WorldClock clock;
    private final Refuges refuges;
    private final Nature nature;

    /** Without day and night (tests). */
    public NeedsSystem(Terrain terrain) {
        this(terrain, null, null, null);
    }

    /** Without seasons, weather and disease (tests). */
    public NeedsSystem(Terrain terrain, WorldClock clock, Refuges refuges) {
        this(terrain, clock, refuges, null);
    }

    /**
     * @param clock   the night is colder, except in a refuge (null = no night)
     * @param refuges sleeping in a refuge restores energy and health faster (null = none)
     */
    public NeedsSystem(Terrain terrain, WorldClock clock, Refuges refuges, Nature nature) {
        this.terrain = terrain;
        this.clock = clock;
        this.refuges = refuges;
        this.nature = nature;
    }

    @Override
    public void update(EcsWorld world, int tick) {
        ComponentStore<Needs> needsStore = world.store(Needs.class);
        ComponentStore<SpeciesRef> species = world.store(SpeciesRef.class);
        ComponentStore<Health> healths = world.store(Health.class);
        ComponentStore<Genome> genomes = world.store(Genome.class);
        ComponentStore<Transform> transforms = world.store(Transform.class);
        ComponentStore<Sick> sickStore = world.store(Sick.class);
        float thirstFactor = nature != null ? nature.thirstMultiplier(tick) : 1f;
        float rainRelief = nature != null ? nature.rainRelief() : 0f;
        float drainFactor = nature != null ? nature.config().disease().energyDrainFactor() : 1f;
        float diseaseDamage = nature != null ? SpeciesDefinition.perTick(nature.config().disease().damagePerSecond()) : 0f;

        for (int i = 0; i < needsStore.size(); i++) {
            int entity = needsStore.entityAt(i);
            SpeciesRef ref = species.get(entity);
            if (ref == null) {
                continue;
            }
            Needs needs = needsStore.componentAt(i);
            SpeciesDefinition stats = ref.stats();
            NeedRates rates = stats.needs();

            Transform transform = transforms.get(entity);
            Climate climate = stats.climate();
            boolean sheltered = refuges != null && transform != null && refuges.at(transform.position.x, transform.position.z) != null;
            float temperature = transform != null ? temperatureAt(transform) : 0.5f;
            if (clock != null && !sheltered) {
                temperature -= clock.settings().nightCooling() * clock.nightness(tick);
            }
            if (nature != null) {
                temperature += nature.temperatureOffset(tick, sheltered);
            }
            Sick sick = sickStore.get(entity);
            boolean ill = sick != null && sick.isActive(tick);
            needs.exposure = transform != null ? climate.exposure(temperature) : 0f;
            float coldFactor = 1f + climate.needFactorPerUnit() * Math.max(0f, -needs.exposure);
            float heatFactor = 1f + climate.needFactorPerUnit() * Math.max(0f, needs.exposure);

            float factor = needs.sleeping ? rates.sleepingNeedFactor() : 1f;
            Genome genome = genomes.get(entity);
            float metabolism = genome != null ? genome.size : 1f; // bigger bodies need more food
            needs.hunger = Math.min(1f, needs.hunger
                    + SpeciesDefinition.perTick(rates.hungerPerSecond()) * factor * metabolism * coldFactor);
            needs.thirst = Math.min(1f, needs.thirst
                    + SpeciesDefinition.perTick(rates.thirstPerSecond()) * factor * heatFactor * thirstFactor);
            if (rainRelief > 0f && !sheltered) {
                needs.thirst = Math.max(0f, needs.thirst - rainRelief);
            }
            float shelter = sheltered && needs.sleeping ? refuges.config().sleepEnergyFactor() : 1f;
            if (needs.sleeping) {
                needs.energy = Math.min(1f, needs.energy + SpeciesDefinition.perTick(rates.energyRecoverPerSecond()) * shelter);
            } else {
                needs.energy = Math.max(0f, needs.energy
                        - SpeciesDefinition.perTick(rates.energyDrainPerSecond()) * (ill ? drainFactor : 1f));
            }

            Health health = healths.get(entity);
            if (health == null) {
                continue;
            }
            health.maxHp = stats.maxHealth(); // evolution can change it
            health.hp = Math.min(health.hp, health.maxHp);
            boolean suffering = needs.hunger >= 1f || needs.thirst >= 1f;
            if (suffering) {
                health.hp -= SpeciesDefinition.perTick(rates.damagePerSecond());
            }
            if (ill) {
                health.hp -= diseaseDamage;
                suffering = true;
            }
            if (Math.abs(needs.exposure) > climate.damageBeyond()) {
                health.hp -= SpeciesDefinition.perTick(climate.damagePerSecond());
                suffering = true;
            }
            if (!suffering && health.hp < health.maxHp) {
                float heal = sheltered && needs.sleeping ? refuges.config().sleepHealFactor() : 1f;
                health.hp = Math.min(health.maxHp, health.hp + SpeciesDefinition.perTick(rates.healthRegenPerSecond()) * heal);
            }
        }
    }

    private float temperatureAt(Transform transform) {
        int tx = Math.clamp((int) Math.floor(transform.position.x), 0, terrain.width() - 1);
        int tz = Math.clamp((int) Math.floor(transform.position.z), 0, terrain.depth() - 1);
        return terrain.temperature(tx, tz);
    }
}
