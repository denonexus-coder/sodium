#version 330 core

in vec2 a_Pos;

out vec2 v_Ndc;

void main() {
    // A single triangle that covers the whole viewport. The sky is evaluated per fragment from
    // the inverse projection, so it follows the camera without a dome mesh.
    v_Ndc = a_Pos;
    gl_Position = vec4(a_Pos, 1.0, 1.0);
}
