package evolvia.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import evolvia.evolution.Animal;
import evolvia.evolution.Species;
import evolvia.world.Tribe;
import evolvia.world.Wildlife;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import evolvia.evolution.Condition;
import evolvia.evolution.Effect;
import evolvia.evolution.EvolutionNode;
import evolvia.evolution.EvolutionTree;
import evolvia.evolution.SpeciesDefinition;
import evolvia.evolution.Stat;
import evolvia.god.GodConfig;
import evolvia.world.Milestones;
import evolvia.world.Nature;
import evolvia.world.Refuges;
import evolvia.world.Biome;
import evolvia.world.Biome.Range;
import evolvia.world.BiomeTable;
import evolvia.world.ResourceDefinition;
import evolvia.world.ResourceKind;
import evolvia.world.ResourceTable;
import evolvia.world.WorldConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Loads game data definitions from JSON files on the classpath ({@code src/main/resources/data}).
 * Invalid data fails fast with a message naming the file and the problem.
 */
public final class DataLoader {

    public static final String WORLD_CONFIG = "data/world.json";
    public static final String BIOMES = "data/biomes.json";
    public static final String SPECIES = "data/species.json";
    public static final String RESOURCES = "data/resources.json";
    public static final String POWERS = "data/powers.json";
    public static final String MILESTONES = "data/milestones.json";
    public static final String REFUGES = "data/refuges.json";
    public static final String NATURE = "data/nature.json";
    public static final String ANIMALS = "data/animals.json";
    public static final String RIVALS = "data/rivals.json";
    public static final String TRIBE = "data/tribe.json";
    public static final String SCIENCE = "data/science/science.json";
    public static final String SCIENCE_DIR = "data/science/";
    public static final String EVOLUTION_DIR = "data/evolution/";
    public static final String EVOLUTION_INDEX = EVOLUTION_DIR + "branches.json";

    private static final Gson GSON = new Gson();

    private DataLoader() {
    }

    /** Loads and validates {@code data/world.json}. */
    public static WorldConfig loadWorldConfig() {
        return parseWorldConfig(readResource(WORLD_CONFIG), WORLD_CONFIG);
    }

    /** Loads and validates {@code data/biomes.json}. */
    public static BiomeTable loadBiomes() {
        return parseBiomes(readResource(BIOMES), BIOMES);
    }

    public static WorldConfig parseWorldConfig(String json, String source) {
        WorldConfig config = fromJson(json, WorldConfig.class, source);
        config.validate(source);
        Colors.parseHex(config.water().color(), source + ": water.color");
        return config;
    }

    /** Loads and validates {@code data/powers.json} (faith and god powers). */
    public static GodConfig loadGodConfig() {
        return parseGodConfig(readResource(POWERS), POWERS);
    }

    public static GodConfig parseGodConfig(String json, String source) {
        GodConfig config = fromJson(json, GodConfig.class, source);
        config.validate(source);
        return config;
    }

    /** Loads and validates {@code data/refuges.json} (phase 9d). */
    public static Refuges.Config loadRefuges() {
        return parseRefuges(readResource(REFUGES), REFUGES);
    }

    public static Refuges.Config parseRefuges(String json, String source) {
        Refuges.Config config = fromJson(json, Refuges.Config.class, source);
        require(config.types() != null && !config.types().isEmpty(), source, "missing \"types\"");
        Set<String> ids = new HashSet<>();
        for (Refuges.Type type : config.types()) {
            require(type.id() != null && ids.add(type.id()), source, "missing or duplicate refuge id " + type.id());
            require(type.name() != null && type.count() >= 0 && type.radius() > 0 && type.minAltitude() >= 0
                    && type.biomes() != null, source, "refuge " + type.id() + ": name, count >= 0, radius > 0, biomes list");
        }
        require(config.searchRadius() > 0 && config.minSpacing() >= 0 && config.sleepEnergyFactor() >= 1
                && config.sleepHealFactor() >= 1, source, "searchRadius > 0, minSpacing >= 0, sleep factors >= 1");
        return config;
    }

    /** Loads and validates {@code data/nature.json} (seasons, weather, disease, disasters). */
    public static Nature.Config loadNature() {
        return parseNature(readResource(NATURE), NATURE);
    }

    public static Nature.Config parseNature(String json, String source) {
        Nature.Config config = fromJson(json, Nature.Config.class, source);
        require(config.seasonDays() > 0 && config.seasons() != null && config.seasons().size() == 4, source,
                "seasonDays > 0 and exactly 4 seasons (spring, summer, autumn, winter)");
        for (Nature.Season season : config.seasons()) {
            require(season.id() != null && season.name() != null && season.regrow() >= 0 && season.thirst() > 0
                    && season.weather() != null && season.disasters() != null, source,
                    "season " + season.id() + ": id, name, regrow >= 0, thirst > 0, weather and disasters");
            require(season.weather().values().stream().mapToDouble(Float::doubleValue).sum() > 0, source,
                    "season " + season.id() + ": some weather must have a weight");
        }
        Nature.WeatherConfig w = config.weather();
        require(w != null && w.minSeconds() > 0 && w.maxSeconds() >= w.minSeconds() && w.rain() != null
                && w.storm() != null && w.snow() != null && w.storm().strikeSeconds() > 0, source,
                "weather: minSeconds > 0, maxSeconds >= minSeconds, rain, storm (strikeSeconds > 0), snow");
        Nature.Disease d = config.disease();
        require(d != null && d.spoilSeconds() > 0 && d.durationSeconds() > 0 && d.immuneSeconds() >= 0
                && d.infectChance() >= 0 && d.infectChance() <= 1, source, "disease: invalid values");
        Nature.Disasters n = config.disasters();
        require(n != null && n.graceDays() >= 0 && n.fire() != null && n.flood() != null && n.blizzard() != null
                && n.fire().flammable() != null && n.fire().burnSeconds() > 0 && n.fire().maxTiles() > 0
                && n.flood().riseSeconds() > 0 && n.blizzard().maxSeconds() >= n.blizzard().minSeconds(), source,
                "disasters: graceDays, fire (flammable, burnSeconds, maxTiles), flood (riseSeconds), blizzard");
        return config;
    }

    /** Loads and validates {@code data/milestones.json} (early game goals). */
    public static List<Milestones.Milestone> loadMilestones() {
        return parseMilestones(readResource(MILESTONES), MILESTONES);
    }

    public static List<Milestones.Milestone> parseMilestones(String json, String source) {
        MilestoneFile file = fromJson(json, MilestoneFile.class, source);
        require(file.milestones() != null, source, "missing \"milestones\" array");
        List<Milestones.Milestone> milestones = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (MilestoneJson m : file.milestones()) {
            String where = source + ": milestone " + m.id();
            require(m.id() != null && ids.add(m.id()), source, "missing or duplicate milestone id " + m.id());
            require(m.name() != null && m.type() != null && m.value() != null, where, "needs name, type and value");
            Milestones.Type type;
            try {
                type = Milestones.Type.valueOf(m.type().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(where + ": unknown type '" + m.type() + "'");
            }
            milestones.add(new Milestones.Milestone(m.id(), m.name(), m.description() != null ? m.description() : "", type,
                    m.value(), m.rewardEp() != null ? m.rewardEp() : 0f, m.rewardFaith() != null ? m.rewardFaith() : 0f));
        }
        return milestones;
    }

    private record MilestoneFile(List<MilestoneJson> milestones) {
    }

    private record MilestoneJson(String id, String name, String description, String type, Integer value, Float rewardEp,
                                 Float rewardFaith) {
    }

    /**
     * Loads and validates {@code data/animals.json}: every animal is {@code data/species.json} with the values
     * its {@code "species"} object overrides (objects merge key by key).
     */
    public static Wildlife.Config loadAnimals() {
        return parseAnimals(readResource(ANIMALS), readResource(SPECIES), ANIMALS);
    }

    public static Wildlife.Config parseAnimals(String json, String baseSpeciesJson, String source) {
        JsonObject root;
        JsonObject base;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
            base = JsonParser.parseString(baseSpeciesJson).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalStateException(source + ": invalid JSON: " + e.getMessage(), e);
        }
        require(root.has("hunting") && root.has("animals"), source, "missing \"hunting\" or \"animals\"");
        Wildlife.Hunting hunting = fromJson(root.get("hunting").toString(), Wildlife.Hunting.class, source);
        require(hunting.hungerThreshold() >= 0 && hunting.hungerThreshold() < 1 && hunting.chaseSeconds() > 0
                        && hunting.score() > 0 && hunting.scareRadius() > 0 && hunting.scareSeconds() > 0
                        && hunting.fleeDistance() > 0 && hunting.biteFactor() > 0,
                source, "hunting: hungerThreshold in [0, 1), other values positive");
        List<Species> species = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        ids.add(base.get("id").getAsString());
        for (JsonElement element : root.getAsJsonArray("animals")) {
            JsonObject a = element.getAsJsonObject();
            String id = a.has("id") ? a.get("id").getAsString() : null;
            require(id != null && ids.add(id), source, "missing or duplicate animal id " + id);
            String where = source + " (" + id + ")";
            AnimalJson traits = fromJson(a.toString(), AnimalJson.class, where);
            require(Animal.PREY.equals(traits.role()) || Animal.PREDATOR.equals(traits.role()), where,
                    "role must be \"prey\" or \"predator\"");
            require(traits.herds() >= 0 && traits.herdSize() != null && traits.herdSize().length == 2
                            && traits.herdSize()[0] >= 1 && traits.herdSize()[0] <= traits.herdSize()[1], where,
                    "herds >= 0, herdSize [min, max] with 1 <= min <= max");
            require(!traits.isPredatorWithoutPrey(), where, "a predator needs a \"prey\" list");
            JsonObject merged = base.deepCopy();
            merge(merged, a.getAsJsonObject("species"));
            merged.addProperty("id", id);
            SpeciesDefinition definition = parseSpecies(merged.toString(), where);
            species.add(new Species(definition, new Animal(traits.role(),
                    traits.prey() != null ? List.copyOf(traits.prey()) : List.of(), traits.nocturnal(), traits.herds(),
                    traits.herdSize(), traits.biomes() != null ? List.copyOf(traits.biomes()) : List.of(),
                    traits.visuals() != null ? Map.copyOf(traits.visuals()) : Map.of())));
        }
        for (Species s : species) {
            for (String prey : s.animal().prey()) {
                require(ids.contains(prey), source, s.id() + " hunts unknown species " + prey);
            }
        }
        return new Wildlife.Config(hunting, List.copyOf(species));
    }

    /**
     * Loads the rival people (phase 11) over {@code species.json}; its start nodes and plan must exist in
     * {@code tree} (unless the tree is empty, as in some tests).
     */
    public static evolvia.world.Rivals.Config loadRivals(EvolutionTree tree) {
        return parseRivals(readResource(RIVALS), readResource(SPECIES), tree, RIVALS);
    }

    public static evolvia.world.Rivals.Config parseRivals(String json, String baseSpeciesJson, EvolutionTree tree, String source) {
        JsonObject rival;
        JsonObject base;
        try {
            rival = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("rival");
            base = JsonParser.parseString(baseSpeciesJson).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalStateException(source + ": invalid JSON: " + e.getMessage(), e);
        }
        require(rival != null && rival.has("id") && rival.has("species"), source, "missing \"rival\" with \"id\" and \"species\"");
        RivalJson r = fromJson(rival.toString(), RivalJson.class, source);
        require(!r.id().equals(base.get("id").getAsString()), source, "the rival needs its own id");
        require(r.herds() >= 1 && r.herdSize() != null && r.herdSize().length == 2 && r.herdSize()[0] >= 2
                && r.herdSize()[0] <= r.herdSize()[1], source, "herds >= 1, herdSize [min, max] with 2 <= min <= max");
        List<String> start = r.start() != null ? List.copyOf(r.start()) : List.of();
        List<evolvia.world.Rivals.Step> plan = r.plan() != null ? List.copyOf(r.plan()) : List.of();
        float last = 0f;
        for (evolvia.world.Rivals.Step step : plan) {
            require(step.node() != null && step.minute() >= last, source, "plan: nodes in the order of their minutes");
            last = step.minute();
        }
        if (tree.size() > 0) {
            for (String node : start) {
                require(tree.node(node) != null, source, "unknown start node " + node);
            }
            for (evolvia.world.Rivals.Step step : plan) {
                require(tree.node(step.node()) != null, source, "unknown plan node " + step.node());
            }
        }
        JsonObject merged = base.deepCopy();
        merge(merged, rival.getAsJsonObject("species"));
        merged.addProperty("id", r.id());
        SpeciesDefinition definition = parseSpecies(merged.toString(), source);
        Species species = Species.rival(definition, tree);
        for (String node : start) {
            species.grant(node);
        }
        require(r.tribeMinute() >= 0, source, "tribeMinute must not be negative");
        return new evolvia.world.Rivals.Config(species, start, plan, r.herds(), r.herdSize(), r.tribeMinute(),
                r.buildings() != null ? List.copyOf(r.buildings()) : List.of());
    }

    private record RivalJson(String id, int herds, int[] herdSize, List<String> start, List<evolvia.world.Rivals.Step> plan,
                             float tribeMinute, List<String> buildings) {
    }

    private record AnimalJson(String role, List<String> prey, boolean nocturnal, int herds, int[] herdSize,
                              List<String> biomes, Map<String, String> visuals) {
        boolean isPredatorWithoutPrey() {
            return Animal.PREDATOR.equals(role) && (prey == null || prey.isEmpty());
        }
    }

    /** Copies {@code overrides} into {@code target}; nested objects merge key by key. */
    private static void merge(JsonObject target, JsonObject overrides) {
        if (overrides == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : overrides.entrySet()) {
            JsonElement existing = target.get(entry.getKey());
            if (existing != null && existing.isJsonObject() && entry.getValue().isJsonObject()) {
                merge(existing.getAsJsonObject(), entry.getValue().getAsJsonObject());
            } else {
                target.add(entry.getKey(), entry.getValue());
            }
        }
    }

    /** Loads and validates {@code data/tribe.json} (the tribe's work, phase 9g). */
    public static Tribe.Config loadTribe() {
        return parseTribe(readResource(TRIBE), TRIBE);
    }

    public static Tribe.Config parseTribe(String json, String source) {
        Tribe.Config config = fromJson(json, Tribe.Config.class, source);
        Tribe.Gathering g = config.gathering();
        require(g != null && g.ability() != null && g.stockCap() > 0 && g.workSeconds() > 0 && g.radius() > 0
                        && g.score() > 0 && g.deliverScore() > 0 && g.maxNeed() > 0 && g.maxNeed() <= 1, source,
                "gathering: ability, positive stockCap, workSeconds, radius, score, deliverScore, maxNeed in (0, 1]");
        Tribe.Rules t = config.tribe();
        require(t != null && t.ability() != null && t.speechAbility() != null && t.joinRadius() > 0 && t.roleSeconds() > 0
                        && t.builderShare() > 0 && t.builderShare() <= 1 && t.maxBuilders() >= 1 && t.buildScore() > 0
                        && t.siteRadius() != null && t.siteRadius().length == 2 && t.siteRadius()[0] > 0
                        && t.siteRadius()[0] <= t.siteRadius()[1] && t.moralityThreshold() >= 0 && t.goodBirthFactor() > 0
                        && t.evilBirthFactor() > 0 && t.evilWorkFactor() > 0 && t.desertionPerMinute() >= 0, source,
                "tribe: invalid rules");
        require(config.buildings() != null && !config.buildings().isEmpty(), source, "missing \"buildings\"");
        Set<String> ids = new HashSet<>();
        for (Tribe.BuildingType b : config.buildings()) {
            require(b.id() != null && ids.add(b.id()) && b.name() != null && b.cost() != null && b.buildSeconds() > 0
                            && b.planFaith() > 0 && Set.of("warmth", "refuge", "storage", "faith").contains(b.effect()),
                    source, "building " + b.id() + ": id, name, cost, buildSeconds > 0, planFaith > 0, effect warmth / refuge / storage / faith");
        }
        return config;
    }

    /**
     * Loads the science rules and tree (phase 10b): {@code data/science/science.json} with the rules and the list of
     * branch files in the same folder.
     */
    public static evolvia.world.Science.Config loadScience() {
        ScienceFile file = fromJson(readResource(SCIENCE), ScienceFile.class, SCIENCE);
        evolvia.world.Science.Rules r = file.rules();
        require(r != null && r.ability() != null && r.basePerMinute() >= 0 && r.perDelivery() >= 0 && r.perBuilding() >= 0
                        && r.tribeFactor() > 0 && r.costGrowthPerDiscovery() >= 0 && r.queueMax() >= 1
                        && r.cookingSpoilFactor() >= 0 && r.herbalSpreadFactor() >= 0 && r.herbalDurationFactor() > 0,
                SCIENCE, "rules: ability, non-negative rates, tribeFactor > 0, queueMax >= 1");
        require(file.files() != null && !file.files().isEmpty(), SCIENCE, "missing \"files\" list");
        Map<String, String> files = new LinkedHashMap<>();
        for (String name : file.files()) {
            files.put(SCIENCE_DIR + name, readResource(SCIENCE_DIR + name));
        }
        return new evolvia.world.Science.Config(r, parseEvolutionTree(files, null, "data/science"));
    }

    /** Loads and validates {@code data/species.json}. */
    public static SpeciesDefinition loadSpecies() {
        return parseSpecies(readResource(SPECIES), SPECIES);
    }

    public static SpeciesDefinition parseSpecies(String json, String source) {
        SpeciesJson s = fromJson(json, SpeciesJson.class, source);
        require(s.id() != null && !s.id().isBlank(), source, "missing \"id\"");
        require(positive(s.bodySize()), source, "bodySize must be positive");
        require(positive(s.speed()), source, "speed must be positive");
        require(positive(s.maxHealth()), source, "maxHealth must be positive");
        require(positive(s.senseRadius()), source, "senseRadius must be positive");
        float[] lifespan = s.lifespanSeconds();
        require(lifespan != null && lifespan.length == 2 && lifespan[0] > 0 && lifespan[0] <= lifespan[1], source,
                "lifespanSeconds must be [min, max] with 0 < min <= max");

        SpeciesDefinition.NeedRates needs = s.needs();
        require(needs != null, source, "missing \"needs\"");
        require(needs.hungerPerSecond() > 0 && needs.thirstPerSecond() > 0 && needs.energyDrainPerSecond() > 0
                        && needs.energyRecoverPerSecond() > 0 && needs.damagePerSecond() > 0,
                source, "needs: hungerPerSecond, thirstPerSecond, energyDrainPerSecond, energyRecoverPerSecond and damagePerSecond must be positive");
        require(needs.sleepingNeedFactor() >= 0 && needs.healthRegenPerSecond() >= 0, source,
                "needs: sleepingNeedFactor and healthRegenPerSecond must not be negative");

        SpeciesDefinition.Eating eating = s.eating();
        require(eating != null && eating.hungerPerUnit() > 0 && eating.secondsPerUnit() > 0 && eating.thirstReliefPerSecond() > 0,
                source, "eating: hungerPerUnit, secondsPerUnit and thirstReliefPerSecond must be positive");

        SpeciesDefinition.AiTuning ai = s.ai();
        require(ai != null && ai.evaluateEverySeconds() > 0, source, "ai.evaluateEverySeconds must be positive");
        require(inUnitRange(ai.needThreshold()) && inUnitRange(ai.sleepThreshold()) && inUnitRange(ai.wanderScore())
                        && ai.switchMargin() >= 0 && ai.exploreRadiusFactor() >= 1, source,
                "ai: needThreshold, sleepThreshold and wanderScore must be in [0, 1), switchMargin must not be negative, exploreRadiusFactor must be at least 1");

        require(s.wander() != null && positive(s.wander().radius()), source, "wander.radius must be positive");
        float[] pause = s.wander().pauseSeconds();
        require(pause != null && pause.length == 2 && pause[0] >= 0 && pause[0] <= pause[1], source,
                "wander.pauseSeconds must be [min, max] with 0 <= min <= max");
        SpeciesDefinition.Reproduction reproduction = s.reproduction();
        require(reproduction != null, source, "missing \"reproduction\"");
        require(reproduction.adultAgeSeconds() > 0 && reproduction.adultAgeSeconds() < lifespan[0], source,
                "reproduction.adultAgeSeconds must be positive and below the shortest lifespan");
        require(reproduction.cooldownSeconds() > 0 && reproduction.litterSize() >= 1 && reproduction.hungerCost() >= 0,
                source, "reproduction: cooldownSeconds must be positive, litterSize at least 1, hungerCost not negative");
        require(inUnitRange(reproduction.maxNeed()) && inUnitRange(reproduction.minHealth())
                        && inUnitRange(reproduction.mateScore()), source,
                "reproduction: maxNeed, minHealth and mateScore must be in [0, 1)");

        SpeciesDefinition.GenomeTuning genome = s.genome();
        require(genome != null && genome.variation() >= 0 && genome.variation() < 0.5f && genome.mutation() >= 0,
                source, "genome: variation must be in [0, 0.5), mutation must not be negative");

        SpeciesDefinition.Population population = s.population();
        require(population != null && population.starting() >= 0 && population.max() >= population.starting()
                        && population.max() <= 100_000 && population.spawnRadius() >= 0, source,
                "population: 0 <= starting <= max <= 100000, spawnRadius must not be negative");

        SpeciesDefinition.Diet diet = s.diet();
        require(diet != null && diet.plantNutrition() >= 0 && diet.meatNutrition() >= 0
                        && diet.plantNutrition() + diet.meatNutrition() > 0, source,
                "diet: plantNutrition and meatNutrition must not be negative, and the species must eat something");
        SpeciesDefinition.Climate climate = s.climate();
        require(climate != null && climate.comfortMin() <= climate.comfortMax() && climate.needFactorPerUnit() >= 0
                        && climate.damageBeyond() >= 0 && climate.damagePerSecond() >= 0, source,
                "climate: comfortMin <= comfortMax, other values must not be negative");
        SpeciesDefinition.EvolutionRates evolution = s.evolution();
        require(evolution != null && evolution.populationPointsPerMinute() >= 0 && evolution.pointsPerGeneration() >= 0
                        && evolution.harshPointsPerCreatureMinute() >= 0 && evolution.traitStepsPerBirth() >= 1
                        && evolution.costGrowthPerNode() >= 0, source,
                "evolution: rates and costGrowthPerNode must not be negative, traitStepsPerBirth >= 1");

        SpeciesDefinition.Groups groups = s.groups();
        require(groups != null && groups.updateSeconds() > 0 && groups.joinRadius() > 0 && groups.minSize() >= 2
                        && groups.minFounders() >= groups.minSize() && groups.maxSize() >= 2 * groups.minSize()
                        && groups.followDistance() > 0 && groups.leaveDistance() > groups.followDistance()
                        && groups.leaveSeconds() >= 0 && groups.followScore() >= 0 && groups.forageRadius() > 0
                        && groups.urgentNeed() > 0 && groups.urgentNeed() <= 1, source,
                "groups: positive values, 2 <= minSize <= minFounders, maxSize >= 2 * minSize, leaveDistance > followDistance, urgentNeed in 0..1");

        SpeciesDefinition.Combat combat = s.combat();
        require(combat != null && combat.territoryRadius() > 0 && combat.damagePerSecond() > 0 && combat.attackRange() > 0
                        && combat.fleeHealth() >= combat.surrenderHealth() && combat.surrenderHealth() >= 0
                        && combat.fleeHealth() < 1 && combat.attackScore() >= 0 && combat.orderScore() >= 0, source,
                "combat: positive territoryRadius / damagePerSecond / attackRange, 0 <= surrenderHealth <= fleeHealth < 1");
        require(population.wildHerds() >= 0 && population.wildHerdSize() >= 0 && population.herdSpacing() >= 0, source,
                "population: wildHerds, wildHerdSize and herdSpacing must not be negative");

        return new SpeciesDefinition(
                s.id(),
                s.name() != null ? s.name() : s.id(),
                Colors.parseHex(s.color(), source + ": color"),
                s.bodySize(),
                s.speed(),
                s.maxHealth(),
                lifespan[0],
                lifespan[1],
                s.senseRadius(),
                needs,
                eating,
                ai,
                new SpeciesDefinition.Wander(s.wander().radius(), pause[0], pause[1]),
                reproduction,
                genome,
                population,
                diet,
                climate,
                evolution,
                groups,
                combat,
                SpeciesDefinition.Skills.NONE);
    }

    /** Loads and validates {@code data/resources.json}. */
    public static ResourceTable loadResources() {
        return parseResources(readResource(RESOURCES), RESOURCES);
    }

    public static ResourceTable parseResources(String json, String source) {
        ResourceFile file = fromJson(json, ResourceFile.class, source);
        require(file.resources() != null, source, "missing \"resources\" array");
        List<ResourceDefinition> resources = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (ResourceJson r : file.resources()) {
            String where = source + ": resource #" + resources.size() + (r.id() != null ? " ('" + r.id() + "')" : "");
            require(r.id() != null && !r.id().isBlank(), where, "missing \"id\"");
            require(ids.add(r.id()), where, "duplicate id");
            ResourceKind kind;
            try {
                kind = ResourceKind.valueOf(String.valueOf(r.kind()).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(where + ": kind must be \"food\", \"water\" or \"material\", got: " + r.kind());
            }
            if (kind == ResourceKind.FOOD) {
                require(positive(r.capacity()) && positive(r.size()), where, "food needs a positive capacity and size");
                require(r.foodType() != null && !r.foodType().isBlank(), where, "food needs a \"foodType\" (e.g. plant, meat)");
                require(positive(r.nutrition()), where, "food needs a positive nutrition");
                require(r.regrowPerSecond() != null && r.regrowPerSecond() >= 0, where, "regrowPerSecond must not be negative");
                require(r.decayPerSecond() == null || r.decayPerSecond() >= 0, where, "decayPerSecond must not be negative");
                require(r.spawnDensity() != null && r.spawnDensity() >= 0 && r.spawnDensity() <= 1, where,
                        "spawnDensity must be in [0, 1]");
            }
            if (kind == ResourceKind.MATERIAL) {
                require(positive(r.capacity()) && positive(r.size()), where, "a material needs a positive capacity and size");
                require(r.material() != null && !r.material().isBlank(), where, "a material needs \"material\" (e.g. wood, stone)");
                require(r.biomeDensity() != null && r.biomeDensity().values().stream().allMatch(d -> d >= 0 && d <= 1), where,
                        "a material needs \"biomeDensity\" with chances in [0, 1]");
                require(r.regrowPerSecond() == null || r.regrowPerSecond() >= 0, where, "regrowPerSecond must not be negative");
            }
            resources.add(new ResourceDefinition(
                    resources.size(),
                    r.id(),
                    r.name() != null ? r.name() : r.id(),
                    kind,
                    r.foodType(),
                    r.nutrition() != null ? r.nutrition() : 1f,
                    r.capacity() != null ? r.capacity() : 0f,
                    r.regrowPerSecond() != null ? r.regrowPerSecond() : 0f,
                    r.decayPerSecond() != null ? r.decayPerSecond() : 0f,
                    Boolean.TRUE.equals(r.spawnOnDeath()),
                    r.spawnDensity() != null ? r.spawnDensity() : 0f,
                    r.size() != null ? r.size() : 0f,
                    r.color() != null ? Colors.parseHex(r.color(), where + ": color") : 0,
                    r.emptyColor() != null ? Colors.parseHex(r.emptyColor(), where + ": emptyColor") : 0,
                    r.material(), r.biomeDensity() != null ? Map.copyOf(r.biomeDensity()) : Map.of()));
        }
        return new ResourceTable(resources, source);
    }

    /** Loads all evolution branch files listed in {@code data/evolution/branches.json} and validates the tree. */
    public static EvolutionTree loadEvolutionTree(BiomeTable biomes) {
        BranchIndex index = fromJson(readResource(EVOLUTION_INDEX), BranchIndex.class, EVOLUTION_INDEX);
        require(index.files() != null && !index.files().isEmpty(), EVOLUTION_INDEX, "missing \"files\" list");
        Map<String, String> files = new LinkedHashMap<>();
        for (String file : index.files()) {
            String path = EVOLUTION_DIR + file;
            files.put(path, readResource(path));
        }
        return parseEvolutionTree(files, biomes);
    }

    /**
     * Parses branch files (path -> JSON) into a validated tree.
     *
     * @param biomes used to check biome ids in conditions; may be null to skip that check
     */
    public static EvolutionTree parseEvolutionTree(Map<String, String> files, BiomeTable biomes, String label) {
        List<EvolutionNode> nodes = new ArrayList<>();
        for (Map.Entry<String, String> file : files.entrySet()) {
            String source = file.getKey();
            BranchFile branch = fromJson(file.getValue(), BranchFile.class, source);
            require(branch.branch() != null && !branch.branch().isBlank(), source, "missing \"branch\"");
            require(branch.nodes() != null, source, "missing \"nodes\" array");
            for (NodeJson n : branch.nodes()) {
                String where = source + ": node " + (n.id() != null ? "'" + n.id() + "'" : "#" + nodes.size());
                require(n.id() != null && !n.id().isBlank(), where, "missing \"id\"");
                require(n.name() != null, where, "missing \"name\"");
                require(n.cost() != null && n.cost() >= 0, where, "cost must be a non-negative number");
                List<Effect> effects = new ArrayList<>();
                if (n.effects() != null) {
                    for (EffectJson e : n.effects()) {
                        effects.add(parseEffect(e, where));
                    }
                }
                List<String> requires = n.requires() != null ? List.copyOf(n.requires()) : List.of();
                String description = n.description() != null ? n.description() : "";
                Condition condition = parseCondition(n.requiresCondition(), where, biomes);
                if (n.requiresConditions() != null && !n.requiresConditions().isEmpty()) { // several (phase 10b)
                    List<Condition> all = new ArrayList<>();
                    if (condition != null) {
                        all.add(condition);
                    }
                    for (ConditionJson c : n.requiresConditions()) {
                        all.add(parseCondition(c, where, biomes));
                    }
                    condition = all.size() == 1 ? all.getFirst() : new Condition.All(List.copyOf(all));
                }
                if (n.levels() == null) {
                    nodes.add(new EvolutionNode(n.id(), n.name(), description, branch.branch(), n.cost(), requires,
                            n.exclusiveGroup(), condition, List.copyOf(effects)));
                    continue;
                }
                // A levelled trait (phase 10a): one node per level, each requiring the level before.
                require(n.levels() >= 2 && n.levels() <= ROMAN.length, where, "levels must be 2 to " + ROMAN.length);
                float growth = n.levelCostGrowth() != null ? n.levelCostGrowth() : 1.5f;
                require(growth >= 1f, where, "levelCostGrowth must be at least 1");
                require(n.exclusiveGroup() == null, where, "a levelled trait cannot be in an exclusiveGroup");
                for (int level = 1; level <= n.levels(); level++) {
                    EvolutionNode.Trait trait = new EvolutionNode.Trait(n.id(), n.name(), level, n.levels());
                    List<String> levelRequires = level == 1 ? requires : List.of(trait.nodeId(level - 1));
                    nodes.add(new EvolutionNode(trait.nodeId(level), n.name() + " " + ROMAN[level - 1], description,
                            branch.branch(), Math.round(n.cost() * (float) Math.pow(growth, level - 1)), levelRequires,
                            null, level == 1 ? condition : null, List.copyOf(effects), trait));
                }
            }
        }
        return new EvolutionTree(nodes, label);
    }

    /** Same as {@link #parseEvolutionTree(Map, BiomeTable)} for another tree (the science tree, phase 10b). */
    public static EvolutionTree parseEvolutionTree(Map<String, String> files, BiomeTable biomes) {
        return parseEvolutionTree(files, biomes, "data/evolution");
    }

    private static Effect parseEffect(EffectJson e, String where) {
        require(e.type() != null, where, "effect without \"type\"");
        return switch (e.type()) {
            case "stat_add", "stat_mul" -> {
                Stat stat = Stat.byKey(e.stat());
                require(stat != null, where, "unknown stat '" + e.stat() + "' (known: "
                        + String.join(", ", java.util.Arrays.stream(Stat.values()).map(Stat::key).toList()) + ")");
                require(e.value() != null, where, e.type() + " needs a \"value\"");
                yield e.type().equals("stat_add") ? new Effect.StatAdd(stat, e.value()) : new Effect.StatMul(stat, e.value());
            }
            case "unlock_ability" -> {
                require(e.ability() != null && !e.ability().isBlank(), where, "unlock_ability needs an \"ability\"");
                yield new Effect.UnlockAbility(e.ability());
            }
            case "visual" -> {
                require(e.part() != null && e.variant() != null, where, "visual needs \"part\" and \"variant\"");
                yield new Effect.Visual(e.part(), e.variant());
            }
            case "unlock_action" -> {
                require(e.action() != null && !e.action().isBlank(), where, "unlock_action needs an \"action\"");
                yield new Effect.UnlockAction(e.action());
            }
            default -> throw new IllegalStateException(where + ": unknown effect type '" + e.type()
                    + "' (known: stat_add, stat_mul, unlock_ability, visual, unlock_action)");
        };
    }

    private static Condition parseCondition(ConditionJson c, String where, BiomeTable biomes) {
        if (c == null) {
            return null;
        }
        require(c.type() != null, where, "requiresCondition without \"type\"");
        return switch (c.type()) {
            case "population_min" -> {
                require(c.value() != null && c.value() > 0, where, "population_min needs a positive \"value\"");
                yield new Condition.PopulationMin(Math.round(c.value()));
            }
            case "biome_presence" -> {
                require(c.biome() != null && (biomes == null || biomes.byId(c.biome()) != null), where,
                        "biome_presence needs an existing \"biome\", got: " + c.biome());
                require(c.ratio() != null && c.ratio() > 0 && c.ratio() <= 1, where, "biome_presence needs \"ratio\" in (0, 1]");
                yield new Condition.BiomePresence(c.biome(), c.ratio());
            }
            case "discovery" -> {
                require(c.id() != null && !c.id().isBlank(), where, "discovery needs an \"id\" (a science node)");
                yield new Condition.Discovery(c.id(), c.name() != null ? c.name() : c.id());
            }
            case "evolved" -> {
                require(c.id() != null && !c.id().isBlank(), where, "evolved needs an \"id\" (an evolution node)");
                yield new Condition.Evolved(c.id(), c.name() != null ? c.name() : c.id());
            }
            default -> throw new IllegalStateException(where + ": unknown condition type '" + c.type()
                    + "' (known: population_min, biome_presence, discovery, evolved)");
        };
    }

    private static boolean positive(Float value) {
        return value != null && value > 0;
    }

    private static boolean inUnitRange(float value) {
        return value >= 0 && value < 1;
    }

    private static void require(boolean condition, String source, String message) {
        if (!condition) {
            throw new IllegalStateException(source + ": " + message);
        }
    }

    public static BiomeTable parseBiomes(String json, String source) {
        BiomeFile file = fromJson(json, BiomeFile.class, source);
        if (file.biomes() == null) {
            throw new IllegalStateException(source + ": missing \"biomes\" array");
        }
        List<Biome> biomes = new ArrayList<>();
        for (BiomeJson b : file.biomes()) {
            String where = source + ": biome #" + biomes.size() + (b.id() != null ? " ('" + b.id() + "')" : "");
            if (b.id() == null || b.id().isBlank()) {
                throw new IllegalStateException(where + ": missing \"id\"");
            }
            if (b.color() == null) {
                throw new IllegalStateException(where + ": missing \"color\"");
            }
            boolean water = Boolean.TRUE.equals(b.water());
            float fertility = b.fertility() != null ? b.fertility() : 0f;
            if (fertility < 0 || fertility > 1) {
                throw new IllegalStateException(where + ": fertility must be in [0, 1]");
            }
            biomes.add(new Biome(
                    biomes.size(),
                    b.id(),
                    b.name() != null ? b.name() : b.id(),
                    Colors.parseHex(b.color(), where + ": color"),
                    water,
                    b.passable() != null ? b.passable() : !water,
                    fertility,
                    range(b.temperature(), where + ": temperature"),
                    range(b.moisture(), where + ": moisture"),
                    range(b.altitude(), where + ": altitude")));
        }
        return new BiomeTable(biomes, source);
    }

    private static Range range(float[] values, String where) {
        if (values == null) {
            return Range.ANY;
        }
        if (values.length != 2 || values[0] > values[1]) {
            throw new IllegalStateException(where + " must be [min, max] with min <= max");
        }
        return new Range(values[0], values[1]);
    }

    private static <T> T fromJson(String json, Class<T> type, String source) {
        try (Reader reader = new StringReader(json)) {
            T value = GSON.fromJson(reader, type);
            if (value == null) {
                throw new IllegalStateException(source + ": file is empty");
            }
            return value;
        } catch (JsonParseException e) {
            throw new IllegalStateException(source + ": invalid JSON: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String readResource(String path) {
        try (InputStream in = DataLoader.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Data file not found on classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read data file: " + path, e);
        }
    }

    /** JSON shape of {@code species.json}; boxed types so a missing value is null. */
    private record SpeciesJson(String id, String name, String color, Float bodySize, Float speed, Float maxHealth,
                               float[] lifespanSeconds, Float senseRadius, SpeciesDefinition.NeedRates needs,
                               SpeciesDefinition.Eating eating, SpeciesDefinition.AiTuning ai,
                               WanderJson wander, SpeciesDefinition.Reproduction reproduction,
                               SpeciesDefinition.GenomeTuning genome, SpeciesDefinition.Population population,
                               SpeciesDefinition.Diet diet, SpeciesDefinition.Climate climate,
                               SpeciesDefinition.EvolutionRates evolution, SpeciesDefinition.Groups groups,
                               SpeciesDefinition.Combat combat) {
    }

    /** JSON shape of {@code data/science/science.json}. */
    private record ScienceFile(evolvia.world.Science.Rules rules, List<String> files) {
    }

    /** JSON shape of {@code data/evolution/branches.json}. */
    private record BranchIndex(List<String> files) {
    }

    /** JSON shape of one evolution branch file. */
    private record BranchFile(String branch, List<NodeJson> nodes) {
    }

    private record NodeJson(String id, String name, String description, Integer cost, List<String> requires,
                            String exclusiveGroup, ConditionJson requiresCondition, List<ConditionJson> requiresConditions,
                            List<EffectJson> effects, Integer levels, Float levelCostGrowth) {
    }

    /** Level names of levelled traits (phase 10a). */
    private static final String[] ROMAN = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII"};

    private record EffectJson(String type, String stat, Float value, String ability, String part, String variant,
                              String action) {
    }

    private record ConditionJson(String type, Float value, String biome, Float ratio, String id, String name) {
    }

    /** JSON shape of {@code resources.json}. */
    private record ResourceFile(List<ResourceJson> resources) {
    }

    private record ResourceJson(String id, String name, String kind, String foodType, Float nutrition, Float capacity,
                                Float regrowPerSecond, Float decayPerSecond, Boolean spawnOnDeath, Float spawnDensity, Float size,
                                String color, String emptyColor, String material, Map<String, Float> biomeDensity) {
    }

    private record WanderJson(Float radius, float[] pauseSeconds) {
    }

    /** JSON shape of {@code biomes.json}. */
    private record BiomeFile(List<BiomeJson> biomes) {
    }

    /** JSON shape of one biome; optional fields are boxed so a missing value is null. */
    private record BiomeJson(String id, String name, String color, Boolean water, Boolean passable,
                             Float fertility, float[] temperature, float[] moisture, float[] altitude) {
    }
}
