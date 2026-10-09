package dev.agaminggod.arenaagents.crewkit;

import java.util.List;

/**
 * Registry of kitchen features. Each track adds ONE line here (merge = keep all lines).
 * The dispatcher (crewkit.core) calls these in order.
 */
public final class CrewkitFeatures {
	private CrewkitFeatures() {}

	public static List<CrewkitFeature> all() {
		return List.of(
			// core-boards: new dev.agaminggod.arenaagents.crewkit.core.BoardsFeature(),
			// items:       new dev.agaminggod.arenaagents.crewkit.items.ItemsFeature(),
			// flow:        new dev.agaminggod.arenaagents.crewkit.flow.FlowFeature(),
			// cast:        new dev.agaminggod.arenaagents.crewkit.cast.CastFeature(),
		);
	}
}
