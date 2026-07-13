package dev.agaminggod.arenaagents.client.combat;

import java.util.Objects;

public record WeaponCandidate(String itemId, int slot) {
	public WeaponCandidate {
		itemId = Objects.requireNonNull(itemId, "itemId must not be null");
		if (itemId.isBlank()) {
			throw new IllegalArgumentException("itemId must not be blank");
		}
		if (slot < 0) {
			throw new IllegalArgumentException("slot must not be negative");
		}
	}
}
