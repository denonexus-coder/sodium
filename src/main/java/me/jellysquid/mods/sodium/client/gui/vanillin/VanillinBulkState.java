package me.jellysquid.mods.sodium.client.gui.vanillin;

/**
 * The value shown by the bulk row on Vanillin's page.
 *
 * <p>It has one state the per-visual enum does not: {@link #MIXED}. That state is read-only and
 * exists so the row can say the rows below do not currently agree, instead of picking one row's
 * value and pretending it is the global one. Selecting a real state applies it to every visual.
 */
public enum VanillinBulkState {
	/** Hand every visual back to Vanillin's own default. */
	DEFAULT,
	/** Remove every visualizer, so all of these objects use the vanilla renderer. */
	DISABLE,
	/** Install every visualizer, including the experimental ones. */
	FORCE_ENABLE,
	/** The rows below do not all have the same value. Selecting this does nothing. */
	MIXED;

	/** The per-visual state this bulk state writes, or {@code null} for {@link #MIXED}. */
	public VanillinState toState() {
		return switch (this) {
			case DEFAULT -> VanillinState.DEFAULT;
			case DISABLE -> VanillinState.DISABLE;
			case FORCE_ENABLE -> VanillinState.FORCE_ENABLE;
			case MIXED -> null;
		};
	}
}
