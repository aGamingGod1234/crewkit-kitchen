package dev.agaminggod.arenaagents.verification;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.agent.AgentRegistryVerification;
import dev.agaminggod.arenaagents.agent.AgentIdentityVerification;
import dev.agaminggod.arenaagents.server.AgentActivityPresentationVerification;
import dev.agaminggod.arenaagents.client.ArenaAgentsClientBootstrapVerification;
import dev.agaminggod.arenaagents.client.ArenaSpectatorStateVerification;
import dev.agaminggod.arenaagents.client.gui.scenario.ScenarioSetupStateVerification;
import dev.agaminggod.arenaagents.client.action.ActionExecutorVerification;
import dev.agaminggod.arenaagents.client.action.CombatInteractionVerification;
import dev.agaminggod.arenaagents.client.action.MinecraftActionContextVerification;
import dev.agaminggod.arenaagents.client.action.MoveToActionVerification;
import dev.agaminggod.arenaagents.client.bridge.BridgeConcurrencyVerification;
import dev.agaminggod.arenaagents.client.bridge.BridgeActionIntegrationVerification;
import dev.agaminggod.arenaagents.client.bridge.BridgeEventSink;
import dev.agaminggod.arenaagents.client.bridge.BridgeServer;
import dev.agaminggod.arenaagents.client.bridge.BridgeSession;
import dev.agaminggod.arenaagents.client.config.AgentConfig;
import dev.agaminggod.arenaagents.client.config.AgentConfigLoader;
import dev.agaminggod.arenaagents.client.perception.BlockSnapshot;
import dev.agaminggod.arenaagents.client.perception.EntitySnapshot;
import dev.agaminggod.arenaagents.client.perception.InventorySnapshot;
import dev.agaminggod.arenaagents.client.perception.Observation;
import dev.agaminggod.arenaagents.client.perception.ObservationCollectorVerification;
import dev.agaminggod.arenaagents.client.perception.ObservationLimits;
import dev.agaminggod.arenaagents.client.perception.ObservationOrdering;
import dev.agaminggod.arenaagents.client.perception.ObservationWireBudgetVerification;
import dev.agaminggod.arenaagents.client.navigation.LocalPathfinderVerification;
import dev.agaminggod.arenaagents.client.navigation.NavigationMovementVerification;
import dev.agaminggod.arenaagents.client.navigation.MinecraftWalkabilityViewVerification;
import dev.agaminggod.arenaagents.client.network.GoalReceiverVerification;
import dev.agaminggod.arenaagents.control.AgentControlVerification;
import dev.agaminggod.arenaagents.protocol.ActionCommand;
import dev.agaminggod.arenaagents.protocol.ActionResult;
import dev.agaminggod.arenaagents.protocol.ActionState;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.protocol.ProtocolCodec;
import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import dev.agaminggod.arenaagents.protocol.ProtocolException;
import dev.agaminggod.arenaagents.server.GoalControlVerification;
import dev.agaminggod.arenaagents.server.AgentModelArgumentVerification;
import dev.agaminggod.arenaagents.server.AgentSpawnPlacementVerification;
import dev.agaminggod.arenaagents.server.OfflineAgentPlayersVerification;
import dev.agaminggod.arenaagents.server.PendingSpawnCancellationLedgerVerification;
import dev.agaminggod.arenaagents.server.bridge.BridgeEnvelopeCodecVerification;
import dev.agaminggod.arenaagents.server.bridge.ProgramActionLedgerVerification;
import dev.agaminggod.arenaagents.server.bridge.CoordinatorStatusVerification;
import dev.agaminggod.arenaagents.server.bridge.MultiplexedServerBridgeVerification;
import dev.agaminggod.arenaagents.server.perception.BlockObservationOrderingVerification;
import dev.agaminggod.arenaagents.server.perception.InventoryObservationSlotsVerification;
import dev.agaminggod.arenaagents.server.perception.ObservationBudgetVerification;
import dev.agaminggod.arenaagents.server.runtime.ActionProgressTrackerVerification;
import dev.agaminggod.arenaagents.server.runtime.BlockPlacementPostconditionVerification;
import dev.agaminggod.arenaagents.server.runtime.BlockPlacementAttemptPolicyVerification;
import dev.agaminggod.arenaagents.server.runtime.DesiredBlockStateVerification;
import dev.agaminggod.arenaagents.server.runtime.ResourceLeaseManagerVerification;
import dev.agaminggod.arenaagents.server.runtime.ServerActionExecutorVerification;
import dev.agaminggod.arenaagents.server.runtime.transaction.EquipmentAndUseVerification;
import dev.agaminggod.arenaagents.server.runtime.transaction.TransactionPostconditionVerification;
import dev.agaminggod.arenaagents.server.runtime.transaction.TransactionProtocolVerification;
import dev.agaminggod.arenaagents.server.runtime.controller.ServerPathPlannerVerification;
import dev.agaminggod.arenaagents.server.runtime.controller.NavigationProgressVerification;
import dev.agaminggod.arenaagents.server.runtime.controller.CombatPolicyVerification;
import dev.agaminggod.arenaagents.server.runtime.controller.CombatNavigationFailureVerification;
import dev.agaminggod.arenaagents.server.runtime.controller.SurvivalReflexVerification;
import dev.agaminggod.arenaagents.server.runtime.controller.BuildSequenceProgressVerification;
import dev.agaminggod.arenaagents.server.runtime.controller.ItemPickupProgressVerification;
import dev.agaminggod.arenaagents.scenario.ScenarioCoreVerification;
import dev.agaminggod.arenaagents.scenario.ArenaSpectatorSnapshotVerification;
import dev.agaminggod.arenaagents.scenario.ScenarioLaunchRuntimeVerification;
import dev.agaminggod.arenaagents.scenario.ScenarioMatchResultVerification;
import dev.agaminggod.arenaagents.scenario.ScenarioPreflightVerification;
import dev.agaminggod.arenaagents.scenario.ScenarioRecoveryVerification;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public final class VerificationMain {
	private static final long ISSUED_AT_EPOCH_MS = 1_750_000_000_000L;
	private static final String COMMAND_ID = "command-1";
	private static final String AGENT_ID = "agent-test";
	private static final String DESIRED_OAK_STAIRS_STATE =
			"minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]";
	private static final int SOCKET_TIMEOUT_MS = 2_000;
	private static final long ASYNC_TIMEOUT_MS = 3_000L;
	private static final long AUTHENTICATION_RETRY_DELAY_MS = 10L;
	private static final long CONCURRENT_CLOSE_OBSERVATION_MS = 100L;
	private static final int GENERATED_OUTBOUND_EVENT_COUNT = 4_097;
	private static final int EXPECTED_HANDSHAKE_TIMEOUT_MS = 1_000;
	private static int passedAssertions;

	private VerificationMain() {
	}

	public static void main(String[] args) throws Exception {
		ProtocolCodec codec = new ProtocolCodec();

		verifyProtocolConstants();
		verifyActionWireNames();
		verifyValidActionUnion(codec);
		verifyBriefExamples(codec);
		verifyEnvelopeValidation(codec);
		verifyDirectCommandSchemaValidation();
		verifyBoundedValidation(codec);
		verifyStrictArguments(codec);
		verifyDesiredStateProtocol(codec);
		verifyBuildSequenceProtocol(codec);
		verifyCommandImmutability();
		verifyCommandEncodingRoundTrip(codec);
		verifyActionResultContract();
		passedAssertions += ActionExecutorVerification.verifyLifecycle();
		passedAssertions += ActionExecutorVerification.verifyPrimitives();
		passedAssertions += MoveToActionVerification.verify();
		passedAssertions += CombatInteractionVerification.verify();
		passedAssertions += MinecraftActionContextVerification.verifyHelpers();
		passedAssertions += LocalPathfinderVerification.verify();
		passedAssertions += NavigationMovementVerification.verify();
		passedAssertions += MinecraftWalkabilityViewVerification.verify();
		passedAssertions += GoalControlVerification.verify();
		passedAssertions += AgentModelArgumentVerification.verify();
		passedAssertions += AgentActivityPresentationVerification.verify();
		passedAssertions += AgentSpawnPlacementVerification.verify();
		passedAssertions += OfflineAgentPlayersVerification.verify();
		passedAssertions += PendingSpawnCancellationLedgerVerification.verify();
		passedAssertions += AgentRegistryVerification.verify();
		passedAssertions += AgentIdentityVerification.verify();
		passedAssertions += AgentControlVerification.verify();
		passedAssertions += BlockObservationOrderingVerification.verify();
		passedAssertions += InventoryObservationSlotsVerification.verify();
		passedAssertions += ObservationBudgetVerification.verify();
		passedAssertions += ActionProgressTrackerVerification.verify();
		passedAssertions += BlockPlacementPostconditionVerification.verify();
		passedAssertions += BlockPlacementAttemptPolicyVerification.verify();
		passedAssertions += DesiredBlockStateVerification.verify();
		passedAssertions += ResourceLeaseManagerVerification.verify();
		passedAssertions += TransactionProtocolVerification.verify();
		passedAssertions += TransactionPostconditionVerification.verify();
		passedAssertions += EquipmentAndUseVerification.verify();
		passedAssertions += ServerActionExecutorVerification.verify();
		passedAssertions += ServerPathPlannerVerification.verify();
		passedAssertions += NavigationProgressVerification.verify();
		passedAssertions += CombatPolicyVerification.verify();
		passedAssertions += CombatNavigationFailureVerification.verify();
		passedAssertions += SurvivalReflexVerification.verify();
		passedAssertions += BuildSequenceProgressVerification.verify();
		passedAssertions += ItemPickupProgressVerification.verify();
		passedAssertions += ScenarioCoreVerification.verify();
		passedAssertions += ScenarioLaunchRuntimeVerification.verify();
		passedAssertions += ScenarioMatchResultVerification.verify();
		passedAssertions += ScenarioPreflightVerification.verify();
		passedAssertions += ScenarioRecoveryVerification.verify();
		passedAssertions += ArenaSpectatorSnapshotVerification.verify();
		passedAssertions += ArenaSpectatorStateVerification.verify();
		passedAssertions += ArenaAgentsClientBootstrapVerification.verify();
		passedAssertions += ScenarioSetupStateVerification.verify();
		passedAssertions += BridgeEnvelopeCodecVerification.verify();
		passedAssertions += ProgramActionLedgerVerification.verify();
		passedAssertions += CoordinatorStatusVerification.verify();
		passedAssertions += MultiplexedServerBridgeVerification.verify();
		passedAssertions += GoalReceiverVerification.verify();
		verifyObservationContracts(codec);
		ObservationCollectorVerification.verifyLoadedChunkBoundary();
		ObservationCollectorVerification.verifyEntityDistanceBoundary();
		ObservationWireBudgetVerification.verifyWorstCaseObservationFits();
		verifyAgentConfigParsing();
		verifyAgentConfigFiles();
		verifyJsonLineFraming(codec);
		verifyBridgeAuthenticationAndDispatch(codec);
		passedAssertions += BridgeActionIntegrationVerification.verifyLifecycleEventsAndCancellation();
		verifyBridgeObservationRequestDispatch(codec);
		verifyClosedSessionDropsQueuedAction(codec);
		verifyGeneratedErrorIdIsContained(codec);
		verifyGeneratedOutboundMessageIds(codec);
		verifyHandshakeTimeoutReleasesSession(codec);
		verifyAuthenticatedRetryClosesFailedSocket(codec);
		verifyHelloAcknowledgementPrecedesEvents(codec);
		verifyCloseLinearizesPendingAdmission(codec);
		verifyConcurrentCloseWaitsForCallback(codec);
		verifyQueueOverflowDoesNotInvertCallbackLock(codec);
		verifyCallbackFailureShutdownDoesNotWaitForBlockedOutput(codec);
		verifyBridgeCallbackFailure(codec);
		verifyBridgeControlValidation(codec);
		verifyBridgeSingleSessionAndReconnect(codec);
		verifyBridgeOutboundEventsAndShutdown(codec);

		System.out.printf("PASS: %d protocol and bridge assertions%n", passedAssertions);
	}

	private static void verifyProtocolConstants() {
		assertEquals(1, ProtocolConstants.PROTOCOL_VERSION, "protocol version");
		assertEquals(65_536, ProtocolConstants.MAX_LINE_BYTES, "line byte limit");
		assertEquals(600_000L, ProtocolConstants.MAX_DURATION_MS, "duration limit");
	}

	private static void verifyActionWireNames() {
		List<String> expectedWireNames = List.of(
				"move_to",
				"look_at",
				"attack",
				"select_item",
				"use_item",
				"break_block",
				"place_block",
				"build_sequence",
				"chat",
				"wait",
				"set_door",
				"pick_up_item",
				"drop_item",
				"navigate_to",
				"fight_target",
				"flee_from",
				"follow_entity",
				"transfer_container",
				"craft_inventory",
				"craft_table",
				"furnace_transaction",
				"equip_item",
				"select_tool",
				"block_with_shield",
				"use_ranged",
				"respawn",
				"complete_goal"
		);
		List<String> actualWireNames = List.of(ActionType.values()).stream()
				.map(ActionType::wireName)
				.toList();

		assertEquals(expectedWireNames, actualWireNames, "exhaustive action wire names");
		assertEquals(ActionType.WAIT, ActionType.fromWireName("wait").orElseThrow(), "wire action lookup");
		assertTrue(ActionType.fromWireName("WAIT").isEmpty(), "wire action lookup is case-sensitive");
		assertTrue(ActionType.fromWireName(null).isEmpty(), "null wire action lookup is empty");
	}

	private static void verifyValidActionUnion(ProtocolCodec codec) throws ProtocolException {
		assertDecodedType(codec, "move_to", "\"x\":1.5,\"y\":64,\"z\":-2.5,\"tolerance\":0.75,\"sprint\":true", ActionType.MOVE_TO);
		assertDecodedType(codec, "look_at", "\"x\":1,\"y\":65.25,\"z\":3", ActionType.LOOK_AT);
		assertDecodedType(codec, "attack", "\"targetSelector\":\"nearest_hostile\",\"timeoutMs\":5000", ActionType.ATTACK);
		assertDecodedType(codec, "select_item", "\"itemId\":\"minecraft:diamond_sword\"", ActionType.SELECT_ITEM);
		assertDecodedType(codec, "use_item", "\"durationMs\":1250", ActionType.USE_ITEM);
		assertDecodedType(codec, "break_block", "\"x\":1,\"y\":64,\"z\":-2,\"timeoutMs\":5000", ActionType.BREAK_BLOCK);
		assertDecodedType(codec, "place_block", "\"x\":1,\"y\":64,\"z\":-2,\"face\":\"up\",\"itemId\":\"minecraft:stone\",\"desiredState\":null", ActionType.PLACE_BLOCK);
		assertDecodedType(codec, "build_sequence", "\"placements\":[{\"x\":1,\"y\":64,\"z\":-2,\"face\":\"up\",\"itemId\":\"minecraft:stone\",\"desiredState\":null}],\"timeoutMs\":60000", ActionType.BUILD_SEQUENCE);
		assertDecodedType(codec, "chat", "\"message\":\"Ready.\"", ActionType.CHAT);
		assertDecodedType(codec, "wait", "\"durationMs\":250", ActionType.WAIT);
		assertDecodedType(codec, "set_door", "\"x\":1,\"y\":64,\"z\":-2,\"open\":true", ActionType.SET_DOOR);
		assertDecodedType(codec, "pick_up_item", "\"targetSelector\":\"minecraft:item\"", ActionType.PICK_UP_ITEM);
		assertDecodedType(codec, "drop_item", "\"slot\":0,\"count\":1", ActionType.DROP_ITEM);
		assertDecodedType(codec, "navigate_to", "\"x\":10,\"y\":64,\"z\":-5,\"tolerance\":1.25,\"sprint\":true,\"timeoutMs\":30000", ActionType.NAVIGATE_TO);
		assertDecodedType(codec, "fight_target", "\"targetSelector\":\"nearest_hostile\",\"desiredRange\":2.5,\"timeoutMs\":15000", ActionType.FIGHT_TARGET);
		assertDecodedType(codec, "flee_from", "\"targetSelector\":\"last_attacker\",\"distance\":16,\"timeoutMs\":10000", ActionType.FLEE_FROM);
		assertDecodedType(codec, "follow_entity", "\"targetSelector\":\"player:Lucas\",\"distance\":3,\"timeoutMs\":30000", ActionType.FOLLOW_ENTITY);
		assertDecodedType(codec, "respawn", "", ActionType.RESPAWN);
		assertDecodedType(codec, "complete_goal", "\"summary\":\"Reached the arena.\"", ActionType.COMPLETE_GOAL);
	}

	private static void verifyBriefExamples(ProtocolCodec codec) throws ProtocolException {
		expectProtocolException(
				() -> codec.decodeCommand("{\"type\":\"unknown\"}"),
				"UNKNOWN_ACTION",
				"unknown",
				"unknown action"
		);
		expectProtocolException(
				() -> codec.decodeCommand("{\"type\":\"wait\",\"durationMs\":600001}"),
				"OUT_OF_RANGE",
				"durationMs",
				"overlong wait"
		);

		ActionCommand command = codec.decodeCommand(commandJson("wait", "\"durationMs\":250"));
		assertEquals(ActionType.WAIT, command.type(), "wait action type");
	}

	private static void verifyEnvelopeValidation(ProtocolCodec codec) {
		expectProtocolException(
				() -> codec.decodeCommand("{not-json"),
				"MALFORMED_JSON",
				"JSON",
				"malformed JSON"
		);
		expectProtocolException(
				() -> codec.decodeCommand("[]"),
				"MALFORMED_JSON",
				"object",
				"non-object JSON"
		);
		expectProtocolException(
				() -> codec.decodeCommand("{\"type\":\"wait\",\"durationMs\":1,\"commandId\":\"command-1\",\"issuedAtEpochMs\":1}"),
				"MISSING_FIELD",
				"protocolVersion",
				"required protocol version"
		);
		expectProtocolException(
				() -> codec.decodeCommand("{\"protocolVersion\":2,\"commandId\":\"command-1\",\"type\":\"wait\",\"issuedAtEpochMs\":1,\"durationMs\":1}"),
				"UNSUPPORTED_VERSION",
				"2",
				"unsupported protocol version"
		);
		expectProtocolException(
				() -> codec.decodeCommand("{\"protocolVersion\":1,\"type\":\"wait\",\"issuedAtEpochMs\":1,\"durationMs\":1}"),
				"MISSING_FIELD",
				"commandId",
				"required command id"
		);
		expectProtocolException(
				() -> codec.decodeCommand("{\"protocolVersion\":1,\"commandId\":\"command-1\",\"issuedAtEpochMs\":1}"),
				"MISSING_FIELD",
				"type",
				"required action type"
		);
		expectProtocolException(
				() -> codec.decodeCommand("{\"protocolVersion\":1,\"commandId\":\"command-1\",\"type\":\"wait\",\"durationMs\":1}"),
				"MISSING_FIELD",
				"issuedAtEpochMs",
				"required issue timestamp"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson("wait", "\"durationMs\":1,\"extra\":true")),
				"UNKNOWN_FIELD",
				"extra",
				"unknown action field"
		);
	}

	private static void verifyBoundedValidation(ProtocolCodec codec) {
		String oversizedUtf8Line = "{\"type\":\"chat\",\"message\":\"" + "\u00e9".repeat(ProtocolConstants.MAX_LINE_BYTES / 2) + "\"}";
		expectProtocolException(
				() -> codec.decodeCommand(oversizedUtf8Line),
				"LINE_TOO_LARGE",
				Integer.toString(ProtocolConstants.MAX_LINE_BYTES),
				"UTF-8 line byte limit"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson("look_at", "\"x\":1e309,\"y\":64,\"z\":0")),
				"OUT_OF_RANGE",
				"x",
				"finite coordinate"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson("break_block", "\"x\":1.5,\"y\":64,\"z\":0,\"timeoutMs\":1")),
				"OUT_OF_RANGE",
				"x",
				"integral block coordinate"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson(
						"break_block",
						"\"x\":1.00000000000000001,\"y\":64,\"z\":0,\"timeoutMs\":1"
				)),
				"OUT_OF_RANGE",
				"x",
				"exact break block coordinate"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson(
						"place_block",
						"\"x\":0,\"y\":64,\"z\":-2.00000000000000001,\"face\":\"up\",\"itemId\":\"minecraft:stone\""
				)),
				"OUT_OF_RANGE",
				"z",
				"exact place block coordinate"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson("wait", "\"durationMs\":0")),
				"OUT_OF_RANGE",
				"durationMs",
				"minimum duration"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson("chat", "\"message\":\"" + "a".repeat(ProtocolConstants.MAX_CHAT_LENGTH + 1) + "\"")),
				"OUT_OF_RANGE",
				"message",
				"chat text limit"
		);
	}

	private static void verifyStrictArguments(ProtocolCodec codec) throws ProtocolException {
		expectProtocolException(
				() -> codec.decodeCommand(commandJson("wait", "")),
				"MISSING_FIELD",
				"durationMs",
				"required action argument"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson("move_to", "\"x\":0,\"y\":64,\"z\":0,\"tolerance\":1,\"sprint\":\"yes\"")),
				"INVALID_FIELD",
				"sprint",
				"boolean action argument"
		);
		expectProtocolException(
				() -> codec.decodeCommand(commandJson("place_block", "\"x\":0,\"y\":64,\"z\":0,\"face\":\"forward\",\"itemId\":\"minecraft:stone\"")),
				"INVALID_FIELD",
				"face",
				"block face"
		);

		ActionCommand shortestWait = codec.decodeCommand(commandJson("wait", "\"durationMs\":1"));
		ActionCommand longestWait = codec.decodeCommand(commandJson("wait", "\"durationMs\":" + ProtocolConstants.MAX_DURATION_MS));
		assertEquals(1L, shortestWait.arguments().get("durationMs").getAsLong(), "minimum valid duration");
		assertEquals(ProtocolConstants.MAX_DURATION_MS, longestWait.arguments().get("durationMs").getAsLong(), "maximum valid duration");
	}

	private static void verifyDesiredStateProtocol(ProtocolCodec codec) throws ProtocolException {
		ActionCommand command = codec.decodeCommand(commandJson(
				"place_block",
				"\"x\":1,\"y\":64,\"z\":-2,\"face\":\"up\",\"itemId\":\"minecraft:oak_stairs\",\"desiredState\":\""
						+ DESIRED_OAK_STAIRS_STATE + "\""
		));
		assertEquals(
			DESIRED_OAK_STAIRS_STATE,
			command.arguments().get("desiredState").getAsString(),
			"desired block state survives Java protocol validation"
		);

		ActionCommand nullable = codec.decodeCommand(commandJson(
				"place_block",
				"\"x\":1,\"y\":64,\"z\":-2,\"face\":\"up\",\"itemId\":\"minecraft:oak_stairs\",\"desiredState\":null"
		));
		assertTrue(nullable.arguments().get("desiredState").isJsonNull(), "null desired block state is accepted");

		ActionCommand mismatchedBlockId = codec.decodeCommand(commandJson(
				"place_block",
				"\"x\":1,\"y\":64,\"z\":-2,\"face\":\"up\",\"itemId\":\"minecraft:oak_stairs\",\"desiredState\":\"minecraft:stone[facing=north]\""
		));
		assertEquals(
			"minecraft:stone[facing=north]",
			mismatchedBlockId.arguments().get("desiredState").getAsString(),
			"block-id mismatch is deferred to execution validation"
		);

		expectProtocolException(
				() -> codec.decodeCommand(commandJson(
						"place_block",
						"\"x\":1,\"y\":64,\"z\":-2,\"face\":\"up\",\"itemId\":\"minecraft:oak_stairs\",\"desiredState\":\""
								+ "x".repeat(513) + "\""
				)),
				"OUT_OF_RANGE",
				"desiredState",
				"desired block state length"
		);
	}

	private static void verifyBuildSequenceProtocol(ProtocolCodec codec) throws ProtocolException {
		String placement = "{\"x\":1,\"y\":64,\"z\":-2,\"face\":\"up\","
				+ "\"itemId\":\"minecraft:stone\",\"desiredState\":null}";
		String placements32 = java.util.stream.IntStream.range(0, 32)
				.mapToObj(ignored -> placement)
				.collect(java.util.stream.Collectors.joining(","));
		ActionCommand command = codec.decodeCommand(commandJson(
				"build_sequence", "\"placements\":[" + placements32 + "],\"timeoutMs\":60000"));
		assertEquals(32, command.arguments().getAsJsonArray("placements").size(),
				"build sequence preserves 32 ordered placements");
		expectProtocolException(
				() -> codec.decodeCommand(commandJson(
						"build_sequence", "\"placements\":[" + placements32 + "," + placement + "],\"timeoutMs\":60000")),
				"OUT_OF_RANGE",
				"placements",
				"build sequence placement limit"
		);
	}

	private static void verifyDirectCommandSchemaValidation() {
		JsonObject validArguments = new JsonObject();
		validArguments.addProperty("durationMs", 250);
		ActionCommand validCommand = new ActionCommand(
				COMMAND_ID,
				ActionType.WAIT,
				validArguments,
				ISSUED_AT_EPOCH_MS
		);
		assertEquals(250L, validCommand.arguments().get("durationMs").getAsLong(), "direct valid command arguments");

		expectProtocolException(
				() -> new ActionCommand(
						COMMAND_ID,
						ActionType.WAIT,
						new JsonObject(),
						ISSUED_AT_EPOCH_MS
				),
				"MISSING_FIELD",
				"durationMs",
				"direct command required argument"
		);

		JsonObject unknownArguments = validArguments.deepCopy();
		unknownArguments.addProperty("extra", true);
		expectProtocolException(
				() -> new ActionCommand(
						COMMAND_ID,
						ActionType.WAIT,
						unknownArguments,
						ISSUED_AT_EPOCH_MS
				),
				"UNKNOWN_FIELD",
				"extra",
				"direct command unknown argument"
		);

		JsonObject outOfRangeArguments = new JsonObject();
		outOfRangeArguments.addProperty("durationMs", ProtocolConstants.MAX_DURATION_MS + 1L);
		expectProtocolException(
				() -> new ActionCommand(
						COMMAND_ID,
						ActionType.WAIT,
						outOfRangeArguments,
						ISSUED_AT_EPOCH_MS
				),
				"OUT_OF_RANGE",
				"durationMs",
				"direct command bounded argument"
		);
	}

	private static void verifyCommandImmutability() {
		JsonObject sourceArguments = new JsonObject();
		sourceArguments.addProperty("durationMs", 250);
		ActionCommand command = new ActionCommand(COMMAND_ID, ActionType.WAIT, sourceArguments, ISSUED_AT_EPOCH_MS);

		sourceArguments.addProperty("durationMs", 500);
		JsonObject returnedArguments = command.arguments();
		returnedArguments.addProperty("durationMs", 750);

		assertEquals(250L, command.arguments().get("durationMs").getAsLong(), "action arguments are defensively copied");
		expectProtocolException(
				() -> new ActionCommand(
						"a".repeat(ProtocolConstants.MAX_COMMAND_ID_LENGTH + 1),
						ActionType.WAIT,
						sourceArguments,
						ISSUED_AT_EPOCH_MS
				),
				"OUT_OF_RANGE",
				"commandId",
				"record command id limit"
		);
	}

	private static void verifyCommandEncodingRoundTrip(ProtocolCodec codec) throws ProtocolException {
		JsonObject arguments = new JsonObject();
		arguments.addProperty("durationMs", 250);
		ActionCommand original = new ActionCommand(COMMAND_ID, ActionType.WAIT, arguments, ISSUED_AT_EPOCH_MS);

		String encoded = codec.encode(original);
		ActionCommand decoded = codec.decodeCommand(encoded);

		assertTrue(encoded.contains("\"protocolVersion\":1"), "encoded command protocol version");
		assertTrue(encoded.contains("\"type\":\"wait\""), "encoded action wire name");
		assertTrue(!encoded.contains("\"arguments\""), "encoded action arguments are flattened");
		assertEquals(original, decoded, "command encoding round trip");
	}

	private static void verifyActionResultContract() throws ProtocolException {
		ActionResult result = new ActionResult(
				COMMAND_ID,
				ActionState.SUCCEEDED,
				"COMPLETED",
				"Goal completed.",
				ISSUED_AT_EPOCH_MS + 250
		);

		assertEquals(ActionState.SUCCEEDED, result.state(), "action result state");
		assertTrue(result.state().isTerminal(), "successful action is terminal");
		assertTrue(!ActionState.RUNNING.isTerminal(), "running action is non-terminal");
		expectProtocolException(
				() -> new ActionResult(COMMAND_ID, ActionState.RUNNING, "RUNNING", "Still running.", ISSUED_AT_EPOCH_MS),
				"INVALID_FIELD",
				"terminal",
				"result rejects non-terminal state"
		);

		String encoded = new ProtocolCodec().encode(result);
		assertTrue(encoded.contains("\"protocolVersion\":1"), "encoded result protocol version");
		assertTrue(encoded.contains("\"state\":\"SUCCEEDED\""), "encoded terminal state");
	}

	private static void verifyObservationContracts(ProtocolCodec codec) throws ProtocolException {
		List<EntitySnapshot> entities = List.of(
				new EntitySnapshot(
						"entity-far",
						"minecraft:zombie",
						"far",
						4.0D,
						64.0D,
						0.0D,
						16.0D,
						20.0F,
						20.0F,
						true
				),
				new EntitySnapshot(
						"entity-near-b",
						"minecraft:cow",
						"near-b",
						1.0D,
						64.0D,
						0.0D,
						1.0D,
						10.0F,
						10.0F,
						false
				),
				new EntitySnapshot(
						"entity-near-a",
						"minecraft:pig",
						"near-a",
						-1.0D,
						64.0D,
						0.0D,
						1.0D,
						10.0F,
						10.0F,
						false
				)
		);
		assertEquals(
				List.of("near-a", "near-b", "far"),
				ObservationOrdering.entities(entities).stream().map(EntitySnapshot::name).toList(),
				"entity ordering by distance and stable identifier"
		);

		List<BlockSnapshot> blocks = new ArrayList<>();
		for (int index = 0; index < ObservationLimits.MAX_BLOCKS + 5; index++) {
			blocks.add(new BlockSnapshot(
					index,
					64,
					0,
					"minecraft:stone",
					"minecraft:empty",
					false,
					true,
					index * (double) index
			));
		}
		List<BlockSnapshot> truncatedBlocks = ObservationLimits.truncateBlocks(
				blocks,
				ObservationLimits.MAX_BLOCKS
		);
		assertEquals(128, truncatedBlocks.size(), "block cap");
		assertEquals(127, truncatedBlocks.getLast().x(), "block truncation preserves deterministic prefix");

		List<InventorySnapshot.ItemSummary> mutableItems = new ArrayList<>();
		mutableItems.add(new InventorySnapshot.ItemSummary("minecraft:stone", 32));
		InventorySnapshot inventory = new InventorySnapshot(0, "minecraft:stone", 32, mutableItems);
		mutableItems.add(new InventorySnapshot.ItemSummary("minecraft:dirt", 16));
		assertEquals(1, inventory.items().size(), "inventory snapshot defensively copies items");

		Observation unavailable = Observation.unavailable("world_not_ready");
		assertTrue(!unavailable.ready(), "unavailable observation is explicit");
		assertEquals("world_not_ready", unavailable.status(), "unavailable observation reason");
		assertTrue(unavailable.entities().isEmpty(), "unavailable observation has immutable empty entities");
		assertTrue(!unavailable.currentAction().present(), "unavailable observation has action placeholder");
		assertTrue(!unavailable.lastResult().present(), "unavailable observation has result placeholder");

		String encoded = codec.encode(unavailable);
		assertTrue(
				encoded.indexOf("\"ready\"") < encoded.indexOf("\"status\"")
						&& encoded.indexOf("\"status\"") < encoded.indexOf("\"position\"")
						&& encoded.indexOf("\"entities\"") < encoded.indexOf("\"blocks\""),
				"observation serialization field order"
		);
	}

	private static void verifyAgentConfigParsing() {
		String validJson = "{\"agentId\":\"agent-55\",\"bridgePort\":25571,"
				+ "\"observationRadius\":12,\"enabled\":true}";
		AgentConfig config = AgentConfigLoader.parse(validJson);
		assertEquals(25571, config.bridgePort(), "config bridge port");
		AgentConfig loopbackAlias = AgentConfigLoader.parse(validJson.substring(0, validJson.length() - 1)
				+ ",\"host\":\"127.0.0.2\"}");
		assertEquals("agent-55", loopbackAlias.agentId(), "config accepts numeric loopback alias");
		expectProtocolException(
				() -> AgentConfigLoader.parse(validJson.substring(0, validJson.length() - 1)
						+ ",\"host\":\"0.0.0.0\"}"),
				"LOOPBACK_REQUIRED",
				"127.0.0.1",
				"config non-loopback host"
		);
	}

	private static void verifyAgentConfigFiles() throws IOException {
		Path directory = Files.createTempDirectory("arenaagents-config-").toAbsolutePath().normalize();
		Path configPath = directory.resolve("arenaagents.json");
		try {
			AgentConfigLoader.loadOrCreate(configPath);
			String existingJson = "{\"agentId\":\"existing-agent\",\"bridgePort\":25572,"
					+ "\"observationRadius\":8,\"enabled\":true}";
			Files.writeString(configPath, existingJson, StandardCharsets.UTF_8);
			AgentConfig loaded = AgentConfigLoader.loadOrCreate(configPath);
			assertEquals("existing-agent", loaded.agentId(), "existing config loaded");
			assertEquals(existingJson, Files.readString(configPath, StandardCharsets.UTF_8), "existing config not overwritten");
		} finally {
			Files.deleteIfExists(configPath);
			Files.deleteIfExists(directory);
		}
	}

	private static void verifyJsonLineFraming(ProtocolCodec codec) throws IOException {
		String json = "{\"message\":\"ready\"}";
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		codec.writeLine(output, json);
		assertEquals(json, codec.readLine(new ByteArrayInputStream(output.toByteArray())), "UTF-8 JSONL round trip");
		byte[] oversizedLine = ("a".repeat(ProtocolConstants.MAX_LINE_BYTES + 1) + "\n")
				.getBytes(StandardCharsets.UTF_8);
		expectProtocolException(
				() -> codec.readLine(new ByteArrayInputStream(oversizedLine)),
				"LINE_TOO_LARGE",
				Integer.toString(ProtocolConstants.MAX_LINE_BYTES),
				"framing line byte limit"
		);
	}

	private static void verifyBridgeAuthenticationAndDispatch(ProtocolCodec codec) throws Exception {
		int port = findAvailablePort();
		RecordingExecutor executor = new RecordingExecutor();
		AtomicReference<ActionCommand> received = new AtomicReference<>();
		try (BridgeServer server = new BridgeServer(enabledConfig(port), codec, executor, received::set)) {
			server.start();
			try (Socket client = openClient(port)) {
				writeMessage(codec, client, actionEnvelope("before-hello"));
				assertErrorCode(codec, client, "AUTHENTICATION_REQUIRED", "first message must authenticate");
			}
			try (Socket client = connectAuthenticatedWithRetry(codec, port, "hello-authenticated")) {
				writeMessage(codec, client, actionEnvelope("action-1"));
				awaitCondition(() -> executor.pendingCount() == 1, "action callback queued");
				assertTrue(received.get() == null, "action callback waits for provided executor");
				executor.runNext();
				assertEquals(ActionType.WAIT, received.get().type(), "action callback decoded through protocol codec");
			}
		}
	}

	private static void verifyBridgeObservationRequestDispatch(ProtocolCodec codec) throws Exception {
		int port = findAvailablePort();
		RecordingExecutor executor = new RecordingExecutor();
		AtomicBoolean requested = new AtomicBoolean();
		AtomicReference<BridgeServer> serverReference = new AtomicReference<>();
		BridgeEventSink eventSink = new BridgeEventSink() {
			@Override
			public void onActionCommand(ActionCommand command) {
			}

			@Override
			public void onObservationRequested() {
				requested.set(true);
				JsonObject payload = new JsonObject();
				payload.addProperty("ready", false);
				payload.addProperty("status", "world_not_ready");
				serverReference.get().sendEvent("observation", payload);
			}
		};
		try (BridgeServer server = new BridgeServer(enabledConfig(port), codec, executor, eventSink)) {
			serverReference.set(server);
			server.start();
			try (Socket client = connectAuthenticated(codec, port, "hello-observation")) {
				writeMessage(codec, client, requestObservationEnvelope("request-observation-1"));
				awaitCondition(() -> executor.pendingCount() == 1, "observation callback queued");
				assertTrue(!requested.get(), "observation callback waits for provided executor");
				executor.runNext();
				assertTrue(requested.get(), "observation callback dispatched on provided executor");

				JsonObject observationEvent = readMessage(codec, client);
				assertEquals("observation", observationEvent.get("type").getAsString(), "observation event type");
				assertEquals(AGENT_ID, observationEvent.get("agentId").getAsString(), "observation event agent identity");
				assertEquals("world_not_ready", observationEvent.get("status").getAsString(), "observation event payload");
			}
		}
	}

	private static void verifyClosedSessionDropsQueuedAction(ProtocolCodec codec) throws Exception {
		int port = findAvailablePort();
		RecordingExecutor executor = new RecordingExecutor();
		AtomicReference<ActionCommand> received = new AtomicReference<>();
		BridgeServer server = new BridgeServer(enabledConfig(port), codec, executor, received::set);
		server.start();
		try (Socket client = connectAuthenticated(codec, port, "hello-stale-action")) {
			writeMessage(codec, client, actionEnvelope("action-stale"));
			awaitCondition(() -> executor.pendingCount() == 1, "stale action callback queued");
			server.close();
			executor.runNext();
			assertTrue(received.get() == null, "closed session drops queued action callback");
		} finally {
			server.close();
		}
	}

	private static void verifyGeneratedErrorIdIsContained(ProtocolCodec codec) throws Exception {
		int port = findAvailablePort();
		try (BridgeServer server = new BridgeServer(
				enabledConfig(port),
				codec,
				Runnable::run,
				command -> { }
		)) {
			server.start();
			try (Socket client = connectAuthenticated(codec, port, "hello-error-id")) {
				JsonObject payload = new JsonObject();
				payload.addProperty("event", "reserve-internal-id");
				String eventId = server.sendEvent("significant_event", payload);
				assertEquals(eventId, readMessage(codec, client).get("messageId").getAsString(), "generated event id delivered");

				writeMessage(
						codec,
						client,
						"{\"protocolVersion\":1,\"agentId\":\"" + AGENT_ID
								+ "\",\"type\":\"cancel_action\",\"messageId\":\"protocol-error-id\"}"
				);
				JsonObject error = readMessage(codec, client);
				assertEquals("error", error.get("type").getAsString(), "generated error reported type");
				assertEquals("MISSING_FIELD", error.get("code").getAsString(), "generated error reported code");
				assertTrue(!eventId.equals(error.get("messageId").getAsString()), "generated error id is unique");
			}
		}
	}

	private static void verifyGeneratedOutboundMessageIds(ProtocolCodec codec) throws Exception {
		int port = findAvailablePort();
		try (BridgeServer server = new BridgeServer(enabledConfig(port), codec, Runnable::run, command -> { })) {
			server.start();
			try (Socket client = connectAuthenticated(codec, port, "hello-generated-ids")) {
				JsonObject payload = new JsonObject();
				payload.addProperty("event", "generated-id");
				String previousId = null;
				for (int index = 0; index < GENERATED_OUTBOUND_EVENT_COUNT; index++) {
					String generatedId = server.sendEvent("significant_event", payload);
					JsonObject delivered = readMessage(codec, client);
					if (generatedId.equals(previousId) || !generatedId.equals(delivered.get("messageId").getAsString())) {
						throw new AssertionError("generated outbound message IDs must be unique and match the wire envelope");
					}
					previousId = generatedId;
				}
				pass("generated outbound IDs remain unique beyond the former session cap");
			}
		}
	}

	private static void verifyHandshakeTimeoutReleasesSession(ProtocolCodec codec) throws Exception {
		int port = findAvailablePort();
		try (BridgeServer server = new BridgeServer(enabledConfig(port), codec, Runnable::run, command -> { })) {
			server.start();
			try (Socket silent = openClient(port)) {
				silent.setSoTimeout(EXPECTED_HANDSHAKE_TIMEOUT_MS + SOCKET_TIMEOUT_MS);
				assertErrorCode(codec, silent, "AUTHENTICATION_TIMEOUT", "silent peer handshake timeout");
			}
			try (Socket reconnected = connectAuthenticatedWithRetry(codec, port, "hello-after-timeout")) {
				assertTrue(reconnected.isConnected(), "handshake timeout releases session slot");
			}
		}
	}

	private static void verifyAuthenticatedRetryClosesFailedSocket(ProtocolCodec codec) throws Exception {
		try (RetryHandshakeServer server = new RetryHandshakeServer(codec)) {
			try (Socket connected = connectAuthenticatedWithRetry(codec, server.port(), "hello-retry")) {
				assertTrue(connected.isConnected(), "authentication retries SESSION_ACTIVE response");
			}
			awaitCondition(server::firstSocketClosedByClient, "failed authentication socket closed before retry");
			server.assertHealthy();
		}
	}

	private static void verifyHelloAcknowledgementPrecedesEvents(ProtocolCodec codec) throws Exception {
		int port = findAvailablePort();
		BridgeConcurrencyVerification.verifyHelloAcknowledgementPrecedesEvents(enabledConfig(port), codec);
		pass("hello acknowledgement precedes concurrent event");
	}

	private static void verifyCloseLinearizesPendingAdmission(ProtocolCodec codec) throws Exception {
		int port = findAvailablePort();
		BridgeConcurrencyVerification.verifyCloseLinearizesPendingAdmission(enabledConfig(port), codec);
		pass("server close linearizes pending session admission");
	}

	private static void verifyConcurrentCloseWaitsForCallback(ProtocolCodec codec) throws Exception {
		int port = findAvailablePort();
		CountDownLatch callbackEntered = new CountDownLatch(1);
		CountDownLatch releaseCallback = new CountDownLatch(1);
		BridgeServer server = new BridgeServer(enabledConfig(port), codec, Runnable::run, command -> {
			callbackEntered.countDown();
			awaitUninterruptibly(releaseCallback);
		});
		AtomicBoolean secondCloseReturned = new AtomicBoolean();
		Thread firstClose = null;
		Thread secondClose = null;
		try (Socket client = connectAuthenticatedAfterStart(server, codec, port, "hello-concurrent-close")) {
			writeMessage(codec, client, actionEnvelope("action-concurrent-close"));
			awaitCondition(() -> callbackEntered.getCount() == 0L, "blocking callback started");

			firstClose = startCloseThread("first", server, null);
			awaitCondition(() -> !server.isRunning(), "first close published shutdown state");
			secondClose = startCloseThread("second", server, secondCloseReturned);
			secondClose.join(CONCURRENT_CLOSE_OBSERVATION_MS);
			assertTrue(!secondCloseReturned.get(), "concurrent close waits for callback teardown");
		} finally {
			releaseCallback.countDown();
			server.close();
			joinCloseThread(firstClose);
			joinCloseThread(secondClose);
		}
	}

	private static void verifyQueueOverflowDoesNotInvertCallbackLock(ProtocolCodec codec) throws Exception {
		int port = findAvailablePort();
		BridgeConcurrencyVerification.verifyQueueOverflowDoesNotInvertCallbackLock(enabledConfig(port), codec);
		pass("queue overflow avoids lifecycle and callback lock inversion");
	}

	private static void verifyCallbackFailureShutdownDoesNotWaitForBlockedOutput(ProtocolCodec codec)
			throws Exception {
		int port = findAvailablePort();
		BridgeConcurrencyVerification.verifyCallbackFailureShutdownDoesNotWaitForBlockedOutput(
				enabledConfig(port),
				codec
		);
		pass("callback failure shutdown does not wait for blocked output");
	}

	private static Socket connectAuthenticatedAfterStart(
			BridgeServer server,
			ProtocolCodec codec,
			int port,
			String messageId
	) throws IOException {
		server.start();
		return connectAuthenticated(codec, port, messageId);
	}

	private static Thread startCloseThread(String role, BridgeServer server, AtomicBoolean returned) {
		return Thread.ofPlatform()
				.name("arenaagents-verification-close-" + role)
				.daemon(true)
				.start(() -> {
					try {
						server.close();
					} finally {
						if (returned != null) {
							returned.set(true);
						}
					}
				});
	}

	private static void joinCloseThread(Thread thread) throws InterruptedException {
		if (thread == null) {
			return;
		}
		thread.join(SOCKET_TIMEOUT_MS);
		if (thread.isAlive()) {
			throw new AssertionError("bridge close thread did not stop");
		}
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		while (latch.getCount() != 0L) {
			try {
				latch.await();
			} catch (InterruptedException exception) {
				interrupted = true;
			}
		}
		if (interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	private static void verifyBridgeSingleSessionAndReconnect(ProtocolCodec codec) throws Exception {
		int port = findAvailablePort();
		try (BridgeServer server = new BridgeServer(enabledConfig(port), codec, Runnable::run, command -> { })) {
			server.start();
			try (Socket first = connectAuthenticated(codec, port, "hello-primary")) {
				try (Socket second = openClient(port)) {
					assertErrorCode(codec, second, "SESSION_ACTIVE", "second live session rejected");
				}
			}
			try (Socket reconnected = connectAuthenticatedWithRetry(codec, port, "hello-reconnected")) {
				assertTrue(reconnected.isConnected(), "session reconnects after clean peer close");
			}
		}
	}

	private static void verifyBridgeCallbackFailure(ProtocolCodec codec) throws Exception {
		int port = findAvailablePort();
		RecordingExecutor executor = new RecordingExecutor();
		try (BridgeServer server = new BridgeServer(
				enabledConfig(port),
				codec,
				executor,
				command -> { throw new IllegalStateException("callback failed"); }
		)) {
			server.start();
			try (Socket client = connectAuthenticated(codec, port, "hello-callback-failure")) {
				writeMessage(codec, client, actionEnvelope("action-callback-failure"));
				awaitCondition(() -> executor.pendingCount() == 1, "failing action callback queued");
				assertDoesNotThrow(executor::runNext, "action callback failure contained");
				assertTrue(
						codec.readLine(client.getInputStream()) == null,
						"action callback failure closes the session"
				);
			}
		}
	}

	private static void verifyBridgeControlValidation(ProtocolCodec codec) throws Exception {
		int port = findAvailablePort();
		try (BridgeServer server = new BridgeServer(enabledConfig(port), codec, Runnable::run, command -> { })) {
			server.start();
			try (Socket client = connectAuthenticated(codec, port, "hello-control")) {
				writeMessage(
						codec,
						client,
						"{\"protocolVersion\":1,\"agentId\":\"" + AGENT_ID
								+ "\",\"type\":\"cancel_action\",\"messageId\":\"cancel-1\"}"
				);
				assertErrorCode(codec, client, "MISSING_FIELD", "cancel command id required");
			}
		}
	}

	private static void verifyBridgeOutboundEventsAndShutdown(ProtocolCodec codec) throws Exception {
		int port = findAvailablePort();
		try (BridgeServer server = new BridgeServer(enabledConfig(port), codec, Runnable::run, command -> { })) {
			server.start();
			try (Socket client = connectAuthenticated(codec, port, "hello-outbound")) {
				JsonObject payload = new JsonObject();
				payload.addProperty("event", "bridge_ready");
				String generatedId = server.sendEvent("significant_event", payload);
				JsonObject event = readMessage(codec, client);
				assertEquals("significant_event", event.get("type").getAsString(), "thread-safe outbound event type");
				assertEquals(AGENT_ID, event.get("agentId").getAsString(), "outbound event agent identity");
				assertEquals(generatedId, event.get("messageId").getAsString(), "outbound event generated identity");
				JsonObject envelopeCollision = payload.deepCopy();
				envelopeCollision.addProperty("messageId", "caller-controlled");
				expectProtocolException(
						() -> server.sendEvent("significant_event", envelopeCollision),
						ProtocolConstants.ERROR_UNKNOWN_FIELD,
						"messageId",
						"outbound payload cannot replace generated id"
				);
				expectProtocolException(
						() -> server.sendEvent("unknown_event", payload),
						"UNKNOWN_MESSAGE_TYPE",
						"unknown_event",
						"outbound message type allowlist"
				);
			}
		}
		awaitCondition(() -> bridgeThreads().stream().noneMatch(Thread::isAlive), "bridge daemon threads stop after close");
		assertTrue(bridgeThreads().stream().noneMatch(thread -> !thread.isDaemon()), "no non-daemon bridge thread remains");
	}

	private static void assertDecodedType(
			ProtocolCodec codec,
			String wireType,
			String actionFields,
			ActionType expectedType
	) throws ProtocolException {
		ActionCommand command = codec.decodeCommand(commandJson(wireType, actionFields));
		assertEquals(expectedType, command.type(), wireType + " action type");
		assertEquals(COMMAND_ID, command.commandId(), wireType + " command id");
		assertEquals(ISSUED_AT_EPOCH_MS, command.issuedAtEpochMs(), wireType + " issue timestamp");
	}

	private static String commandJson(String wireType, String actionFields) {
		String actionSuffix = actionFields.isEmpty() ? "" : "," + actionFields;
		return "{\"protocolVersion\":1,\"commandId\":\"" + COMMAND_ID
				+ "\",\"type\":\"" + wireType
				+ "\",\"issuedAtEpochMs\":" + ISSUED_AT_EPOCH_MS
				+ actionSuffix + "}";
	}

	private static AgentConfig enabledConfig(int port) {
		return new AgentConfig(AGENT_ID, port, 12, true);
	}

	private static int findAvailablePort() throws IOException {
		try (ServerSocket socket = new ServerSocket()) {
			socket.bind(new InetSocketAddress("127.0.0.1", 0));
			return socket.getLocalPort();
		}
	}

	private static Socket openClient(int port) throws IOException {
		Socket socket = new Socket();
		socket.connect(new InetSocketAddress("127.0.0.1", port), SOCKET_TIMEOUT_MS);
		socket.setSoTimeout(SOCKET_TIMEOUT_MS);
		return socket;
	}

	private static Socket connectAuthenticated(ProtocolCodec codec, int port, String messageId) throws IOException {
		Socket socket = openClient(port);
		try {
			writeMessage(codec, socket, helloEnvelope(messageId));
			String line = codec.readLine(socket.getInputStream());
			if (line == null) {
				throw new IOException("Bridge closed before acknowledging hello");
			}
			JsonObject response = JsonParser.parseString(line).getAsJsonObject();
			String type = response.get("type").getAsString();
			if ("error".equals(type)) {
				String code = response.get("code").getAsString();
				String message = response.get("message").getAsString();
				throw new ProtocolException(code, message);
			}
			if (!"hello_ack".equals(type)) {
				throw new ProtocolException(
						"UNEXPECTED_HANDSHAKE_RESPONSE",
						"Expected hello_ack but received '" + type + "'"
				);
			}
			pass("hello acknowledged");
			return socket;
		} catch (IOException | RuntimeException | Error exception) {
			try {
				socket.close();
			} catch (IOException closeException) {
				exception.addSuppressed(closeException);
			}
			throw exception;
		}
	}

	private static Socket connectAuthenticatedWithRetry(ProtocolCodec codec, int port, String messageId)
			throws Exception {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ASYNC_TIMEOUT_MS);
		Throwable lastFailure = null;
		while (System.nanoTime() < deadline) {
			try {
				return connectAuthenticated(codec, port, messageId);
			} catch (IOException exception) {
				lastFailure = exception;
			} catch (ProtocolException exception) {
				if (!"SESSION_ACTIVE".equals(exception.code())) {
					throw exception;
				}
				lastFailure = exception;
			}
			Thread.sleep(AUTHENTICATION_RETRY_DELAY_MS);
		}
		throw new AssertionError("session did not reconnect", lastFailure);
	}

	private static void writeMessage(ProtocolCodec codec, Socket socket, String json) throws IOException {
		codec.writeLine(socket.getOutputStream(), json);
	}

	private static JsonObject readMessage(ProtocolCodec codec, Socket socket) throws IOException {
		String line = codec.readLine(socket.getInputStream());
		if (line == null) {
			throw new AssertionError("bridge closed before sending a message");
		}
		return JsonParser.parseString(line).getAsJsonObject();
	}

	private static void assertErrorCode(ProtocolCodec codec, Socket socket, String expectedCode, String label)
			throws IOException {
		JsonObject error = readMessage(codec, socket);
		assertEquals("error", error.get("type").getAsString(), label + " type");
		assertEquals(expectedCode, error.get("code").getAsString(), label + " code");
	}

	private static String helloEnvelope(String messageId) {
		return "{\"protocolVersion\":1,\"agentId\":\"" + AGENT_ID
				+ "\",\"type\":\"hello\",\"messageId\":\"" + messageId + "\"}";
	}

	private static String actionEnvelope(String messageId) {
		return "{\"protocolVersion\":1,\"agentId\":\"" + AGENT_ID
				+ "\",\"type\":\"action_command\",\"messageId\":\"" + messageId
				+ "\",\"command\":" + commandJson("wait", "\"durationMs\":250") + "}";
	}

	private static String requestObservationEnvelope(String messageId) {
		return "{\"protocolVersion\":1,\"agentId\":\"" + AGENT_ID
				+ "\",\"type\":\"request_observation\",\"messageId\":\"" + messageId + "\"}";
	}

	private static void awaitCondition(BooleanSupplier condition, String label) throws InterruptedException {
		long deadline = System.currentTimeMillis() + ASYNC_TIMEOUT_MS;
		while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
			Thread.yield();
		}
		assertTrue(condition.getAsBoolean(), label);
	}

	private static List<Thread> bridgeThreads() {
		return Thread.getAllStackTraces().keySet().stream()
				.filter(thread -> thread.getName().startsWith(BridgeServer.THREAD_NAME_PREFIX))
				.toList();
	}

	private static void expectProtocolException(
			ThrowingRunnable action,
			String expectedCode,
			String expectedMessagePart,
			String label
	) {
		try {
			action.run();
		} catch (ProtocolException exception) {
			assertEquals(expectedCode, exception.code(), label + " code");
			assertTrue(exception.getMessage().contains(expectedMessagePart), label + " message");
			return;
		} catch (Exception exception) {
			throw new AssertionError(label + " threw " + exception.getClass().getSimpleName(), exception);
		}

		throw new AssertionError(label + " did not throw ProtocolException");
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
		pass(label);
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) {
			throw new AssertionError(label);
		}
		pass(label);
	}

	private static void assertDoesNotThrow(ThrowingRunnable action, String label) {
		try {
			action.run();
		} catch (Exception exception) {
			throw new AssertionError(label + " threw " + exception.getClass().getSimpleName(), exception);
		}
		pass(label);
	}

	private static void pass(String label) {
		passedAssertions++;
		System.out.println("PASS: " + label);
	}

	private static final class RecordingExecutor implements Executor {
		private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

		@Override
		public synchronized void execute(Runnable command) {
			tasks.addLast(command);
		}

		private synchronized int pendingCount() {
			return tasks.size();
		}

		private void runNext() {
			Runnable task;
			synchronized (this) {
				task = tasks.removeFirst();
			}
			task.run();
		}
	}

	private static final class RetryHandshakeServer implements AutoCloseable {
		private final ProtocolCodec codec;
		private final ServerSocket serverSocket;
		private final List<Socket> acceptedSockets = new ArrayList<>();
		private final AtomicBoolean firstSocketClosedByClient = new AtomicBoolean();
		private final AtomicReference<Throwable> failure = new AtomicReference<>();
		private final Thread thread;

		private RetryHandshakeServer(ProtocolCodec codec) throws IOException {
			this.codec = codec;
			serverSocket = new ServerSocket();
			serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
			thread = Thread.ofPlatform()
					.name("arenaagents-retry-handshake-server")
					.daemon(true)
					.start(this::serve);
		}

		private int port() {
			return serverSocket.getLocalPort();
		}

		private boolean firstSocketClosedByClient() {
			return firstSocketClosedByClient.get();
		}

		private void assertHealthy() {
			Throwable problem = failure.get();
			if (problem != null) {
				throw new AssertionError("retry handshake fixture failed", problem);
			}
			pass("retry handshake fixture healthy");
		}

		private void serve() {
			try {
				Socket first = accept();
				codec.readLine(first.getInputStream());
				codec.writeLine(
						first.getOutputStream(),
						"{\"protocolVersion\":1,\"agentId\":\"" + AGENT_ID
								+ "\",\"type\":\"error\",\"messageId\":\"retry-error-1\","
								+ "\"code\":\"SESSION_ACTIVE\",\"message\":\"retry\"}"
				);
				first.setSoTimeout(SOCKET_TIMEOUT_MS);
				firstSocketClosedByClient.set(codec.readLine(first.getInputStream()) == null);
				first.close();

				Socket second = accept();
				codec.readLine(second.getInputStream());
				codec.writeLine(
						second.getOutputStream(),
						"{\"protocolVersion\":1,\"agentId\":\"" + AGENT_ID
								+ "\",\"type\":\"hello_ack\",\"messageId\":\"retry-ack-1\","
								+ "\"replyTo\":\"hello-retry\"}"
				);
			} catch (IOException | RuntimeException exception) {
				if (!serverSocket.isClosed()) {
					failure.compareAndSet(null, exception);
				}
			}
		}

		private Socket accept() throws IOException {
			Socket socket = serverSocket.accept();
			synchronized (acceptedSockets) {
				acceptedSockets.add(socket);
			}
			return socket;
		}

		@Override
		public void close() throws IOException {
			serverSocket.close();
			synchronized (acceptedSockets) {
				for (Socket socket : acceptedSockets) {
					socket.close();
				}
			}
			try {
				thread.join(SOCKET_TIMEOUT_MS);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
		}
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
