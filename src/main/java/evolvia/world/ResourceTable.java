package evolvia.world;

import java.util.List;

/**
 * All resource types. There must be exactly one water type (placed on every shore tile).
 */
public final class ResourceTable {

    private final List<ResourceDefinition> all;
    private final List<ResourceDefinition> food;
    private final ResourceDefinition water;

    /** @throws IllegalStateException if there is not exactly one water resource */
    public ResourceTable(List<ResourceDefinition> resources, String source) {
        this.all = List.copyOf(resources);
        this.food = all.stream().filter(r -> r.kind() == ResourceKind.FOOD).toList();
        List<ResourceDefinition> waters = all.stream().filter(r -> r.kind() == ResourceKind.WATER).toList();
        if (waters.size() != 1) {
            throw new IllegalStateException(source + ": exactly one resource of kind \"water\" is required, found " + waters.size());
        }
        this.water = waters.get(0);
    }

    public List<ResourceDefinition> all() {
        return all;
    }

    public List<ResourceDefinition> food() {
        return food;
    }

    public ResourceDefinition water() {
        return water;
    }
}
