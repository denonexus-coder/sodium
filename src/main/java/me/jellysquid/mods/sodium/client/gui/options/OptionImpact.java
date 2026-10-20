package me.jellysquid.mods.sodium.client.gui.options;

import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public enum OptionImpact implements TextProvider {
    LOW(Formatting.GREEN, 0xFF6BD98A, "sodium.option_impact.low"),
    MEDIUM(Formatting.YELLOW, 0xFFF2C94C, "sodium.option_impact.medium"),
    HIGH(Formatting.GOLD, 0xFFFF7A59, "sodium.option_impact.high"),
    VARIES(Formatting.WHITE, 0xFF9AB8FF, "sodium.option_impact.varies");

    private final Text text;
    private final int color;

    OptionImpact(Formatting color, int rgb, String text) {
        this.text = Text.translatable(text).formatted(color);
        this.color = rgb;
    }

    @Override
    public Text getLocalizedName() {
        return this.text;
    }

    /**
     * Opaque colour for the small marker drawn on the row and for the badge in the detail pane.
     * The formatting above is kept for the old tooltip path, which still uses chat colours.
     */
    public int getColor() {
        return this.color;
    }
}
