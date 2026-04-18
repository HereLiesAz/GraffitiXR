#pragma once
#include <glm/glm.hpp>
#include <glm/gtc/type_ptr.hpp>

static inline void camToWorld(const float* V_mat, float xc, float yc, float zc,
                              float& xw, float& yw, float& zw) {
    glm::mat4 V = glm::make_mat4(V_mat);
    glm::mat4 CV = glm::inverse(V);
    glm::vec4 pw = CV * glm::vec4(xc, yc, zc, 1.0f);
    xw = pw.x; yw = pw.y; zw = pw.z;
}

static inline GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}
