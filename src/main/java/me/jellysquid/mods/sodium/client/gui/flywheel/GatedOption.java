package me.jellysquid.mods.sodium.client.gui.flywheel;

import java.util.Collection;
import java.util.function.BooleanSupplier;

import me.jellysquid.mods.sodium.client.gui.options.Option;
import me.jellysquid.mods.sodium.client.gui.options.OptionFlag;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpact;
import me.jellysquid.mods.sodium.client.gui.options.control.Control;
import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;
import net.minecraft.text.Text;

/**
 * An option whose availability is decided on every frame rather than at build time.
 *
 * <p>{@link me.jellysquid.mods.sodium.client.gui.options.OptionImpl} takes one boolean when it is
 * built and never looks at it again, so a Flywheel sub-option would stay live after its master
 * switch was turned off. This wrapper keeps the option it decorates untouched and answers
 * {@link #isAvailable()} from the supplier instead, which is enough: Sodium asks that question
 * while drawing, while handling a click, and while handling a slider drag, so a sub-option grays
 * out the instant the master is unticked, before Apply is even pressed.
 *
 * <p>Every other question is delegated. In particular {@link #hasChanged()} and
 * {@link #applyChanges()} stay those of the wrapped option, so turning the master off does not
 * discard a sub-option the user already chose -- the value waits, applies when the backend comes
 * back, and is in {@code flywheel.json} either way.
 */
public final class GatedOption<T> implements Option<T> {
	private final Option<T> delegate;
	private final BooleanSupplier gate;

	public GatedOption(Option<T> delegate, BooleanSupplier gate) {
		this.delegate = delegate;
		this.gate = gate;
	}

	@Override
	public boolean isAvailable() {
		return this.gate.getAsBoolean();
	}

	@Override
	public Text getName() {
		return this.delegate.getName();
	}

	@Override
	public Text getTooltip() {
		return this.delegate.getTooltip();
	}

	@Override
	public OptionImpact getImpact() {
		return this.delegate.getImpact();
	}

	@Override
	public Control<T> getControl() {
		return this.delegate.getControl();
	}

	@Override
	public T getValue() {
		return this.delegate.getValue();
	}

	@Override
	public void setValue(T value) {
		this.delegate.setValue(value);
	}

	@Override
	public void reset() {
		this.delegate.reset();
	}

	@Override
	public OptionStorage<?> getStorage() {
		return this.delegate.getStorage();
	}

	@Override
	public boolean hasChanged() {
		return this.delegate.hasChanged();
	}

	@Override
	public void applyChanges() {
		this.delegate.applyChanges();
	}

	@Override
	public Collection<OptionFlag> getFlags() {
		return this.delegate.getFlags();
	}
}
