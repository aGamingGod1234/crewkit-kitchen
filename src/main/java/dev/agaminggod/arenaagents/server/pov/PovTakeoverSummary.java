package dev.agaminggod.arenaagents.server.pov;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Before/after capture of a taken-over body and the plain-language note the model receives on resume. */
public final class PovTakeoverSummary {
	public static final int MAX_LENGTH = 512;
	private static final int MAX_LIST_ENTRIES = 12;
	private static final int MAX_EVENT_CHARS = 160;
	private static final int MIN_SHORTENED_ENTRY = 12;
	private static final String ITEMS_LABEL = " Items: ";
	private static final String EVENTS_LABEL = " Events: ";
	private static final String MINECRAFT_NAMESPACE = "minecraft:";

	private PovTakeoverSummary() {
	}

	public record Snapshot(
			ResourceKey<Level> dimension,
			Vec3 position,
			float health,
			float absorption,
			int food,
			int xpLevel,
			Map<String, Integer> itemCounts,
			long capturedAtEpochMs
	) {
		public Snapshot {
			Objects.requireNonNull(dimension, "dimension must not be null");
			Objects.requireNonNull(position, "position must not be null");
			if (!Float.isFinite(health) || !Float.isFinite(absorption)) {
				throw new IllegalArgumentException("health and absorption must be finite");
			}
			Objects.requireNonNull(itemCounts, "itemCounts must not be null");
			TreeMap<String, Integer> sorted = new TreeMap<>();
			for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
				String item = Objects.requireNonNull(entry.getKey(), "item id must not be null");
				int count = Objects.requireNonNull(entry.getValue(), "item count must not be null");
				if (item.isBlank()) throw new IllegalArgumentException("item id must not be blank");
				if (count < 0) throw new IllegalArgumentException("item count must not be negative");
				if (count > 0) sorted.put(item, count);
			}
			itemCounts = Collections.unmodifiableMap(sorted);
		}
	}

	/** Counts every inventory slot (main, armor, offhand, body) plus the stack held on the cursor. */
	public static Snapshot capture(ServerPlayer agent) {
		Objects.requireNonNull(agent, "agent must not be null");
		Map<String, Integer> counts = new TreeMap<>();
		Inventory inventory = agent.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) count(counts, inventory.getItem(slot));
		count(counts, agent.containerMenu.getCarried());
		return new Snapshot(
				agent.level().dimension(),
				agent.position(),
				agent.getHealth(),
				agent.getAbsorptionAmount(),
				agent.getFoodData().getFoodLevel(),
				agent.experienceLevel,
				counts,
				System.currentTimeMillis()
		);
	}

	/**
	 * Plain-language report for the model, at most {@value #MAX_LENGTH} characters. Unchanged parts are omitted.
	 * Item changes list gains before losses, each largest first. Duration, movement, vitals and deaths always fit;
	 * items and events share the rest, events keeping up to two fifths of it, and are shortened with "and N more".
	 */
	public static String describe(
			Snapshot start,
			Snapshot end,
			long durationMs,
			int deaths,
			int respawns,
			List<String> notableEvents
	) {
		Objects.requireNonNull(start, "start must not be null");
		Objects.requireNonNull(end, "end must not be null");
		Objects.requireNonNull(notableEvents, "notableEvents must not be null");
		String opening = "An operator controlled your body for " + duration(durationMs) + ".";
		StringBuilder head = new StringBuilder(opening);
		movement(start, end, head);
		vitals(start, end, head);
		String lives = lives(Math.max(0, deaths), Math.max(0, respawns));
		List<String> items = itemDeltas(start.itemCounts(), end.itemCounts());
		List<String> events = events(notableEvents);
		if (head.length() == opening.length() && items.isEmpty() && lives.isEmpty() && events.isEmpty()) {
			return opening + " No other changes.";
		}
		int budget = MAX_LENGTH - head.length() - lives.length();
		int eventReserve = Math.min(fit(EVENTS_LABEL, "; ", events, Integer.MAX_VALUE).length(), budget * 2 / 5);
		String itemPart = fit(ITEMS_LABEL, ", ", items, budget - eventReserve);
		String eventPart = fit(EVENTS_LABEL, "; ", events, budget - itemPart.length());
		return cap(head + itemPart + lives + eventPart);
	}

	static String duration(long durationMs) {
		long seconds = Math.max(0L, Math.round(durationMs / 1000.0D));
		if (seconds < 60L) return seconds + " s";
		long minutes = seconds / 60L;
		long remainder = seconds % 60L;
		return remainder == 0L ? minutes + " min" : minutes + " min " + remainder + " s";
	}

	static List<String> itemDeltas(Map<String, Integer> before, Map<String, Integer> after) {
		TreeSet<String> ids = new TreeSet<>(before.keySet());
		ids.addAll(after.keySet());
		List<Map.Entry<String, Integer>> deltas = new ArrayList<>();
		for (String id : ids) {
			int delta = after.getOrDefault(id, 0) - before.getOrDefault(id, 0);
			if (delta != 0) deltas.add(Map.entry(shortId(id), delta));
		}
		deltas.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(entry -> entry.getValue() > 0 ? 0 : 1)
				.thenComparingInt(entry -> -Math.abs(entry.getValue()))
				.thenComparing(Map.Entry::getKey));
		List<String> lines = new ArrayList<>(deltas.size());
		for (Map.Entry<String, Integer> entry : deltas) {
			lines.add((entry.getValue() > 0 ? "+" : "") + entry.getValue() + " " + entry.getKey());
		}
		return lines;
	}

	private static void movement(Snapshot start, Snapshot end, StringBuilder out) {
		if (!start.dimension().equals(end.dimension())) {
			out.append(" Moved from ").append(dimensionName(start.dimension())).append(" to ")
					.append(dimensionName(end.dimension())).append(" (now at ").append(blockPosition(end.position())).append(").");
			return;
		}
		long blocks = Math.round(start.position().distanceTo(end.position()));
		if (blocks == 0L) return;
		out.append(" Moved ").append(blocks).append(blocks == 1L ? " block" : " blocks").append(" (from ")
				.append(blockPosition(start.position())).append(" to ").append(blockPosition(end.position())).append(").");
	}

	private static void vitals(Snapshot start, Snapshot end, StringBuilder out) {
		String healthBefore = number(start.health());
		String healthAfter = number(end.health());
		String absorptionBefore = number(start.absorption());
		String absorptionAfter = number(end.absorption());
		boolean healthChanged = !healthBefore.equals(healthAfter);
		boolean absorptionChanged = !absorptionBefore.equals(absorptionAfter);
		if (healthChanged) {
			out.append(" Health ").append(healthBefore).append(" to ").append(healthAfter);
			if (absorptionChanged) out.append(" (absorption ").append(absorptionBefore).append(" to ").append(absorptionAfter).append(')');
			else if (end.absorption() > 0.0F) out.append(" (absorption ").append(absorptionAfter).append(')');
			out.append('.');
		} else if (absorptionChanged) {
			out.append(" Absorption ").append(absorptionBefore).append(" to ").append(absorptionAfter).append('.');
		}
		if (start.food() != end.food()) out.append(" Food ").append(start.food()).append(" to ").append(end.food()).append('.');
		if (start.xpLevel() != end.xpLevel()) {
			out.append(" XP level ").append(start.xpLevel()).append(" to ").append(end.xpLevel()).append('.');
		}
	}

	private static String lives(int deaths, int respawns) {
		if (deaths > 0 && respawns > 0) return " Died " + times(deaths) + ", respawned " + times(respawns) + ".";
		if (deaths > 0) return " Died " + times(deaths) + ".";
		if (respawns > 0) return " Respawned " + times(respawns) + ".";
		return "";
	}

	private static String times(int count) {
		return count == 1 ? "1 time" : count + " times";
	}

	private static List<String> events(List<String> notableEvents) {
		List<String> events = new ArrayList<>();
		for (String event : notableEvents) {
			if (event == null) continue;
			StringBuilder clean = new StringBuilder(event.length());
			event.codePoints().forEach(point -> clean.appendCodePoint(Character.isISOControl(point) ? ' ' : point));
			String text = clean.toString().replaceAll("\\s+", " ").strip();
			while (text.endsWith(".") || text.endsWith(";")) text = text.substring(0, text.length() - 1).strip();
			if (text.isEmpty()) continue;
			events.add(text.length() > MAX_EVENT_CHARS ? truncate(text, MAX_EVENT_CHARS - 3) + "..." : text);
		}
		return events;
	}

	/**
	 * Joins as many whole entries as fit in budget, ending with "and N more" when some had to be dropped. When not
	 * even one fits, the first entry is shortened instead so a long single event is not lost entirely.
	 */
	private static String fit(String label, String separator, List<String> entries, int budget) {
		if (entries.isEmpty()) return "";
		int limit = Math.min(entries.size(), MAX_LIST_ENTRIES);
		for (int shown = limit; shown > 0; shown--) {
			StringBuilder part = new StringBuilder(label);
			for (int index = 0; index < shown; index++) {
				if (index > 0) part.append(separator);
				part.append(entries.get(index));
			}
			part.append(more(separator, entries.size() - shown)).append('.');
			if (part.length() <= budget) return part.toString();
		}
		String suffix = more(separator, entries.size() - 1) + ".";
		int room = budget - label.length() - suffix.length() - 3;
		if (room < MIN_SHORTENED_ENTRY) return "";
		return label + truncate(entries.get(0), room).strip() + "..." + suffix;
	}

	private static String more(String separator, int hidden) {
		return hidden > 0 ? separator + "and " + hidden + " more" : "";
	}

	private static String cap(String text) {
		return text.length() <= MAX_LENGTH ? text : truncate(text, MAX_LENGTH - 3) + "...";
	}

	private static String truncate(String text, int length) {
		int end = Math.max(0, Math.min(length, text.length()));
		if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
		return text.substring(0, end);
	}

	private static void count(Map<String, Integer> counts, ItemStack stack) {
		if (stack.isEmpty()) return;
		counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
	}

	private static String shortId(String id) {
		return id.startsWith(MINECRAFT_NAMESPACE) ? id.substring(MINECRAFT_NAMESPACE.length()) : id;
	}

	private static String dimensionName(ResourceKey<Level> dimension) {
		return shortId(dimension.identifier().toString());
	}

	private static String blockPosition(Vec3 position) {
		return (long) Math.floor(position.x) + "," + (long) Math.floor(position.y) + "," + (long) Math.floor(position.z);
	}

	private static String number(float value) {
		float rounded = Math.round(value * 10.0F) / 10.0F;
		if (rounded == Math.rint(rounded)) return Integer.toString((int) rounded);
		return String.format(Locale.ROOT, "%.1f", rounded);
	}
}
