package me.jellysquid.mods.sodium.client.gui.vanillin;

import java.util.ArrayList;
import java.util.List;

import com.google.common.collect.ImmutableList;

import me.jellysquid.mods.sodium.client.gui.options.Option;
import me.jellysquid.mods.sodium.client.gui.options.OptionFlag;
import me.jellysquid.mods.sodium.client.gui.options.OptionGroup;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpact;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpl;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import me.jellysquid.mods.sodium.client.gui.options.control.CyclingControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

/**
 * Vanillin's configuration, as a page of Sodium's options screen.
 *
 * <p>Vanillin decides which vanilla objects the Flywheel engine instances instead of vanilla
 * rendering them. Every visual it knows about appears here as a three-state control, enumerated
 * from Vanillin's own registry rather than a hardcoded list, so the page cannot go stale against
 * the mod it configures.
 *
 * <p>The three states are not two settings wearing a costume. {@code Default} hands the decision
 * back to Vanillin, which turns on what it ships as stable and leaves the experimental visuals off
 * in a production environment. {@code Disable} removes the visualizer entirely, so the object goes
 * down the vanilla path exactly as if Vanillin were not installed. {@code Force enable} installs
 * one even where a mod has asked Vanillin to leave that object alone.
 *
 * <p>Every option carries {@link OptionFlag#REQUIRES_GAME_RESTART}, because Vanillin builds its
 * visualizer registry once during mod startup. Apply does push the new state into the registry so
 * objects visualised afterwards pick it up, but an object already on screen keeps the visual it
 * has until the world reloads, and saying so is more useful than implying the change is complete.
 */
public final class VanillinOptionsPage {
	private VanillinOptionsPage() {
	}

	/**
	 * Builds the page, or returns {@code null} when Vanillin is not installed so the menu does not
	 * grow a tab full of rows that cannot be reached.
	 */
	@Nullable
	public static OptionPage create() {
		if (!VanillinCompat.PRESENT) {
			return null;
		}

		List<OptionGroup> groups = new ArrayList<>();

		groups.add(OptionGroup.createBuilder()
				.add(introRow())
				.build());

		List<Option<?>> blockEntities = new ArrayList<>();
		for (VanillinCompat.Entry entry : VanillinCompat.blockEntityEntries()) {
			blockEntities.add(visualOption(entry));
		}

		addGroup(groups, blockEntities);

		List<Option<?>> entities = new ArrayList<>();
		for (VanillinCompat.Entry entry : VanillinCompat.entityEntries()) {
			entities.add(visualOption(entry));
		}

		addGroup(groups, entities);

		return new OptionPage(Text.translatable("sodium.options.pages.vanillin"), ImmutableList.copyOf(groups));
	}

	/**
	 * Wraps a list of options in a group, skipping it when empty. A group with no options renders
	 * as a bare heading with nothing under it, which reads as a bug rather than as an absence.
	 */
	private static void addGroup(List<OptionGroup> groups, List<Option<?>> options) {
		if (options.isEmpty()) {
			return;
		}

		OptionGroup.Builder builder = OptionGroup.createBuilder();
		for (Option<?> option : options) {
			builder.add(option);
		}

		groups.add(builder.build());
	}

	/**
	 * Shown, never switched. Vanillin has no master switch -- each visual stands on its own -- so
	 * this row reports what the page is for instead of pretending to gate rows behind nothing.
	 */
	private static Option<Boolean> introRow() {
		return OptionImpl.createBuilder(boolean.class, VanillinOptionsStorage.INSTANCE)
				.setName(Text.translatable("sodium.options.vanillin.instancing.name"))
				.setTooltip(Text.translatable("sodium.options.vanillin.instancing.tooltip"))
				.setControl(TickBoxControl::new)
				.setEnabled(false)
				.setBinding((data, value) -> {
				}, data -> !VanillinCompat.entries().isEmpty())
				.build();
	}

	/**
	 * One visual, as a three-state control.
	 *
	 * <p>The name is built from Vanillin's registry path rather than a translation key, because the
	 * set of visuals is discovered at runtime and a missing translation would render as the raw key
	 * on screen. The tooltip carries the one thing a label cannot: whether Vanillin turns this on
	 * when the file has no opinion.
	 */
	private static Option<VanillinState> visualOption(VanillinCompat.Entry entry) {
		return OptionImpl.createBuilder(VanillinState.class, VanillinOptionsStorage.INSTANCE)
				.setName(Text.literal(entry.label()))
				.setTooltip(tooltip(entry))
				.setControl(option -> new CyclingControl<>(option, VanillinState.class, new Text[]{
						Text.translatable("sodium.options.vanillin.state.default"),
						Text.translatable("sodium.options.vanillin.state.disable"),
						Text.translatable("sodium.options.vanillin.state.force_enable"),
				}))
				.setImpact(OptionImpact.MEDIUM)
				.setBinding((data, value) -> VanillinCompat.set(entry, value),
						data -> VanillinCompat.get(entry))
				// Vanillin builds its registry at startup, and an object already visualised keeps
				// its visual until the world reloads.
				.setFlags(OptionFlag.REQUIRES_GAME_RESTART)
				.build();
	}

	/**
	 * The default is spelled out on the row rather than inferred from the current value, because
	 * the two are different things: an entry still on {@code Default} has never been touched, and
	 * an experimental visual sitting on {@code Default} is off in a production environment.
	 */
	private static Text tooltip(VanillinCompat.Entry entry) {
		// MutableText, not Text: append() lives on the mutable type, so holding the result as a
		// Text would compile the first line and then fail to find append on the ones after it.
		MutableText out = Text.empty()
				.append(Text.translatable("sodium.options.vanillin.tooltip.prefix"))
				.append(Text.literal("\n\n"));

		if (entry.enabledByDefault()) {
			out = out.append(Text.translatable("sodium.options.vanillin.tooltip.stable"));
		} else {
			out = out.append(Text.translatable("sodium.options.vanillin.tooltip.experimental"));
		}

		return out.append(Text.literal("\n"))
				.append(Text.translatable("sodium.options.vanillin.tooltip.restart"))
				.append(Text.literal("\n"))
				.append(Text.literal(entry.key()));
	}
}
