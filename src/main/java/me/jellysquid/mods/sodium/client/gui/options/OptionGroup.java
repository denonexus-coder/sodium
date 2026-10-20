package me.jellysquid.mods.sodium.client.gui.options;

import com.google.common.collect.ImmutableList;
import net.minecraft.text.Text;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class OptionGroup {
    private final ImmutableList<Option<?>> options;
    private final @Nullable Text name;

    private OptionGroup(ImmutableList<Option<?>> options, @Nullable Text name) {
        this.options = options;
        this.name = name;
    }

    public static Builder createBuilder() {
        return new Builder();
    }

    public ImmutableList<Option<?>> getOptions() {
        return this.options;
    }

    /**
     * The heading shown above this group in the options screen, or {@code null} for an unlabelled
     * group. Groups without a name keep the old behaviour: just a small gap between rows.
     */
    public @Nullable Text getName() {
        return this.name;
    }

    public static class Builder {
        private final List<Option<?>> options = new ArrayList<>();
        private @Nullable Text name;

        public Builder setName(Text name) {
            this.name = name;

            return this;
        }

        public Builder add(Option<?> option) {
            this.options.add(option);

            return this;
        }

        public OptionGroup build() {
            Validate.notEmpty(this.options, "At least one option must be specified");

            return new OptionGroup(ImmutableList.copyOf(this.options), this.name);
        }
    }
}
