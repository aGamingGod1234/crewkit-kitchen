package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.AgentValidators;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import dev.agaminggod.arenaagents.agent.goal.GoalSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Predicate;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;

public final class GoalCompiler {
	private static final Pattern POSITION = Pattern.compile(
			"^(?:go|move|travel|get)(?: to)?(?: coordinates?)?\\s+"
					+ "(?:x\\s*=\\s*)?(-?\\d+)\\s*,?\\s*"
					+ "(?:y\\s*=\\s*)?(-?\\d+)\\s*,?\\s*"
					+ "(?:z\\s*=\\s*)?(-?\\d+)"
					+ "(?:\\s+and\\s+stop(?:\\s+there)?)?$"
	);
	private static final Pattern ADVANCEMENT = Pattern.compile("^(?:complete|get|earn) (?:the )?advancement ([a-z0-9_.-]+:[a-z0-9_./-]+)$");
	private static final Pattern KILL = Pattern.compile("^(?:kill|slay|defeat) (?:the )?(.+)$");
	private static final Pattern BEAT_GAME = Pattern.compile("^beat (?:the )?game$");
	private static final Pattern ITEM = Pattern.compile("^(get|obtain|collect|bring|craft|make) (?:me )?(?:(\\d+) )?(?:(?:a|an|some) )?(.+?)(?: for me)?$");
	private static final Pattern SUBJECTIVE = Pattern.compile("\\b(?:good|better|best|strong|stronger|useful|decent|nice|appropriate|some kind of)\\b");
	private static final Pattern GOAL_LEAD = Pattern.compile("^(?:get|obtain|collect|bring|craft|make|go|move|travel|come|kill|slay|defeat|build|mine|find|gather|chop|break|place|beat|survive|explore|follow|protect|farm|smelt|cook|trade|complete|earn)\\b");
	private static final Pattern LIVE_STEERING = Pattern.compile(
			"^(?:continue|keep going|watch out|try another route|retry|resume|come here|come to me|come with me|come|over here|this way|follow me|follow us|follow|stay close|stay with me|wait|stop|hold on|go there|behind me|to me)$"
	);

	public GoalCompilation compile(String request, RegistryAccess registries, long createdAtTick) {
		return compile(request, registries, createdAtTick, ignored -> true);
	}

	public GoalCompilation compile(
			String request,
			RegistryAccess registries,
			long createdAtTick,
			Predicate<String> advancementExists
	) {
		Objects.requireNonNull(registries, "registries must not be null");
		Objects.requireNonNull(advancementExists, "advancementExists must not be null");
		String original = AgentValidators.normalizePrompt(request);
		String command = stripTrailingPunctuation(stripPoliteness(original.toLowerCase(Locale.ROOT)));
		if (SUBJECTIVE.matcher(command).find()) {
			return GoalCompilation.needsTranslation("I need you to clarify the exact Minecraft result you want.");
		}

		Matcher position = POSITION.matcher(command);
		if (position.matches()) {
			try {
				GoalPredicate predicate = new GoalPredicate.PositionWithin(
						Integer.parseInt(position.group(1)), Integer.parseInt(position.group(2)), Integer.parseInt(position.group(3)),
						1.0D, 20
				);
				return accepted(original, predicate, createdAtTick, "Goal set: reach the requested coordinates.");
			} catch (NumberFormatException exception) {
				return GoalCompilation.rejected("Those coordinates are outside Minecraft's supported range.");
			}
		}

		Matcher advancement = ADVANCEMENT.matcher(command);
		if (advancement.matches()) {
			if (!advancementExists.test(advancement.group(1))) {
				return GoalCompilation.needsTranslation("That advancement ID does not exist on this server.");
			}
			return accepted(original, new GoalPredicate.AdvancementGranted(advancement.group(1)), createdAtTick,
					"Goal set: earn " + advancement.group(1) + ".");
		}

		Matcher kill = KILL.matcher(command);
		if (BEAT_GAME.matcher(command).matches()) {
			return accepted(original, new GoalPredicate.EntityKilledByAgent("minecraft:ender_dragon", true), createdAtTick,
					"Goal set: defeat minecraft:ender_dragon.");
		}
		if (kill.matches()) {
			List<String> matches = matchEntities(kill.group(1), registries);
			if (matches.size() == 1) {
				String entityId = matches.getFirst();
				return accepted(original, new GoalPredicate.EntityKilledByAgent(entityId, true), createdAtTick,
						"Goal set: defeat " + entityId + ".");
			}
			return GoalCompilation.needsTranslation(matches.isEmpty()
					? "I could not identify the exact Minecraft entity to defeat."
					: "More than one Minecraft entity matches that request.");
		}

		Matcher item = ITEM.matcher(command);
		if (item.matches()) {
			if (item.group(1).equals("craft") || item.group(1).equals("make")) {
				return GoalCompilation.needsTranslation(
						"I can verify possession, but not that this item was newly crafted. Clarify whether obtaining it counts."
				);
			}
			int count;
			try {
				count = item.group(2) == null ? 1 : Integer.parseInt(item.group(2));
			} catch (NumberFormatException exception) {
				return GoalCompilation.rejected("The requested item count is outside the supported range.");
			}
			if (count <= 0) return GoalCompilation.rejected("The requested item count must be positive.");
			List<String> matches = matchItems(item.group(3), registries);
			if (matches.size() == 1) {
				String itemId = matches.getFirst();
				return accepted(original, new GoalPredicate.InventoryContains(itemId, count), createdAtTick,
						"Goal set: obtain " + itemId + " x" + count + ".");
			}
			return GoalCompilation.needsTranslation(matches.isEmpty()
					? "I could not identify the exact Minecraft item you want."
					: "More than one Minecraft item matches that request.");
		}

		return GoalCompilation.needsTranslation("I need an exact result before I can start this goal.");
	}

	public static boolean looksLikeGoalRequest(String request) {
		String normalized = normalizedCommand(request);
		if (LIVE_STEERING.matcher(normalized).matches()) return false;
		return GOAL_LEAD.matcher(normalized).find();
	}

	/** Live steering such as "come here" / "follow me" must reach the coordinator, not start a new goal. */
	public static boolean isLiveSteeringRequest(String request) {
		return LIVE_STEERING.matcher(normalizedCommand(request)).matches();
	}

	/**
	 * Speech is only consumed as a goal change when the agent is idle, or the player already
	 * opted into replace/queue through {@code /agent goal}. Busy agents keep working and still hear the line.
	 */
	public static boolean consumePlayerSpeechAsGoal(boolean hasActiveGoal, String text, boolean replaceOrQueueOptIn) {
		if (replaceOrQueueOptIn) return looksLikeGoalRequest(text) || isLiveSteeringRequest(text);
		if (hasActiveGoal) return false;
		if (isLiveSteeringRequest(text)) return false;
		return looksLikeGoalRequest(text);
	}

	private static String normalizedCommand(String request) {
		return stripTrailingPunctuation(stripPoliteness(AgentValidators.normalizePrompt(request).toLowerCase(Locale.ROOT)));
	}

	public List<String> candidateIdsFor(String request, RegistryAccess registries) {
		Objects.requireNonNull(registries, "registries must not be null");
		String command = stripTrailingPunctuation(stripPoliteness(
				AgentValidators.normalizePrompt(request).toLowerCase(Locale.ROOT)));
		Matcher item = ITEM.matcher(command);
		if (item.matches()) {
			String target = SUBJECTIVE.matcher(item.group(3)).replaceAll(" ").replaceAll("\\s+", " ").strip();
			return relatedItems(target, registries).stream().limit(64).toList();
		}
		Matcher kill = KILL.matcher(command);
		if (BEAT_GAME.matcher(command).matches()) return List.of("minecraft:ender_dragon");
		if (kill.matches()) {
			String target = SUBJECTIVE.matcher(kill.group(1)).replaceAll(" ").replaceAll("\\s+", " ").strip();
			return relatedEntities(target, registries).stream().limit(64).toList();
		}
		return List.of();
	}

	private static GoalCompilation accepted(String original, GoalPredicate predicate, long createdAtTick, String message) {
		return GoalCompilation.accepted(GoalSpec.create(original, predicate, createdAtTick), message);
	}

	private static List<String> matchItems(String target, RegistryAccess registries) {
		String wanted = normalizedTarget(target);
		ArrayList<String> matches = new ArrayList<>();
		Registry<Item> itemRegistry = registries.lookup(Registries.ITEM).orElse(BuiltInRegistries.ITEM);
		for (Identifier id : itemRegistry.keySet()) {
			Item item = itemRegistry.getValue(id);
			if (item == null) continue;
			String descriptionName = descriptionName(item.getDescriptionId());
			if (wanted.equals(id.toString()) || wanted.equals(pathName(id)) || wanted.equals(descriptionName)) {
				matches.add(id.toString());
			}
		}
		return matches.stream().distinct().sorted().toList();
	}

	private static List<String> relatedItems(String target, RegistryAccess registries) {
		String wanted = normalizedTarget(target);
		if (wanted.isEmpty()) return List.of();
		Registry<Item> itemRegistry = registries.lookup(Registries.ITEM).orElse(BuiltInRegistries.ITEM);
		boolean tools = wanted.endsWith(" tool") || wanted.endsWith(" tools");
		String material = tools ? wanted.replaceFirst("\\s+tools?$", "") : "";
		Set<String> toolKinds = Set.of("axe", "hoe", "pickaxe", "shovel", "sword");
		return itemRegistry.keySet().stream()
				.filter(id -> {
					Item item = itemRegistry.getValue(id);
					String description = item == null ? "" : descriptionName(item.getDescriptionId());
					String path = pathName(id);
					return id.toString().equals(wanted) || path.equals(wanted) || description.equals(wanted)
							|| path.endsWith(" " + wanted) || description.endsWith(" " + wanted)
							|| tools && path.startsWith(material + " ") && toolKinds.contains(path.substring(material.length() + 1));
				})
				.map(Identifier::toString).distinct().sorted().toList();
	}

	private static List<String> matchEntities(String target, RegistryAccess registries) {
		String wanted = normalizedTarget(target);
		Registry<EntityType<?>> entityRegistry = registries.lookup(Registries.ENTITY_TYPE).orElse(BuiltInRegistries.ENTITY_TYPE);
		return entityRegistry.keySet().stream()
				.filter(id -> {
					EntityType<?> entityType = entityRegistry.getValue(id);
					String displayName = entityType == null ? "" : entityType.getDescription().getString().toLowerCase(Locale.ROOT);
					return wanted.equals(id.toString()) || wanted.equals(pathName(id)) || wanted.equals(displayName);
				})
				.map(Identifier::toString)
				.distinct()
				.sorted()
				.toList();
	}

	private static List<String> relatedEntities(String target, RegistryAccess registries) {
		String wanted = normalizedTarget(target);
		if (wanted.isEmpty()) return List.of();
		Registry<EntityType<?>> registry = registries.lookup(Registries.ENTITY_TYPE).orElse(BuiltInRegistries.ENTITY_TYPE);
		return registry.keySet().stream()
				.filter(id -> pathName(id).equals(wanted) || pathName(id).endsWith(" " + wanted))
				.map(Identifier::toString).distinct().sorted().toList();
	}

	private static String pathName(Identifier id) {
		return id.getPath().replace('_', ' ');
	}

	private static String descriptionName(String descriptionId) {
		int separator = descriptionId.lastIndexOf('.');
		return (separator < 0 ? descriptionId : descriptionId.substring(separator + 1)).replace('_', ' ').toLowerCase(Locale.ROOT);
	}

	private static String normalizedTarget(String value) {
		return value.strip().replaceAll("\\s+", " ");
	}

	private static String stripPoliteness(String request) {
		String result = request;
		for (String prefix : List.of("hey, ", "hey ", "please ", "can you ", "could you ", "would you ", "go and ")) {
			if (result.startsWith(prefix)) {
				result = result.substring(prefix.length()).strip();
				return stripPoliteness(result);
			}
		}
		return result;
	}

	private static String stripTrailingPunctuation(String request) {
		return request.replaceFirst("[.!?]+$", "").strip();
	}
}
