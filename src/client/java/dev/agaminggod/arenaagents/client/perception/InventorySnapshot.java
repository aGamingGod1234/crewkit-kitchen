package dev.agaminggod.arenaagents.client.perception;

import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import java.util.List;
import java.util.Objects;

public record InventorySnapshot(
		int selectedSlot,
		String selectedItemId,
		int selectedItemCount,
		List<ItemSummary> items
) {
	public static final int HOTBAR_SLOT_COUNT = 9;
	public static final String EMPTY_ITEM_ID = "minecraft:air";

	public InventorySnapshot {
		if (selectedSlot < 0 || selectedSlot >= HOTBAR_SLOT_COUNT) {
			throw new IllegalArgumentException("selectedSlot must identify a hotbar slot");
		}
		selectedItemId = requireText(selectedItemId, "selectedItemId");
		if (selectedItemCount < 0) {
			throw new IllegalArgumentException("selectedItemCount must not be negative");
		}
		items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
	}

	public static InventorySnapshot empty() {
		return new InventorySnapshot(0, EMPTY_ITEM_ID, 0, List.of());
	}

	private static String requireText(String value, String field) {
		Objects.requireNonNull(value, field + " must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		if (value.length() > ProtocolConstants.MAX_IDENTIFIER_LENGTH) {
			throw new IllegalArgumentException(
					field + " must not exceed " + ProtocolConstants.MAX_IDENTIFIER_LENGTH + " characters"
			);
		}
		return value;
	}

	public record ItemSummary(String itemId, int count) {
		public ItemSummary {
			itemId = requireText(itemId, "itemId");
			if (count <= 0) {
				throw new IllegalArgumentException("count must be positive");
			}
		}
	}
}
