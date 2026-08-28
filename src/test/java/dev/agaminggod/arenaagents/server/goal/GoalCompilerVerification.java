package dev.agaminggod.arenaagents.server.goal;

import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentLifecycleReducer;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import dev.agaminggod.arenaagents.agent.goal.GoalSpec;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public final class GoalCompilerVerification {
	private GoalCompilerVerification() {
	}

	public static int verify() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		bindItemStackSize(Items.APPLE, 64);
		bindItemStackSize(Items.COBBLESTONE, 64);
		bindItemStackSize(Items.DIAMOND_PICKAXE, 1);
		bindItemStackSize(Items.DIAMOND_SWORD, 1);
		bindItemStackSize(Items.IRON_AXE, 1);
		bindItemStackSize(Items.IRON_PICKAXE, 1);
		int assertions = 0;
		assertions += verifyExactItemAndAmbiguity();
		assertions += verifyInventoryCapacity();
		assertions += verifyExactPositionEntityAndAdvancement();
		assertions += verifyCompoundItemsAndKills();
		assertions += verifyExplicitAlternativeCandidates();
		assertions += verifyDraftRoundTrip();
		assertions += verifyWorldValidation();
		assertions += verifyWireBoundaryInvariants();
		assertions += verifyDraftRevisionBinding();
		assertions += verifyDraftAuthorizationAndChoices();
		assertions += verifySpecAwareLifecycleStart();
		return assertions;
	}

	private static int verifyExactItemAndAmbiguity() {
		GoalCompiler compiler = new GoalCompiler();
		GoalCompilation exact = compiler.compile("Hey, get an iron pickaxe", RegistryAccess.EMPTY, 1_200L);
		assertEquals(GoalCompilation.Kind.ACCEPTED, exact.kind(), "exact item request is accepted");
		assertEquals(
				new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1),
				exact.acceptedSpec().orElseThrow().completion(),
				"exact item request freezes the iron pickaxe predicate"
		);
		assertEquals(
				GoalCompilation.Kind.NEEDS_TRANSLATION,
				compiler.compile("Get a good pickaxe", RegistryAccess.EMPTY, 1_200L).kind(),
				"subjective item request requires translation"
		);
		assertEquals(
				List.of(
						"minecraft:copper_pickaxe", "minecraft:diamond_pickaxe", "minecraft:golden_pickaxe", "minecraft:iron_pickaxe",
						"minecraft:netherite_pickaxe", "minecraft:stone_pickaxe", "minecraft:wooden_pickaxe"
				),
				compiler.candidateIdsFor("Get a good pickaxe", RegistryAccess.EMPTY),
				"subjective item request keeps only bounded registered pickaxe candidates"
		);
		assertEquals(false, GoalCompiler.looksLikeGoalRequest("Hi, can you hear me?"), "casual speech remains conversation");
		assertEquals(true, GoalCompiler.looksLikeGoalRequest("Can you get an iron pickaxe?"), "actionable speech is a goal request");
		assertEquals(false, GoalCompiler.looksLikeGoalRequest("come here"), "come here is live steering, not a new goal");
		assertEquals(false, GoalCompiler.looksLikeGoalRequest("Follow me"), "follow me is live steering, not a new goal");
		assertEquals(true, GoalCompiler.isLiveSteeringRequest("Can you come here?"), "polite come-here stays steering");
		assertEquals(false, GoalCompiler.consumePlayerSpeechAsGoal(true, "come here", false), "busy agents still hear come here");
		assertEquals(false, GoalCompiler.consumePlayerSpeechAsGoal(true, "get an iron pickaxe", false), "busy agents keep working unless replace/queue was chosen");
		assertEquals(true, GoalCompiler.consumePlayerSpeechAsGoal(false, "get an iron pickaxe", false), "idle agents still compile exact goal speech");
		assertEquals(
				List.of("minecraft:iron_axe", "minecraft:iron_hoe", "minecraft:iron_pickaxe", "minecraft:iron_shovel", "minecraft:iron_sword"),
				compiler.candidateIdsFor("Get iron tools", RegistryAccess.EMPTY),
				"tool-set clarification keeps the registered material tool family"
		);
		GoalCompilation craft = compiler.compile("Craft an iron pickaxe", RegistryAccess.EMPTY, 1_200L);
		assertEquals(GoalCompilation.Kind.NEEDS_TRANSLATION, craft.kind(),
				"craft wording cannot be reduced to already-held inventory");
		assertEquals(Optional.empty(), craft.acceptedSpec(),
				"craft wording has no false possession completion predicate");
		GoalCompilation make = compiler.compile("Make an iron pickaxe", RegistryAccess.EMPTY, 1_200L);
		assertEquals(GoalCompilation.Kind.NEEDS_TRANSLATION, make.kind(),
				"make wording cannot be reduced to already-held inventory");
		return 17;
	}

	private static int verifyInventoryCapacity() {
		GoalCompiler compiler = new GoalCompiler();
		GoalCompilation fullCobblestoneInventory = compiler.compile(
				"Get 2368 cobblestone", RegistryAccess.EMPTY, 1_200L
		);
		assertEquals(GoalCompilation.Kind.ACCEPTED, fullCobblestoneInventory.kind(),
				"general inventory and offhand stacks are representable");
		assertEquals(
				new GoalPredicate.InventoryContains("minecraft:cobblestone", 2_368),
				fullCobblestoneInventory.acceptedSpec().orElseThrow().completion(),
				"the stackable-item capacity boundary preserves the exact requested count"
		);
		assertEquals(
				GoalCompilation.Kind.REJECTED,
				compiler.compile("Get 2369 cobblestone", RegistryAccess.EMPTY, 1_200L).kind(),
				"one item above the stackable-item capacity is rejected"
		);
		assertEquals(
				GoalCompilation.Kind.REJECTED,
				compiler.compile("Get 1000000 cobblestone", RegistryAccess.EMPTY, 1_200L).kind(),
				"an unrepresentable million-item inventory goal is rejected"
		);
		assertEquals(
				GoalCompilation.Kind.ACCEPTED,
				compiler.compile("Get 37 iron pickaxes", RegistryAccess.EMPTY, 1_200L).kind(),
				"unstackable tools can fill general inventory and offhand slots"
		);
		assertEquals(
				GoalCompilation.Kind.REJECTED,
				compiler.compile("Get 38 iron pickaxes", RegistryAccess.EMPTY, 1_200L).kind(),
				"unstackable items use their resolved one-item stack limit"
		);
		assertEquals(
				GoalCompilation.Kind.REJECTED,
				compiler.compile("Get 2369 cobblestone and kill a zombie", RegistryAccess.EMPTY, 1_200L).kind(),
				"compound inventory predicates enforce the same carrying capacity"
		);
		return 7;
	}

	private static int verifyExactPositionEntityAndAdvancement() {
		GoalCompiler compiler = new GoalCompiler();
		GoalPredicate.PositionWithin position = (GoalPredicate.PositionWithin) compiler.compile(
				"Go to 12 64 -8", RegistryAccess.EMPTY, 1_200L
		).acceptedSpec().orElseThrow().completion();
		assertEquals(new GoalPredicate.PositionWithin(12.0D, 64.0D, -8.0D, 1.0D, 20), position, "exact coordinates");
		GoalPredicate.PositionWithin labelledPosition = (GoalPredicate.PositionWithin) compiler.compile(
				"Move to coordinates x=-22, y=99, z=12 and stop there", RegistryAccess.EMPTY, 1_200L
		).acceptedSpec().orElseThrow().completion();
		assertEquals(new GoalPredicate.PositionWithin(-22.0D, 99.0D, 12.0D, 1.0D, 20), labelledPosition,
				"labelled coordinates from the task UI compile to a positional verifier");
		assertEquals(
				new GoalPredicate.EntityKilledByAgent("minecraft:ender_dragon", true),
				compiler.compile("Kill the ender dragon", RegistryAccess.EMPTY, 1_200L).acceptedSpec().orElseThrow().completion(),
				"exact entity kill"
		);
		assertEquals(
				new GoalPredicate.EntityKilledByAgent("minecraft:ender_dragon", true),
				compiler.compile("Beat the game", RegistryAccess.EMPTY, 1_200L).acceptedSpec().orElseThrow().completion(),
				"beating the game freezes an agent-attributed dragon kill"
		);
		assertEquals(
				new GoalPredicate.AdvancementGranted("minecraft:story/mine_stone"),
				compiler.compile("Complete advancement minecraft:story/mine_stone", RegistryAccess.EMPTY, 1_200L,
						id -> id.equals("minecraft:story/mine_stone"))
						.acceptedSpec().orElseThrow().completion(),
				"exact advancement ID"
		);
		assertEquals(
				GoalCompilation.Kind.NEEDS_TRANSLATION,
				compiler.compile("Complete advancement minecraft:story/not_real", RegistryAccess.EMPTY, 1_200L, id -> false).kind(),
				"nonexistent advancement ID requires clarification"
		);
		GoalPredicate.PositionWithin netherPosition = (GoalPredicate.PositionWithin) compiler.compile(
				"Go to 12 64 -8", RegistryAccess.EMPTY, 1_200L, "minecraft:the_nether"
		).acceptedSpec().orElseThrow().completion();
		assertEquals("minecraft:the_nether", netherPosition.dimensionId(), "coordinate goal binds its source dimension");
		assertEquals(GoalCompilation.Kind.REJECTED,
				compiler.compile("Go to 30000000 64 0", RegistryAccess.EMPTY, 1_200L).kind(),
				"out-of-world horizontal coordinates are rejected before a goal is accepted");
		assertEquals(
				List.of("minecraft:oak_planks"),
				compiler.candidateIdsFor("Place oak planks", RegistryAccess.EMPTY),
				"plural multiword block names resolve to the exact registered block ID"
		);
		assertEquals(
				List.of("minecraft:oak_planks"),
				compiler.candidateIdsFor("Place oak plank", RegistryAccess.EMPTY),
				"singular and plural block wording resolve to the same registered block ID"
		);
		assertEquals(
				List.of("minecraft:oak_planks"),
				compiler.candidateIdsFor("Build with oak_planks", RegistryAccess.EMPTY),
				"registry path spelling is normalized as a multiword block name"
		);
		assertEquals(
				List.of("minecraft:crafting_table"),
				compiler.candidateIdsFor("place a crafting table at 10 64 10", RegistryAccess.EMPTY),
				"exact coordinates are removed before matching a block translation candidate"
		);
		assertEquals(
				List.of("minecraft:crafting_table"),
				compiler.candidateIdsFor("Place a crafting table at coordinates x=-10, y=64, z=10", RegistryAccess.EMPTY),
				"labelled coordinate suffixes preserve the exact block candidate"
		);
		assertTrue(
				!compiler.candidateIdsFor("Place oak planks", RegistryAccess.EMPTY).contains("minecraft:oak_button"),
				"phrase matching does not expand a block request to unrelated same-material blocks"
		);
		GoalCompilation breakBlock = compiler.compile(
				"Break stone at 10 64 -10", RegistryAccess.EMPTY, 1_200L, "minecraft:the_nether");
		assertEquals(GoalCompilation.Kind.ACCEPTED, breakBlock.kind(),
				"an exact coordinate-bearing destructive request compiles without translation");
		assertEquals(
				new GoalPredicate.BlockMatches("minecraft:the_nether", 10, 64, -10, "minecraft:air", Map.of()),
				breakBlock.acceptedSpec().orElseThrow().completion(),
				"destructive block completion verifies the achievable post-break state"
		);
		assertEquals(
				List.of("minecraft:air"),
				compiler.candidateIdsFor("Destroy stone at coordinates x=10, y=64, z=-10", RegistryAccess.EMPTY),
				"destructive coordinate translation exposes only the post-break state"
		);
		assertEquals(
				List.of("minecraft:stone"),
				compiler.candidateIdsFor("Place stone at 10 64 -10", RegistryAccess.EMPTY),
				"placement translation still exposes the requested placed block"
		);
		return 18;
	}

	private static int verifyCompoundItemsAndKills() {
		GoalCompiler compiler = new GoalCompiler();
		GoalCompilation mixed = compiler.compile("Get 2 apples and kill a zombie", RegistryAccess.EMPTY, 1_200L);
		assertEquals(GoalCompilation.Kind.ACCEPTED, mixed.kind(), "mixed factual item and kill request is accepted");
		assertEquals(
				new GoalPredicate.AllOf(List.of(
						new GoalPredicate.InventoryContains("minecraft:apple", 2),
						new GoalPredicate.EntityKilledByAgent("minecraft:zombie", true)
				)),
				mixed.acceptedSpec().orElseThrow().completion(),
				"mixed request freezes one bounded predicate per factual result"
		);
		GoalCompilation twoKills = compiler.compile("Kill a zombie and a skeleton", RegistryAccess.EMPTY, 1_200L);
		assertEquals(
				new GoalPredicate.AllOf(List.of(
						new GoalPredicate.EntityKilledByAgent("minecraft:zombie", true),
						new GoalPredicate.EntityKilledByAgent("minecraft:skeleton", true)
				)),
				twoKills.acceptedSpec().orElseThrow().completion(),
				"a carried kill verb compiles each entity into its own verifier"
		);
		assertEquals(
				List.of(
						"minecraft:copper_pickaxe", "minecraft:diamond_pickaxe", "minecraft:golden_pickaxe", "minecraft:iron_pickaxe",
						"minecraft:netherite_pickaxe", "minecraft:stone_pickaxe", "minecraft:wooden_pickaxe", "minecraft:zombie"
				),
				compiler.candidateIdsFor("Get a good pickaxe and kill a zombie", RegistryAccess.EMPTY),
				"compound translation candidates include registered items and entities"
		);
		assertEquals(
				List.of("minecraft:zombie"),
				compiler.candidateIdsFor("Kill 3 zombies", RegistryAccess.EMPTY),
				"a kill count is removed before collecting translation candidates"
		);
		assertEquals(
				List.of("minecraft:apple", "minecraft:enchanted_golden_apple", "minecraft:golden_apple", "minecraft:zombie"),
				compiler.candidateIdsFor("Get an apple and kill 3 zombies", RegistryAccess.EMPTY),
				"compound translation strips kill counts without changing item candidates"
		);
		GoalCompilation overBound = compiler.compile("Get apple" + " and apple".repeat(16), RegistryAccess.EMPTY, 1_200L);
		assertEquals(GoalCompilation.Kind.REJECTED, overBound.kind(), "compound predicates reject more than sixteen factual leaves");
		GoalCompilation carriedCraft = compiler.compile("Craft an iron pickaxe and a shield", RegistryAccess.EMPTY, 1_200L);
		assertEquals(GoalCompilation.Kind.NEEDS_TRANSLATION, carriedCraft.kind(),
				"compound craft wording cannot be reduced to possession predicates");
		assertEquals(Optional.empty(), carriedCraft.acceptedSpec(),
				"compound craft wording has no false possession completion predicate");
		assertEquals(
				GoalCompilation.Kind.NEEDS_TRANSLATION,
				compiler.compile("Get an iron pickaxe and make a shield", RegistryAccess.EMPTY, 1_200L).kind(),
				"an explicit make clause keeps the entire compound goal verifiable"
		);
		GoalCompilation repeatedItem = compiler.compile(
				"Get 2 diamond swords and 3 diamond swords", RegistryAccess.EMPTY, 1_200L);
		assertEquals(GoalCompilation.Kind.ACCEPTED, repeatedItem.kind(),
				"repeated inventory requirements remain directly compilable");
		assertEquals(
				new GoalPredicate.AllOf(List.of(
						new GoalPredicate.InventoryContains("minecraft:diamond_sword", 5)
				)),
				repeatedItem.acceptedSpec().orElseThrow().completion(),
				"repeated inventory requirements sum into one exact minimum count"
		);
		assertEquals(
				GoalCompilation.Kind.REJECTED,
				compiler.compile("Get 20 diamond swords and 18 diamond swords", RegistryAccess.EMPTY, 1_200L).kind(),
				"summed duplicate requirements are revalidated against inventory capacity"
		);
		return 13;
	}

	private static int verifyExplicitAlternativeCandidates() {
		GoalCompiler compiler = new GoalCompiler();
		assertEquals(
				List.of("minecraft:diamond_pickaxe", "minecraft:iron_pickaxe"),
				compiler.candidateIdsFor("Get an iron or diamond pickaxe", RegistryAccess.EMPTY),
				"shared item nouns publish each explicit alternative"
		);
		assertEquals(
				List.of("minecraft:skeleton", "minecraft:wither_skeleton", "minecraft:zombie"),
				compiler.candidateIdsFor("Kill a zombie or a skeleton", RegistryAccess.EMPTY),
				"kill alternatives publish each bounded related entity"
		);
		assertEquals(
				List.of("minecraft:iron_pickaxe"),
				compiler.candidateIdsFor("Get an iron or iron pickaxe", RegistryAccess.EMPTY),
				"duplicate alternatives publish one candidate identifier"
		);
		assertTrue(
				compiler.candidateIdsFor("Get a sword or diamond pickaxe", RegistryAccess.EMPTY)
						.containsAll(List.of("minecraft:iron_sword", "minecraft:diamond_pickaxe")),
				"a standalone item alternative is not forced to share the final noun"
		);
		assertEquals(
				List.of(
						"minecraft:diamond_pickaxe", "minecraft:iron_pickaxe", "minecraft:skeleton",
						"minecraft:wither_skeleton", "minecraft:zombie"
				),
				compiler.candidateIdsFor(
						"Get an iron or diamond pickaxe and kill a zombie or skeleton", RegistryAccess.EMPTY
				),
				"compound clauses union item and kill alternatives"
		);
		assertEquals(
				List.of(),
				compiler.candidateIdsFor("Get a compass to find iron or diamond", RegistryAccess.EMPTY),
				"or in an unrelated purpose phrase does not publish a partial candidate list"
		);
		assertEquals(
				List.of("minecraft:diamond"),
				compiler.candidateIdsFor("Get diamond" + " or diamond".repeat(15), RegistryAccess.EMPTY),
				"sixteen explicit alternatives stay within the predicate leaf bound"
		);
		assertEquals(
				List.of(),
				compiler.candidateIdsFor("Get diamond" + " or diamond".repeat(16), RegistryAccess.EMPTY),
				"more than sixteen alternatives are not expanded into an invalid predicate"
		);
		assertEquals(
				64,
				compiler.candidateIdsFor("Get stairs or slab", RegistryAccess.EMPTY).size(),
				"alternative candidate unions retain the draft schema limit"
		);
		return 9;
	}

	private static int verifyDraftRoundTrip() {
		PendingGoalDraft draft = new PendingGoalDraft(
				UUID.fromString("00000000-0000-0000-0000-000000000101"),
				new AgentId(UUID.fromString("00000000-0000-0000-0000-000000000102")),
				UUID.fromString("00000000-0000-0000-0000-000000000103"),
				"Get a good pickaxe",
				"minecraft:the_nether",
				List.of("minecraft:diamond_pickaxe", "minecraft:iron_pickaxe"),
				Optional.of(new GoalPredicate.AnyOf(java.util.List.of(
						new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1),
						new GoalPredicate.InventoryContains("minecraft:diamond_pickaxe", 1)
				))),
				DraftIntent.CONFIRM_TRANSLATION,
				1_200L,
				4L,
				Optional.empty()
		);
		PendingGoalDraftCodec codec = new PendingGoalDraftCodec();
		assertEquals(draft, codec.decode(codec.encode(draft)), "pending goal draft round-trip");
		assertEquals(
				draft.withProposedPredicate(new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1)),
				codec.decode(codec.encode(draft.withProposedPredicate(new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1)))),
				"atomic proposal replacement retains draft identity and candidate IDs"
		);
		String legacy = codec.encode(draft).replace("\"dimension_id\":\"minecraft:the_nether\",", "");
		assertEquals(GoalPredicate.DEFAULT_DIMENSION, codec.decode(legacy).dimensionId(),
				"legacy persisted drafts retain their historical Overworld interpretation");
		return 3;
	}

	private static int verifyWorldValidation() {
		GoalPredicate translated = new GoalPredicate.AllOf(List.of(
				new GoalPredicate.PositionWithin(12, 64, -8, 1, 20),
				new GoalPredicate.BlockMatches(12, 64, -8, "minecraft:oak_stairs", Map.of("facing", "north"))
		));
		GoalPredicate bound = GoalPredicateWorldValidator.bindToDimension(translated, "minecraft:the_nether");
		GoalPredicate.AllOf compound = (GoalPredicate.AllOf) bound;
		assertEquals("minecraft:the_nether", ((GoalPredicate.PositionWithin) compound.predicates().get(0)).dimensionId(),
				"translated position binds to the requester's live dimension");
		assertEquals("minecraft:the_nether", ((GoalPredicate.BlockMatches) compound.predicates().get(1)).dimensionId(),
				"translated block binds to the requester's live dimension");
		GoalPredicateWorldValidator.validate("minecraft:the_nether", y -> y >= 0 && y < 128, bound);
		assertTrue(GoalPredicateWorldValidator.requiresLiveLevel(bound),
				"spatial compounds require a live dimension before staging or activation");
		expectCode("GOAL_COORDINATES_OUT_OF_BUILD_HEIGHT", () -> GoalPredicateWorldValidator.validate(
				"minecraft:overworld", y -> y >= -64 && y < 320,
				new GoalPredicate.PositionWithin("minecraft:overworld", 0, 1_000, 0, 1, 20)),
				"coordinates outside the selected dimension build height are rejected");
		expectCode("GOAL_DIMENSION_MISMATCH", () -> GoalPredicateWorldValidator.validate(
				"minecraft:the_nether", y -> true,
				new GoalPredicate.PositionWithin("minecraft:overworld", 0, 64, 0, 1, 20)),
				"a spatial predicate cannot escape its selected dimension");
		GoalPredicateWorldValidator.validateBlockProperties("minecraft:oak_stairs", Map.of("facing", "north"));
		expectCode("INVALID_GOAL_BLOCK_PROPERTY", () -> GoalPredicateWorldValidator.validateBlockProperties(
				"minecraft:oak_stairs", Map.of("imaginary", "north")),
				"unknown block-state properties are rejected before staging");
		expectCode("INVALID_GOAL_BLOCK_PROPERTY_VALUE", () -> GoalPredicateWorldValidator.validateBlockProperties(
				"minecraft:oak_stairs", Map.of("facing", "upwards")),
				"unknown block-state values are rejected before staging");
		return 7;
	}

	private static int verifyWireBoundaryInvariants() {
		GoalSpecWireCodec codec = new GoalSpecWireCodec();
		GoalPredicate.PositionWithin minimumPosition = new GoalPredicate.PositionWithin(12, 64, -8, 0.01, 1);
		assertEquals(minimumPosition, codec.decodePredicate(codec.encodePredicate(minimumPosition)),
				"minimum executable position radius survives the Java wire round-trip");
		GoalPredicate.EntityKilledByAgent attributedKill =
				new GoalPredicate.EntityKilledByAgent("minecraft:zombie", true);
		assertEquals(attributedKill, codec.decodePredicate(codec.encodePredicate(attributedKill)),
				"post-activation kill attribution survives the Java wire round-trip");
		expectCode("INVALID_GOAL_PREDICATE", () -> codec.decodePredicate(JsonParser.parseString("""
				{"type":"position_within","x":12,"y":64,"z":-8,"radius":0,"stableTicks":1}
				""").getAsJsonObject()), "zero-radius wire predicates fail closed");
		GoalPredicate historicalKill = codec.decodePredicate(JsonParser.parseString("""
				{"type":"entity_killed_by_agent","entityType":"minecraft:zombie","afterGoalStart":false}
				""").getAsJsonObject());
		assertEquals(new GoalPredicate.EntityKilledByAgent("minecraft:zombie", false), historicalKill,
				"general goal decoding preserves explicit historical attribution semantics");
		expectCode("INVALID_GOAL_PREDICATE",
				() -> GoalPredicateWorldValidator.validateTranslatedProposal(historicalKill),
				"pre-goal kill attribution fails closed at the translated-proposal boundary");
		return 5;
	}

	private static int verifySpecAwareLifecycleStart() {
		GoalSpec spec = new GoalCompiler().compile(
				"Get an iron pickaxe", RegistryAccess.EMPTY, 1_200L
		).acceptedSpec().orElseThrow();
		AgentRecord idle = AgentRecord.create(
				AgentId.random(),
				new AgentProfile("codex", "gpt-5.6-sol", "high", Optional.empty(), 0),
				10_000L
		);
		AgentRecord started = AgentLifecycleReducer.start(idle, spec, 10_001L).after();
		assertEquals(spec, started.currentGoal().orElseThrow().spec(), "lifecycle starts the frozen compiled specification");
		assertEquals(dev.agaminggod.arenaagents.agent.goal.GoalStatus.ACTIVE,
				started.currentGoal().orElseThrow().status(), "compiled goal starts active");

		GoalSpec queuedSpec = new GoalCompiler().compile(
				"Get a diamond pickaxe", RegistryAccess.EMPTY, 1_201L
		).acceptedSpec().orElseThrow();
		AgentRecord queued = AgentLifecycleReducer.queue(started, queuedSpec, 8, 10_002L).after();
		UUID replacedGoalId = queued.currentGoal().orElseThrow().goalId();
		long replacedRevision = queued.goalRevision();
		GoalSpec replacement = new GoalCompiler().compile(
				"Get an iron axe", RegistryAccess.EMPTY, 1_202L
		).acceptedSpec().orElseThrow();
		var replaced = AgentLifecycleReducer.replace(queued, replacement, 10_003L);
		assertEquals(replacement, replaced.after().currentGoal().orElseThrow().spec(),
				"replace installs the confirmed frozen specification");
		assertEquals(false, replacedGoalId.equals(replaced.after().currentGoal().orElseThrow().goalId()),
				"replace creates a distinct goal identity");
		assertEquals(replacedRevision + 1L, replaced.after().goalRevision(),
				"replace advances the lifecycle revision exactly once");
		assertEquals(queued.queuedGoals(), replaced.after().queuedGoals(),
				"replace preserves already queued work");
		assertEquals(true, replaced.cancelAction(), "replace cancels physical work owned by the prior goal");
		return 7;
	}

	private static int verifyDraftAuthorizationAndChoices() {
		UUID requester = UUID.randomUUID();
		PendingGoalDraft idleDraft = new PendingGoalDraft(
				UUID.randomUUID(), AgentId.random(), requester, "Get an iron pickaxe",
				Optional.of(new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1)),
				DraftIntent.CONFIRM_TRANSLATION, 100L, 0L, Optional.empty()
		);
		assertEquals(GoalDraftResolution.Operation.START,
				GoalDraftResolution.authorize(idleDraft, requester, false, GoalDraftChoice.CONFIRM),
				"requester can confirm a validated idle draft");
		expectCode("GOAL_DRAFT_FORBIDDEN",
				() -> GoalDraftResolution.authorize(idleDraft, UUID.randomUUID(), false, GoalDraftChoice.CONFIRM),
				"another player cannot resolve the draft");
		assertEquals(GoalDraftResolution.Operation.START,
				GoalDraftResolution.authorize(idleDraft, UUID.randomUUID(), true, GoalDraftChoice.CONFIRM),
				"operator can confirm another player's draft");

		PendingGoalDraft activeDraft = new PendingGoalDraft(
				UUID.randomUUID(), idleDraft.agentId(), requester, "Get a diamond pickaxe",
				Optional.of(new GoalPredicate.InventoryContains("minecraft:diamond_pickaxe", 1)),
				DraftIntent.REPLACE_OR_QUEUE, 101L, 4L, Optional.of(UUID.randomUUID())
		);
		assertEquals(GoalDraftResolution.Operation.REPLACE,
				GoalDraftResolution.authorize(activeDraft, requester, false, GoalDraftChoice.REPLACE),
				"active draft explicitly replaces only after the player's choice");
		assertEquals(GoalDraftResolution.Operation.QUEUE,
				GoalDraftResolution.authorize(activeDraft, requester, false, GoalDraftChoice.QUEUE),
				"active draft can queue without changing current work");
		assertEquals(GoalDraftResolution.Operation.CANCEL,
				GoalDraftResolution.authorize(activeDraft, requester, false, GoalDraftChoice.CANCEL),
				"cancel removes only the draft");
		expectCode("GOAL_DRAFT_CHOICE_REQUIRED",
				() -> GoalDraftResolution.authorize(activeDraft, requester, false, GoalDraftChoice.CONFIRM),
				"active draft requires an explicit replace or queue choice");
		return 7;
	}

	private static int verifyDraftRevisionBinding() {
		AgentRecord idle = AgentRecord.create(
				AgentId.random(),
				new AgentProfile("codex", "gpt-5.6-sol", "high", Optional.empty(), 0),
				10_000L
		);
		PendingGoalDraft draft = new PendingGoalDraft(
				UUID.randomUUID(), idle.agentId(), UUID.randomUUID(), "Get an iron pickaxe",
				Optional.of(new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1)),
				DraftIntent.START, 100L, idle.goalRevision(), Optional.empty()
		);
		assertEquals(true, draft.matches(idle), "draft matches the exact idle revision it was created against");
		GoalSpec spec = new GoalCompiler().compile("Get an iron pickaxe", RegistryAccess.EMPTY, 100L)
				.acceptedSpec().orElseThrow();
		AgentRecord started = AgentLifecycleReducer.start(idle, spec, 10_001L).after();
		assertEquals(false, draft.matches(started), "draft becomes stale when the goal revision changes");
		return 2;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
		System.out.println("PASS: " + label);
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
		System.out.println("PASS: " + label);
	}

	private static void expectCode(String code, Runnable operation, String label) {
		try {
			operation.run();
			throw new AssertionError(label + ": expected " + code);
		} catch (dev.agaminggod.arenaagents.agent.AgentDomainException exception) {
			assertEquals(code, exception.code(), label);
		}
	}

	private static void bindItemStackSize(Item item, int maxStackSize) {
		item.builtInRegistryHolder().bindComponents(
				DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, maxStackSize).build()
		);
	}
}
