package me.jellysquid.mods.sodium.client.gui.flywheel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.google.common.collect.ImmutableList;

import me.jellysquid.mods.sodium.client.gui.options.Option;
import me.jellysquid.mods.sodium.client.gui.options.OptionFlag;
import me.jellysquid.mods.sodium.client.gui.options.OptionGroup;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpact;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpl;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import me.jellysquid.mods.sodium.client.gui.options.control.CyclingControl;
import me.jellysquid.mods.sodium.client.gui.options.control.SliderControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

/**
 * Flywheel's configuration, as a page of Sodium's options screen.
 *
 * <p>The whole of {@code flywheel.json} is reachable from here rather than from an editor: the
 * master switch, the instancing knobs, the transparency switches and the shader settings, each with
 * the effect it actually has written into its tooltip.
 *
 * <p>Everything below the master switch is wrapped in a {@link GatedOption} whose gate reads the
 * master's current, not yet applied value. That is what makes the page react while it is still
 * being edited: untick Flywheel and the rest of the page greys out immediately, before Apply is
 * pressed, and tick it again and everything comes back with the choices it had.
 *
 * <p>The flags on each option are not decoration. They are set from where the value is read in the
 * backend: an option consulted while the renderer rebuilds carries
 * {@link OptionFlag#REQUIRES_RENDERER_RELOAD}, one read per frame or per particle carries nothing
 * and simply starts working, and one whose worker pool is created once for the life of the process
 * carries {@link OptionFlag#REQUIRES_GAME_RESTART}, because applying it would otherwise look like
 * it did nothing.
 */
public final class FlywheelOptionsPage {
	private FlywheelOptionsPage() {
	}

	/**
	 * Builds the page, or returns {@code null} when Flywheel is not installed so the menu does not
	 * grow an empty tab.
	 */
	@Nullable
	public static OptionPage create() {
		if (!FlywheelCompat.PRESENT) {
			return null;
		}

		List<OptionGroup> groups = new ArrayList<>();

		// Built first because every other row asks it whether it is on. getValue() is the value
		// currently shown on screen, not the applied one, so the gating follows the user's edit.
		Option<Boolean> master = OptionImpl.createBuilder(boolean.class, FlywheelOptionsStorage.INSTANCE)
				.setName(Text.translatable("sodium.options.flywheel.enabled.name"))
				.setTooltip(Text.translatable("sodium.options.flywheel.enabled.tooltip"))
				.setControl(TickBoxControl::new)
				.setImpact(OptionImpact.HIGH)
				.setBinding((data, value) -> FlywheelCompat.enabled(value), data -> FlywheelCompat.enabled())
				.setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
				.build();

		groups.add(OptionGroup.createBuilder()
				.add(master)
				.add(statusRow())
				.build());

		BooleanSupplier engine = master::getValue;

		groups.add(OptionGroup.createBuilder()
				.add(gated(workerThreadsOption(), engine))
				.add(gated(limitUpdatesOption(), engine))
				.build());

		groups.add(OptionGroup.createBuilder()
				.add(gated(lightSmoothnessOption(), engine))
				.add(gated(orphanUploadOption(), engine))
				.build());

		groups.add(OptionGroup.createBuilder()
				.add(gated(oitOption(), engine))
				.add(gated(disableParticlesOption(), engine))
				.add(gated(freezeGuiOption(), engine))
				.build());

		return new OptionPage(Text.translatable("sodium.options.pages.flywheel"), ImmutableList.copyOf(groups));
	}

	// ------------------------------------------------------------------ engine

	/**
	 * Shader precompilation threads. {@code -1} hands the choice to Flywheel, {@code 0} keeps
	 * compilation on the render thread. The pool is created once and cached, so applying this one
	 * needs a restart rather than a reload.
	 */
	private static Option<Integer> workerThreadsOption() {
		return OptionImpl.createBuilder(int.class, FlywheelOptionsStorage.INSTANCE)
				.setName(Text.translatable("sodium.options.flywheel.worker_threads.name"))
				.setTooltip(Text.translatable("sodium.options.flywheel.worker_threads.tooltip"))
				.setControl(option -> new SliderControl(option, FlywheelCompat.workerThreadsMin(),
						FlywheelCompat.workerThreadsMax(), 1, value -> {
							if (value < 0) {
								return Text.translatable("sodium.options.flywheel.worker_threads.value.auto");
							}
							if (value == 0) {
								return Text.translatable("sodium.options.flywheel.worker_threads.value.off");
							}
							return Text.literal(Integer.toString(value));
						}))
				.setImpact(OptionImpact.LOW)
				.setBinding((data, value) -> FlywheelCompat.workerThreads(value), data -> FlywheelCompat.workerThreads())
				.setFlags(OptionFlag.REQUIRES_GAME_RESTART)
				.build();
	}

	/**
	 * Whether distant objects are still updated every frame. On by default, and the switch behind
	 * one of the larger CPU wins in a build with a lot of machinery in the world.
	 */
	private static Option<Boolean> limitUpdatesOption() {
		return OptionImpl.createBuilder(boolean.class, FlywheelOptionsStorage.INSTANCE)
				.setName(Text.translatable("sodium.options.flywheel.limit_updates.name"))
				.setTooltip(Text.translatable("sodium.options.flywheel.limit_updates.tooltip"))
				.setControl(TickBoxControl::new)
				.setImpact(OptionImpact.MEDIUM)
				.setBinding((data, value) -> FlywheelCompat.limitUpdates(value), data -> FlywheelCompat.limitUpdates())
				.setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
				.build();
	}

	// -------------------------------------------------------------- instancing

	/**
	 * How light is read across a face. Part of the shader key, so a change recompiles the pipelines
	 * and is worth flagging as a reload.
	 */
	private static Option<FlywheelLightSmoothness> lightSmoothnessOption() {
		return OptionImpl.createBuilder(FlywheelLightSmoothness.class, FlywheelOptionsStorage.INSTANCE)
				.setName(Text.translatable("sodium.options.flywheel.light_smoothness.name"))
				.setTooltip(Text.translatable("sodium.options.flywheel.light_smoothness.tooltip"))
				.setControl(option -> new CyclingControl<>(option, FlywheelLightSmoothness.class, new Text[]{
						Text.translatable("sodium.options.flywheel.light_smoothness.flat"),
						Text.translatable("sodium.options.flywheel.light_smoothness.tri_linear"),
						Text.translatable("sodium.options.flywheel.light_smoothness.smooth"),
						Text.translatable("sodium.options.flywheel.light_smoothness.smooth_inner_face_corrected"),
				}))
				.setImpact(OptionImpact.HIGH)
				.setBinding((data, value) -> FlywheelCompat.lightSmoothness(value.name()),
						data -> FlywheelLightSmoothness.fromConfig())
				.setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
				.build();
	}

	/**
	 * Read inside the instance upload itself rather than when the backend is built, so it takes
	 * effect on the next frame of a running world and needs no reload at all.
	 */
	private static Option<Boolean> orphanUploadOption() {
		return OptionImpl.createBuilder(boolean.class, FlywheelOptionsStorage.INSTANCE)
				.setName(Text.translatable("sodium.options.flywheel.orphan_upload.name"))
				.setTooltip(Text.translatable("sodium.options.flywheel.orphan_upload.tooltip"))
				.setControl(TickBoxControl::new)
				.setImpact(OptionImpact.HIGH)
				.setBinding((data, value) -> FlywheelCompat.orphanInstanceUpload(value),
						data -> FlywheelCompat.orphanInstanceUpload())
				.build();
	}

	// -------------------------------------------------- transparency and effects

	/**
	 * The framebuffer for order-independent transparency is allocated when the instancing engine is
	 * constructed, so this one has to flag a reload rather than pretend to apply on the spot.
	 */
	private static Option<Boolean> oitOption() {
		return OptionImpl.createBuilder(boolean.class, FlywheelOptionsStorage.INSTANCE)
				.setName(Text.translatable("sodium.options.flywheel.oit.name"))
				.setTooltip(Text.translatable("sodium.options.flywheel.oit.tooltip"))
				.setControl(TickBoxControl::new)
				.setImpact(OptionImpact.HIGH)
				.setBinding((data, value) -> FlywheelCompat.oit(value), data -> FlywheelCompat.oit())
				.setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
				.build();
	}

	private static Option<Boolean> disableParticlesOption() {
		return OptionImpl.createBuilder(boolean.class, FlywheelOptionsStorage.INSTANCE)
				.setName(Text.translatable("sodium.options.flywheel.disable_particles.name"))
				.setTooltip(Text.translatable("sodium.options.flywheel.disable_particles.tooltip"))
				.setControl(TickBoxControl::new)
				.setImpact(OptionImpact.MEDIUM)
				.setBinding((data, value) -> FlywheelCompat.disableParticles(value),
						data -> FlywheelCompat.disableParticles())
				.build();
	}

	private static Option<Boolean> freezeGuiOption() {
		return OptionImpl.createBuilder(boolean.class, FlywheelOptionsStorage.INSTANCE)
				.setName(Text.translatable("sodium.options.flywheel.freeze_gui.name"))
				.setTooltip(Text.translatable("sodium.options.flywheel.freeze_gui.tooltip"))
				.setControl(TickBoxControl::new)
				.setImpact(OptionImpact.LOW)
				.setBinding((data, value) -> FlywheelCompat.freezeGuiItemAnimation(value),
						data -> FlywheelCompat.freezeGuiItemAnimation())
				.build();
	}

	// -------------------------------------------------------------------- status

	/**
	 * Shown, never switched. The driver is identified once from the GL strings, so this row is
	 * built as a tick box that refuses input: it reports what was detected instead of offering a
	 * change that would be written nowhere.
	 */
	private static Option<Boolean> statusRow() {
		return OptionImpl.createBuilder(boolean.class, FlywheelOptionsStorage.INSTANCE)
				.setName(Text.translatable("sodium.options.flywheel.mobile_driver.name"))
				.setTooltip(Text.translatable("sodium.options.flywheel.mobile_driver.tooltip"))
				.setControl(TickBoxControl::new)
				.setEnabled(false)
				.setBinding((data, value) -> {
				}, data -> FlywheelCompat.mobileDriver())
				.build();
	}

	private static <T> Option<T> gated(Option<T> option, BooleanSupplier gate) {
		return new GatedOption<>(option, gate);
	}
}
