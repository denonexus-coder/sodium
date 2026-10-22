const uint MATERIAL_USE_MIP_OFFSET = 0u;
const uint MATERIAL_ALPHA_CUTOFF_OFFSET = 1u;
const uint MATERIAL_WATER_OFFSET = 3u;
const uint MATERIAL_TRANSLUCENT_OFFSET = 4u;

const float[4] ALPHA_CUTOFF = float[4](0.0, 0.1, 0.5, 1.0);

float _material_mip_bias(uint material) {
    return ((material >> MATERIAL_USE_MIP_OFFSET) & 1u) != 0u ? 0.0 : -4.0;
}

float _material_alpha_cutoff(uint material) {
    return ALPHA_CUTOFF[(material >> MATERIAL_ALPHA_CUTOFF_OFFSET) & 3u];
}

bool _material_water(uint material) {
    return ((material >> MATERIAL_WATER_OFFSET) & 1u) != 0u;
}

bool _material_translucent(uint material) {
    return ((material >> MATERIAL_TRANSLUCENT_OFFSET) & 1u) != 0u;
}