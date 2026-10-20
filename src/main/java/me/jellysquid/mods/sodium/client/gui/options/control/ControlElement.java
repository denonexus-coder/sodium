package me.jellysquid.mods.sodium.client.gui.options.control;

import me.jellysquid.mods.sodium.client.gui.options.Option;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpact;
import me.jellysquid.mods.sodium.client.gui.widgets.AbstractWidget;
import me.jellysquid.mods.sodium.client.util.Dim2i;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.navigation.GuiNavigation;
import net.minecraft.client.gui.navigation.GuiNavigationPath;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.Nullable;

public class ControlElement<T> extends AbstractWidget {
    protected final Option<T> option;

    protected final Dim2i dim;

    public ControlElement(Option<T> option, Dim2i dim) {
        this.option = option;
        this.dim = dim;
    }

    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, float delta) {
        String name = this.option.getName().getString();
        String label;

        // Trim against the real space left of the control instead of cutting at ten characters:
        // the old rule turned every long Portuguese name into an unreadable fragment.
        int nameSpace = this.dim.width() - this.option.getControl().getMaxWidth() - 24;

        if (this.font.getWidth(name) > nameSpace) {
            name = this.font.trimToWidth(name, Math.max(24, nameSpace - this.font.getWidth("..."))) + "...";
        }

        if (this.option.isAvailable()) {
            if (this.option.hasChanged()) {
                label = Formatting.ITALIC + name + " *";
            } else {
                label = Formatting.WHITE + name;
            }
        } else {
            label = String.valueOf(Formatting.GRAY) + Formatting.STRIKETHROUGH + name;
        }

        this.hovered = this.dim.containsCursor(mouseX, mouseY);

        // A flat strip with a small impact marker is cheaper to draw than a card, and it keeps the
        // row readable at GUI scale 3 and 4, which is what phones use.
        this.drawRect(drawContext, this.dim.x(), this.dim.y(), this.dim.getLimitX(), this.dim.getLimitY(), this.hovered ? 0xD0171E2A : 0x9010141C);

        OptionImpact impact = this.option.getImpact();

        if (impact != null) {
            this.drawRect(drawContext, this.dim.x() + 5, this.dim.getCenterY() - 1, this.dim.x() + 7, this.dim.getCenterY() + 1, impact.getColor());
        }

        this.drawString(drawContext, label, this.dim.x() + 12, this.dim.getCenterY() - 4, 0xFFFFFFFF);

        if (this.isFocused()) {
            this.drawBorder(drawContext, this.dim.x(), this.dim.y(), this.dim.getLimitX(), this.dim.getLimitY(), -1);
        }
    }

    public Option<T> getOption() {
        return this.option;
    }

    public Dim2i getDimensions() {
        return this.dim;
    }

    @Override
    public @Nullable GuiNavigationPath getNavigationPath(GuiNavigation navigation) {
        if (!this.option.isAvailable())
            return null;
        return super.getNavigationPath(navigation);
    }

    @Override
    public ScreenRect getNavigationFocus() {
        return new ScreenRect(this.dim.x(), this.dim.y(), this.dim.width(), this.dim.height());
    }
}
