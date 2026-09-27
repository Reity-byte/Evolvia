package evolvia.world;

/**
 * Resource type, loaded from {@code data/resources.json}.
 *
 * @param index           position in the {@link ResourceTable}
 * @param id              stable identifier
 * @param name            display name (Czech)
 * @param kind            food or water
 * @param capacity        maximum amount (food units); unused for water
 * @param regrowPerSecond amount regrown per second at fertility 1 (scaled by the tile's fertility)
 * @param spawnDensity    chance per land tile at fertility 1 that a node spawns there (food only)
 * @param size            render size in tiles
 * @param rgb             color when full, 0xRRGGBB
 * @param emptyRgb        color when empty, 0xRRGGBB
 */
public record ResourceDefinition(
        int index,
        String id,
        String name,
        ResourceKind kind,
        float capacity,
        float regrowPerSecond,
        float spawnDensity,
        float size,
        int rgb,
        int emptyRgb) {
}
