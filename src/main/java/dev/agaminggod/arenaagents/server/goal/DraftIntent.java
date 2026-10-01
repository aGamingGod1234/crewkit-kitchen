package dev.agaminggod.arenaagents.server.goal;

public enum DraftIntent {
	START,
	REPLACE_OR_QUEUE,
	CONFIRM_TRANSLATION,
	TRANSLATE_START,
	TRANSLATE_QUEUE,
	TRANSLATE_REPLACE;

	public boolean acceptsCoordinatorProposal() {
		return this == CONFIRM_TRANSLATION || this == TRANSLATE_START || this == TRANSLATE_QUEUE || this == TRANSLATE_REPLACE;
	}
}
