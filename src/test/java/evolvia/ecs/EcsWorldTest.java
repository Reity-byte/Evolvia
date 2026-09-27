package evolvia.ecs;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EcsWorldTest {

    private static final class Position {
        float x;

        Position(float x) {
            this.x = x;
        }
    }

    private static final class Tag {
    }

    @Test
    void createsSequentialIds() {
        EcsWorld world = new EcsWorld();
        assertEquals(0, world.createEntity());
        assertEquals(1, world.createEntity());
        assertEquals(2, world.entityCount());
    }

    @Test
    void addAndGetComponents() {
        EcsWorld world = new EcsWorld();
        int e = world.createEntity();
        Position p = world.add(e, new Position(3));
        assertSame(p, world.get(e, Position.class));
        assertNull(world.get(e, Tag.class));
        assertThrows(IllegalArgumentException.class, () -> world.add(99, new Tag()));
    }

    @Test
    void destructionIsDeferredUntilFlush() {
        EcsWorld world = new EcsWorld();
        int e = world.createEntity();
        world.add(e, new Position(1));
        world.destroyEntity(e);
        assertTrue(world.isAlive(e), "still alive during the tick");
        assertEquals(1, world.store(Position.class).size());

        world.flushDestroyed();
        assertFalse(world.isAlive(e));
        assertEquals(0, world.store(Position.class).size());
        assertEquals(0, world.entityCount());
    }

    @Test
    void destroyedIdsAreRecycledOnlyAfterFlush() {
        EcsWorld world = new EcsWorld();
        int a = world.createEntity();
        world.destroyEntity(a);
        world.destroyEntity(a); // queued twice: harmless
        int b = world.createEntity();
        assertEquals(1, b, "ID is not reused before the flush");
        world.flushDestroyed();
        assertEquals(a, world.createEntity(), "freed ID is reused");
        assertEquals(2, world.entityCount());
    }

    @Test
    void recycledEntityStartsWithoutComponents() {
        EcsWorld world = new EcsWorld();
        int e = world.createEntity();
        world.add(e, new Position(5));
        world.add(e, new Tag());
        world.destroyEntity(e);
        world.flushDestroyed();
        int reused = world.createEntity();
        assertEquals(e, reused);
        assertNull(world.get(reused, Position.class));
        assertNull(world.get(reused, Tag.class));
    }

    @Test
    void sparseSetKeepsMappingAfterRemovals() {
        SparseSetStore<Position> store = new SparseSetStore<>();
        for (int e = 0; e < 1000; e++) {
            store.put(e * 3, new Position(e));
        }
        for (int e = 0; e < 1000; e += 2) {
            assertTrue(store.remove(e * 3));
        }
        assertFalse(store.remove(0));
        assertEquals(500, store.size());
        for (int e = 1; e < 1000; e += 2) {
            assertEquals(e, store.get(e * 3).x);
        }
        // Dense iteration visits every remaining entity exactly once, with its own component.
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < store.size(); i++) {
            int entity = store.entityAt(i);
            assertTrue(seen.add(entity));
            assertEquals(entity / 3f, store.componentAt(i).x);
        }
        assertEquals(500, seen.size());
    }

    @Test
    void putReplacesExistingComponent() {
        SparseSetStore<Position> store = new SparseSetStore<>();
        store.put(7, new Position(1));
        store.put(7, new Position(2));
        assertEquals(1, store.size());
        assertEquals(2, store.get(7).x);
        assertNull(store.get(-1));
        assertNull(store.get(100_000));
    }
}
