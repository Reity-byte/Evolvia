package evolvia.render;

import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL33C.*;

/**
 * GLSL program built from a vertex + fragment shader pair on the classpath.
 */
public final class Shader implements AutoCloseable {

    private final String name;
    private final int program;
    private final Map<String, Integer> uniformLocations = new HashMap<>();

    /**
     * Loads {@code <basePath>.vert} and {@code <basePath>.frag} from the classpath,
     * e.g. {@code fromResources("shaders/basic")}.
     */
    public static Shader fromResources(String basePath) {
        return new Shader(basePath,
                readResource(basePath + ".vert"),
                readResource(basePath + ".frag"));
    }

    public Shader(String name, String vertexSource, String fragmentSource) {
        this.name = name;
        int vertex = compile(GL_VERTEX_SHADER, vertexSource, name + ".vert");
        int fragment = compile(GL_FRAGMENT_SHADER, fragmentSource, name + ".frag");

        program = glCreateProgram();
        glAttachShader(program, vertex);
        glAttachShader(program, fragment);
        glLinkProgram(program);
        glDeleteShader(vertex);
        glDeleteShader(fragment);

        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(program);
            glDeleteProgram(program);
            throw new IllegalStateException("Failed to link shader '" + name + "':\n" + log);
        }
    }

    private static int compile(int type, String source, String label) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException("Failed to compile shader '" + label + "':\n" + log);
        }
        return shader;
    }

    private static String readResource(String path) {
        try (InputStream in = Shader.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Shader resource not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read shader resource: " + path, e);
        }
    }

    public void bind() {
        glUseProgram(program);
    }

    public void setUniform(String uniform, Matrix4fc value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniformMatrix4fv(location(uniform), false, value.get(stack.mallocFloat(16)));
        }
    }

    public void setUniform(String uniform, float value) {
        glUniform1f(location(uniform), value);
    }

    private int location(String uniform) {
        return uniformLocations.computeIfAbsent(uniform, u -> glGetUniformLocation(program, u));
    }

    @Override
    public void close() {
        glDeleteProgram(program);
    }

    @Override
    public String toString() {
        return "Shader[" + name + "]";
    }
}
