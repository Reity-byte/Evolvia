package evolvia.systems;

import evolvia.components.Fear;
import evolvia.components.GroupMember;
import evolvia.components.Health;
import evolvia.components.ResourceNode;
import evolvia.components.Sick;
import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.core.Time;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;
import evolvia.god.DivinePower;
import evolvia.god.GodPowers;
import evolvia.world.DeathStats;
import evolvia.world.Groups;
import evolvia.world.Nature;
import evolvia.world.Nature.Disaster;
import evolvia.world.Nature.Weather;
import evolvia.world.ResourceKind;
import evolvia.world.Terrain;
import evolvia.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * The world's nature (phase 9e): changes the weather, strikes lightning in storms, schedules and runs
 * natural disasters (fire, flood, blizzard) and spreads disease. Seasons need no update: they follow
 * from the tick. Everything random comes from the world's generator, in a fixed order.
 */
public final class NatureSystem implements GameSystem {

    /** Fire, flood and disease act once a second. */
    private static final int INTERVAL = Time.TICKS_PER_SECOND;
    private static final int[] DX4 = {1, -1, 0, 0};
    private static final int[] DZ4 = {0, 0, 1, -1};

    private final World world;
    private final Nature nature;
    private final Terrain terrain;
    private final Random random;
    private final List<Integer> scratch = new ArrayList<>();

    public NatureSystem(World world, Nature nature, Random random) {
        this.world = world;
        this.nature = nature;
        this.terrain = nature.terrain();
        this.random = random;
    }

    @Override
    public void update(EcsWorld ecs, int tick) {
        updateWeather(tick);
        if (nature.weather() == Weather.STORM && tick >= nature.nextStrikeTick()) {
            strike(ecs, tick);
        }
        rollDisaster(tick);
        if (nature.pendingDisaster() != null && tick >= nature.pendingTick()) {
            start(nature.pendingDisaster(), tick);
            nature.clearPending();
        }
        if (nature.flooding() && nature.floodOver(tick)) {
            nature.endFlood();
        }
        if (tick % INTERVAL == 0) {
            if (!nature.burningTiles().isEmpty()) {
                spreadFire(tick);
                burnFood(ecs);
            }
            if (nature.floodLevel(tick) > 0f) {
                drownFood(ecs, tick);
            }
            if (!nature.burningTiles().isEmpty() || nature.floodLevel(tick) > 0f) {
                endanger(ecs, tick);
            }
            spreadDisease(ecs, tick);
        }
    }

    // ---------------------------------------------------------------- weather

    private void updateWeather(int tick) {
        if (tick < nature.weatherUntilTick()) {
            return;
        }
        Nature.Season season = nature.season(tick);
        float total = 0f;
        for (Weather w : Weather.values()) {
            total += season.weatherWeight(w);
        }
        float pick = random.nextFloat() * total;
        Weather chosen = Weather.CLEAR;
        for (Weather w : Weather.values()) {
            pick -= season.weatherWeight(w);
            if (season.weatherWeight(w) > 0f && pick < 0f) {
                chosen = w;
                break;
            }
        }
        Nature.WeatherConfig config = nature.config().weather();
        int duration = Math.round((config.minSeconds() + random.nextFloat() * (config.maxSeconds() - config.minSeconds()))
                * Time.TICKS_PER_SECOND);
        if (nature.blizzard(tick)) {
            chosen = Weather.SNOW;
        }
        nature.setWeather(chosen, tick + duration);
        if (chosen == Weather.STORM) {
            nature.setNextStrike(tick + strikeInterval());
        }
    }

    private int strikeInterval() {
        float seconds = nature.config().weather().storm().strikeSeconds();
        return Math.max(1, Math.round(seconds * (0.5f + random.nextFloat()) * Time.TICKS_PER_SECOND));
    }

    /** A natural lightning strike: near a random creature (or anywhere on land); kills, scares, may start a fire. */
    private void strike(EcsWorld ecs, int tick) {
        nature.setNextStrike(tick + strikeInterval());
        ComponentStore<SpeciesRef> creatures = ecs.store(SpeciesRef.class);
        float x = -1f;
        float z = -1f;
        for (int attempt = 0; attempt < 30 && x < 0f; attempt++) {
            float cx;
            float cz;
            if (creatures.size() > 0 && attempt < 15) {
                Transform t = ecs.get(creatures.entityAt(random.nextInt(creatures.size())), Transform.class);
                cx = t.position.x + (random.nextFloat() - 0.5f) * 40f;
                cz = t.position.z + (random.nextFloat() - 0.5f) * 40f;
            } else {
                cx = random.nextFloat() * terrain.width();
                cz = random.nextFloat() * terrain.depth();
            }
            if (terrain.isPassable((int) Math.floor(cx), (int) Math.floor(cz))) {
                x = cx;
                z = cz;
            }
        }
        if (x < 0f) {
            return;
        }
        Nature.Storm storm = nature.config().weather().storm();
        List<Integer> near = new ArrayList<>();
        world.creatureGrid().forEachWithin(x, z, storm.scareRadius(), near::add);
        ComponentStore<Transform> transforms = ecs.store(Transform.class);
        final float sx = x;
        final float sz = z;
        near.sort(Comparator.<Integer>comparingDouble(e -> distance(transforms.get(e), sx, sz)).thenComparingInt(e -> e));
        boolean killed = false;
        for (int entity : near) {
            Transform t = transforms.get(entity);
            if (!killed && distance(t, x, z) <= storm.killRadius()) {
                world.killCreature(entity, DeathStats.Cause.LIGHTNING);
                killed = true;
                continue;
            }
            scare(ecs, entity, x, z, storm.scareRadius() * 2f, tick + Math.round(storm.scareSeconds() * Time.TICKS_PER_SECOND));
        }
        int tx = (int) Math.floor(x);
        int tz = (int) Math.floor(z);
        if (random.nextFloat() < storm.igniteChance()) {
            nature.ignite(tx, tz, tick);
        }
        world.godPowers().recordStrike(new GodPowers.Strike(DivinePower.LIGHTNING, x, z, storm.killRadius(), tick));
    }

    private static double distance(Transform t, float x, float z) {
        return Math.hypot(t.position.x - x, t.position.z - z);
    }

    private static void scare(EcsWorld ecs, int entity, float fromX, float fromZ, float distance, int untilTick) {
        Fear fear = ecs.get(entity, Fear.class);
        if (fear == null) {
            fear = ecs.add(entity, new Fear());
        }
        fear.fromX = fromX;
        fear.fromZ = fromZ;
        fear.distance = distance;
        fear.untilTick = Math.max(fear.untilTick, untilTick);
    }

    // ---------------------------------------------------------------- disasters

    /** At the start of each day (after the grace days), maybe one disaster for later that day. */
    private void rollDisaster(int tick) {
        int day = nature.clock().day(tick);
        if (day == nature.rolledDay()) {
            return;
        }
        nature.setRolledDay(day);
        if (day <= nature.config().disasters().graceDays() || nature.pendingDisaster() != null) {
            return;
        }
        Nature.Season season = nature.season(tick);
        int dayTicks = Math.round(nature.clock().settings().dayLengthSeconds() * Time.TICKS_PER_SECOND);
        for (Disaster disaster : Disaster.values()) {
            if (random.nextFloat() < season.disasterChance(disaster)) {
                nature.schedule(disaster, tick + dayTicks / 10 + random.nextInt(dayTicks / 2));
                return;
            }
        }
    }

    private void start(Disaster disaster, int tick) {
        switch (disaster) {
            case FIRE -> {
                if (!startFire(tick)) {
                    return;
                }
            }
            case FLOOD -> {
                if (nature.flooding()) {
                    return;
                }
                nature.startFlood(tick);
            }
            case BLIZZARD -> {
                Nature.Blizzard b = nature.config().disasters().blizzard();
                int duration = Math.round((b.minSeconds() + random.nextFloat() * (b.maxSeconds() - b.minSeconds()))
                        * Time.TICKS_PER_SECOND);
                nature.startBlizzard(tick + duration);
            }
        }
        nature.announceDisaster(disaster);
    }

    /** A fire starts in flammable land some way from the player's people (or anywhere flammable). */
    private boolean startFire(int tick) {
        List<Groups.Group> own = world.groups().all().stream().filter(g -> g.player).toList();
        for (int attempt = 0; attempt < 300; attempt++) {
            int tx;
            int tz;
            if (!own.isEmpty() && attempt < 100) {
                Groups.Group group = own.get(random.nextInt(own.size()));
                double angle = random.nextFloat() * Math.PI * 2;
                float distance = 15f + random.nextFloat() * 20f;
                tx = (int) Math.floor(group.homeX + Math.sin(angle) * distance);
                tz = (int) Math.floor(group.homeZ + Math.cos(angle) * distance);
            } else {
                tx = random.nextInt(terrain.width());
                tz = random.nextInt(terrain.depth());
            }
            if (nature.ignite(tx, tz, tick)) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        nature.ignite(tx + dx, tz + dz, tick);
                    }
                }
                return true;
            }
        }
        return false;
    }

    private void spreadFire(int tick) {
        Nature.Fire fire = nature.config().disasters().fire();
        float wet = nature.weather().wet() ? fire.rainFactor() : 1f;
        List<Integer> burning = nature.burningTiles();
        int count = burning.size();
        for (int n = 0; n < count; n++) {
            int i = burning.get(n);
            int tx = i % terrain.width();
            int tz = i / terrain.width();
            for (int d = 0; d < 4; d++) {
                int nx = tx + DX4[d];
                int nz = tz + DZ4[d];
                if (terrain.inBounds(nx, nz) && random.nextFloat() < nature.flammability(nx, nz) * wet) {
                    nature.ignite(nx, nz, tick);
                }
            }
        }
        nature.burnDown(nature.weather().wet() ? 3 * INTERVAL : INTERVAL, tick);
    }

    /** Food on burning tiles is burnt. */
    private void burnFood(EcsWorld ecs) {
        ComponentStore<ResourceNode> nodes = ecs.store(ResourceNode.class);
        for (int i : nature.burningTiles()) {
            int tx = i % terrain.width();
            int tz = i / terrain.width();
            for (ResourceKind kind : new ResourceKind[]{ResourceKind.FOOD, ResourceKind.MATERIAL}) {
                world.resourceGrid(kind).forEachWithin(tx + 0.5f, tz + 0.5f, 1f, entity -> {
                    ResourceNode node = nodes.get(entity);
                    Transform t = ecs.get(entity, Transform.class);
                    if (node != null && t != null && (int) Math.floor(t.position.x) == tx && (int) Math.floor(t.position.z) == tz
                            && !"stone".equals(node.type.material())) { // plants and trees burn, rocks do not
                        node.amount = 0f;
                        node.divine = false;
                    }
                });
            }
        }
    }

    /** Plants under the flood lose their food. */
    private void drownFood(EcsWorld ecs, int tick) {
        ComponentStore<ResourceNode> nodes = ecs.store(ResourceNode.class);
        ComponentStore<Transform> transforms = ecs.store(Transform.class);
        for (int n = 0; n < nodes.size(); n++) {
            ResourceNode node = nodes.componentAt(n);
            if (node.type.kind() != ResourceKind.FOOD || node.type.decays()) {
                continue;
            }
            Transform t = transforms.get(nodes.entityAt(n));
            if (nature.isFlooded((int) Math.floor(t.position.x), (int) Math.floor(t.position.z), tick)) {
                node.amount = 0f;
                node.divine = false;
            }
        }
    }

    /** Creatures in fire or flood get hurt; those near a fire or in the water run away. */
    private void endanger(EcsWorld ecs, int tick) {
        Nature.Fire fire = nature.config().disasters().fire();
        Nature.Flood flood = nature.config().disasters().flood();
        int reach = (int) Math.ceil(fire.fearRadius());
        boolean burning = !nature.burningTiles().isEmpty();
        ComponentStore<SpeciesRef> creatures = ecs.store(SpeciesRef.class);
        for (int n = 0; n < creatures.size(); n++) {
            int entity = creatures.entityAt(n);
            Transform t = ecs.get(entity, Transform.class);
            Health health = ecs.get(entity, Health.class);
            if (t == null || health == null) {
                continue;
            }
            int tx = (int) Math.floor(t.position.x);
            int tz = (int) Math.floor(t.position.z);
            if (burning) {
                if (nature.isBurning(tx, tz)) {
                    health.hp -= fire.damagePerSecond();
                }
                int best = Integer.MAX_VALUE;
                int fx = 0;
                int fz = 0;
                for (int dz = -reach; dz <= reach; dz++) {
                    for (int dx = -reach; dx <= reach; dx++) {
                        int d2 = dx * dx + dz * dz;
                        if (d2 < best && d2 <= reach * reach && nature.isBurning(tx + dx, tz + dz)) {
                            best = d2;
                            fx = tx + dx;
                            fz = tz + dz;
                        }
                    }
                }
                if (best != Integer.MAX_VALUE) {
                    scare(ecs, entity, fx + 0.5f, fz + 0.5f, fire.fearRadius() * 2f, tick + 3 * INTERVAL);
                }
            }
            if (nature.isFlooded(tx, tz, tick)) {
                health.hp -= flood.damagePerSecond();
                // Run uphill: "away" from the lower side.
                float gx = terrain.heightAt(t.position.x + 2f, t.position.z) - terrain.heightAt(t.position.x - 2f, t.position.z);
                float gz = terrain.heightAt(t.position.x, t.position.z + 2f) - terrain.heightAt(t.position.x, t.position.z - 2f);
                float length = (float) Math.hypot(gx, gz);
                if (length < 1e-4f) {
                    gx = 1f;
                    gz = 0f;
                    length = 1f;
                }
                scare(ecs, entity, t.position.x - gx / length, t.position.z - gz / length, 14f, tick + 3 * INTERVAL);
            }
        }
    }

    // ---------------------------------------------------------------- disease

    /** The ill infect herd mates nearby; the recovered lose their immunity in time. */
    private void spreadDisease(EcsWorld ecs, int tick) {
        ComponentStore<Sick> sickStore = ecs.store(Sick.class);
        if (sickStore.size() == 0) {
            return;
        }
        Nature.Disease disease = nature.config().disease();
        int duration = Math.round(disease.durationSeconds() * Time.TICKS_PER_SECOND);
        int immune = Math.round(disease.immuneSeconds() * Time.TICKS_PER_SECOND);
        List<Integer> infected = new ArrayList<>();
        List<Integer> recovered = new ArrayList<>();
        for (int n = 0; n < sickStore.size(); n++) {
            int entity = sickStore.entityAt(n);
            Sick sick = sickStore.componentAt(n);
            if (tick >= sick.immuneUntilTick) {
                recovered.add(entity);
                continue;
            }
            if (!sick.isActive(tick)) {
                continue;
            }
            GroupMember member = ecs.get(entity, GroupMember.class);
            Transform t = ecs.get(entity, Transform.class);
            if (member == null || t == null) {
                continue;
            }
            scratch.clear();
            world.creatureGrid().forEachWithin(t.position.x, t.position.z, disease.spreadRadius(), scratch::add);
            scratch.sort(null);
            for (int other : scratch) {
                GroupMember m = ecs.get(other, GroupMember.class);
                if (other != entity && m != null && m.group == member.group && sickStore.get(other) == null
                        && !infected.contains(other) && random.nextFloat() < disease.spreadChancePerSecond()) {
                    infected.add(other);
                }
            }
        }
        for (int entity : recovered) {
            sickStore.remove(entity);
        }
        for (int entity : infected) {
            Sick.infect(ecs, entity, tick, duration, immune);
        }
    }
}
