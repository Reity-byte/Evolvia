package evolvia.world;

/**
 * Resource type, loaded from {@code data/resources.json}.
 *
 * @param index           position in the {@link ResourceTable}
 * @param id              stable identifier
 * @param name            display name (Czech)
 * @param kind            food or water
 * @param foodType        what kind of food ({@code plant}, {@code meat}); a species' diet lists the types it eats
 * @param nutrition       hunger relief multiplier per unit (1 = the species' base value)
 * @param capacity        maximum amount (food units); unused for water
 * @param regrowPerSecond amount regrown per second at fertility 1 (scaled by the tile's fertility)
 * @param decayPerSecond  amount lost per second; a decaying node disappears when empty (e.g. carcasses)
 * @param spawnOnDeath    a node of this type appears where a creature dies (carcass)
 * @param spawnDensity    chance per land tile at fertility 1 that a node spawns at world start (food only)
 * @param size            render size in tiles
 * @param rgb             color when full, 0xRRGGBB
 * @param emptyRgb        color when empty, 0xRRGGBB
 */
public record ResourceDefinition(
        int index,
        String id,
        String name,
        ResourceKind kind,
        String foodType,
        float nutrition,
        float capacity,
        float regrowPerSecond,
        float decayPerSecond,
        boolean spawnOnDeath,
        float spawnDensity,
        float size,
        int rgb,
        int emptyRgb) {

    /** True if nodes of this type disappear when empty. */
    public boolean decays() {
        return decayPerSecond > 0;
    }
}
