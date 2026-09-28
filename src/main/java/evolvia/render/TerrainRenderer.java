package evolvia.render;

import evolvia.world.Biome;
import evolvia.world.Nature;
import evolvia.world.Terrain;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Renders the terrain as a grid of chunk meshes; a chunk is rebuilt alone when the terrain reports
 * a change in it (terraforming, {@link Terrain#blockRevision}). Each tile has its own four vertices so it gets one flat biome color;
 * normals are per-vertex, computed from the heightmap.
 * <p>
 * Also draws a flat "ocean floor" around the map so the sea continues past the border.
 */
public final class TerrainRenderer implements AutoCloseable {

    /** Chunk size in tiles. */
    public static final int CHUNK_SIZE = Terrain.REVISION_BLOCK;
    /** Vertex layout: position (3), normal (3), color (3). */
    private static final int FLOATS_PER_VERTEX = 9;
    /** Per-tile brightness variation for a less uniform low-poly look. */
    private static final float COLOR_JITTER = 0.05f;
    /** Seabed is darkened with depth down to this factor at the deepest point. */
    private static final float DEEP_SEABED_BRIGHTNESS = 0.45f;

    private final Shader shader;
    private final Terrain terrain;
    private final List<Mesh> chunks = new ArrayList<>();
    /** Terrain block revision each chunk was built from. */
    private final List<Integer> builtRevisions = new ArrayList<>();
    private final Mesh oceanFloor;
    /** Scorched ground per tile (phase 9e), sampled by the terrain shader. */
    private final int scorchTexture;
    private final ByteBuffer scorch;
    private boolean scorchClear = true;

    public TerrainRenderer(Terrain terrain) {
        this.terrain = terrain;
        shader = Shader.fromResources("shaders/terrain");
        for (int cz = 0; cz < terrain.depth(); cz += CHUNK_SIZE) {
            for (int cx = 0; cx < terrain.width(); cx += CHUNK_SIZE) {
                chunks.add(buildChunk(terrain, cx, cz));
                builtRevisions.add(terrain.blockRevision(cx, cz));
            }
        }
        oceanFloor = buildOceanFloor(terrain);
        scorch = MemoryUtil.memCalloc(terrain.width() * terrain.depth());
        scorchTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, scorchTexture);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, terrain.width(), terrain.depth(), 0, GL_RED, GL_UNSIGNED_BYTE, scorch);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    /** Copies the burning and burnt tiles into the scorch texture (nothing to do while there are none). */
    private void updateScorch(Nature nature, int tick) {
        if (!nature.hasBurntGround() && scorchClear) {
            return;
        }
        boolean any = false;
        for (int i = 0; i < nature.tileCount(); i++) {
            int value = Math.round(nature.scorch(i, tick) * 255f);
            scorch.put(i, (byte) value);
            any |= value > 0;
        }
        scorchClear = !any;
        glBindTexture(GL_TEXTURE_2D, scorchTexture);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, terrain.width(), terrain.depth(), GL_RED, GL_UNSIGNED_BYTE, scorch);
    }

    public void render(Camera camera, Lighting lighting, Nature nature, int tick) {
        rebuildChangedChunks();
        updateScorch(nature, tick);
        shader.bind();
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, scorchTexture);
        shader.setUniform("uScorch", 0);
        shader.setUniform("uMapSize", (float) terrain.width(), (float) terrain.depth());
        shader.setUniform("uProjection", camera.projection());
        shader.setUniform("uView", camera.view());
        lighting.apply(shader, camera);
        for (Mesh chunk : chunks) {
            chunk.draw();
        }
        oceanFloor.draw();
    }

    private void rebuildChangedChunks() {
        int index = 0;
        for (int cz = 0; cz < terrain.depth(); cz += CHUNK_SIZE) {
            for (int cx = 0; cx < terrain.width(); cx += CHUNK_SIZE) {
                int revision = terrain.blockRevision(cx, cz);
                if (revision != builtRevisions.get(index)) {
                    chunks.get(index).close();
                    chunks.set(index, buildChunk(terrain, cx, cz));
                    builtRevisions.set(index, revision);
                }
                index++;
            }
        }
    }

    private static Mesh buildChunk(Terrain terrain, int x0, int z0) {
        int x1 = Math.min(x0 + CHUNK_SIZE, terrain.width());
        int z1 = Math.min(z0 + CHUNK_SIZE, terrain.depth());
        int tiles = (x1 - x0) * (z1 - z0);
        float[] vertices = new float[tiles * 4 * FLOATS_PER_VERTEX];
        int[] indices = new int[tiles * 6];
        int v = 0;
        int i = 0;
        int vertex = 0;
        float[] color = new float[3];

        for (int tz = z0; tz < z1; tz++) {
            for (int tx = x0; tx < x1; tx++) {
                tileColor(terrain, tx, tz, color);
                // Corners: 0 = (tx, tz), 1 = (tx+1, tz), 2 = (tx+1, tz+1), 3 = (tx, tz+1)
                v = putVertex(vertices, v, terrain, tx, tz, color);
                v = putVertex(vertices, v, terrain, tx + 1, tz, color);
                v = putVertex(vertices, v, terrain, tx + 1, tz + 1, color);
                v = putVertex(vertices, v, terrain, tx, tz + 1, color);
                // Two counter-clockwise (seen from above) triangles split along 0-2; must match Terrain.heightAt.
                indices[i++] = vertex;
                indices[i++] = vertex + 3;
                indices[i++] = vertex + 2;
                indices[i++] = vertex;
                indices[i++] = vertex + 2;
                indices[i++] = vertex + 1;
                vertex += 4;
            }
        }
        return new Mesh(vertices, indices, 3, 3, 3);
    }

    private static int putVertex(float[] vertices, int v, Terrain terrain, int cx, int cz, float[] color) {
        // Normal from central differences of the corner heights (grid spacing 1).
        float left = terrain.cornerHeight(cx - 1, cz);
        float right = terrain.cornerHeight(cx + 1, cz);
        float back = terrain.cornerHeight(cx, cz - 1);
        float front = terrain.cornerHeight(cx, cz + 1);
        float nx = left - right;
        float ny = 2f;
        float nz = back - front;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);

        vertices[v++] = cx;
        vertices[v++] = terrain.cornerHeight(cx, cz);
        vertices[v++] = cz;
        vertices[v++] = nx / length;
        vertices[v++] = ny / length;
        vertices[v++] = nz / length;
        vertices[v++] = color[0];
        vertices[v++] = color[1];
        vertices[v++] = color[2];
        return v;
    }

    private static void tileColor(Terrain terrain, int tx, int tz, float[] out) {
        Biome biome = terrain.biome(tx, tz);
        float brightness = 1f + COLOR_JITTER * hashToSigned(tx, tz);
        if (biome.water()) {
            brightness *= seabedBrightness(terrain, terrain.tileHeight(tx, tz));
        }
        out[0] = Math.min(biome.red() * brightness, 1f);
        out[1] = Math.min(biome.green() * brightness, 1f);
        out[2] = Math.min(biome.blue() * brightness, 1f);
    }

    /** 1 at sea level, {@link #DEEP_SEABED_BRIGHTNESS} at height 0. */
    private static float seabedBrightness(Terrain terrain, float height) {
        float depth01 = Math.clamp(1f - height / terrain.seaLevel(), 0f, 1f);
        return 1f - (1f - DEEP_SEABED_BRIGHTNESS) * depth01;
    }

    /** Deterministic pseudo-random value in [-1, 1] for a tile (visual only, not simulation). */
    private static float hashToSigned(int x, int z) {
        int h = x * 73856093 ^ z * 19349663;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return (h & 0xFFFF) / 32767.5f - 1f;
    }

    /**
     * A large quad slightly below height 0 around the whole map, colored like the deepest seabed.
     * The map border is at height 0 (edge falloff), so the seam is hidden under the water.
     */
    private static Mesh buildOceanFloor(Terrain terrain) {
        Biome water = terrain.biomeTable().water();
        float brightness = seabedBrightness(terrain, 0f);
        float r = water.red() * brightness;
        float g = water.green() * brightness;
        float b = water.blue() * brightness;

        float m = WaterRenderer.OCEAN_MARGIN;
        float x0 = -m;
        float z0 = -m;
        float x1 = terrain.width() + m;
        float z1 = terrain.depth() + m;
        float y = -0.05f;
        float[] vertices = {
                x0, y, z0, 0, 1, 0, r, g, b,
                x0, y, z1, 0, 1, 0, r, g, b,
                x1, y, z1, 0, 1, 0, r, g, b,
                x1, y, z0, 0, 1, 0, r, g, b,
        };
        int[] indices = {0, 1, 2, 0, 2, 3};
        return new Mesh(vertices, indices, 3, 3, 3);
    }

    @Override
    public void close() {
        glDeleteTextures(scorchTexture);
        MemoryUtil.memFree(scorch);
        for (Mesh chunk : chunks) {
            chunk.close();
        }
        oceanFloor.close();
        shader.close();
    }
}
