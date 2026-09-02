package dev.vibeported.capture.example;

import static org.lwjgl.opengl.GL30C.*;

/** A rotating triangle. Core-profile GL 3.3, so nothing here is legacy. */
final class Triangle implements AutoCloseable {

    private static final String VERTEX_SHADER = """
            #version 330 core
            layout(location = 0) in vec2 position;
            layout(location = 1) in vec3 color;
            uniform float angle;
            out vec3 vertexColor;
            void main() {
                float s = sin(angle), c = cos(angle);
                vec2 rotated = vec2(position.x * c - position.y * s,
                                    position.x * s + position.y * c);
                gl_Position = vec4(rotated.x * 0.5625, rotated.y, 0.0, 1.0);
                vertexColor = color;
            }
            """;

    private static final String FRAGMENT_SHADER = """
            #version 330 core
            in vec3 vertexColor;
            out vec4 fragColor;
            void main() {
                fragColor = vec4(vertexColor, 1.0);
            }
            """;

    private final int program;
    private final int vao;
    private final int vbo;
    private final int angleUniform;

    Triangle() {
        int vs = compile(GL_VERTEX_SHADER, VERTEX_SHADER);
        int fs = compile(GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) != GL_TRUE) {
            throw new IllegalStateException("Link failed: " + glGetProgramInfoLog(program));
        }
        glDeleteShader(vs);
        glDeleteShader(fs);
        angleUniform = glGetUniformLocation(program, "angle");

        float[] vertices = {
                //  x      y      r     g     b
                 0.0f,  0.8f,  1.0f, 0.2f, 0.2f,
                -0.7f, -0.6f,  0.2f, 1.0f, 0.3f,
                 0.7f, -0.6f,  0.3f, 0.4f, 1.0f,
        };

        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);
        int stride = 5 * Float.BYTES;
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glBindVertexArray(0);
    }

    void draw(float angle) {
        glClearColor(0.06f, 0.07f, 0.10f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glUseProgram(program);
        glUniform1f(angleUniform, angle);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);
        glUseProgram(0);
    }

    private static int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) != GL_TRUE) {
            throw new IllegalStateException("Compile failed: " + glGetShaderInfoLog(shader));
        }
        return shader;
    }

    @Override
    public void close() {
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        glDeleteProgram(program);
    }
}
