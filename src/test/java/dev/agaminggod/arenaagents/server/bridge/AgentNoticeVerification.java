package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.server.AgentChatReporter;
import dev.agaminggod.arenaagents.server.AgentSavedData;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

/**
 * Verifies the launch-time provider CLI notice path: the strict agent_notice wire contract,
 * the chat presentation it selects, that the bridge delivers the message to the reporter for
 * idle and unknown agents without dropping the session, and that late joiners get a replay.
 */
public final class AgentNoticeVerification {
	private static final String MESSAGE =
			"Claude Code CLI is not installed on the server machine (no 'claude' found on PATH). "
					+ "Install it, sign in with 'claude auth login', then restart Minecraft and relaunch this agent.";

	private AgentNoticeVerification() {
	}

	public static void main(String[] args) {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		int assertions = verify();
		System.out.println("Agent notice verification passed: " + assertions + " assertions");
	}

	public static int verify() {
		verifyStrictSchema();
		verifyChatPresentation();
		verifyNoticeIsAcceptedForIdleAndUnknownAgents();
		return 27;
	}

	private static void verifyStrictSchema() {
		MultiplexedServerBridge.AgentNotice decoded =
				MultiplexedServerBridge.decodeAgentNotice(noticePayload("error", "PROVIDER_CLI_MISSING", MESSAGE));
		assertEquals("error", decoded.severity(), "notice retains its severity");
		assertEquals("PROVIDER_CLI_MISSING", decoded.code(), "notice retains its reason code");
		assertEquals(MESSAGE, decoded.message(), "notice retains the player-facing message beyond 256 characters");
		assertEquals("warning", MultiplexedServerBridge.decodeAgentNotice(noticePayload("warning", "X", "m")).severity(),
				"warning notices are accepted");
		assertEquals("info", MultiplexedServerBridge.decodeAgentNotice(noticePayload("info", "X", "m")).severity(),
				"info notices are accepted");

		JsonObject extra = noticePayload("error", "PROVIDER_CLI_MISSING", MESSAGE);
		extra.addProperty("goalRevision", 3L);
		assertThrows(() -> MultiplexedServerBridge.decodeAgentNotice(extra), "notices reject unknown fields");
		assertThrows(() -> MultiplexedServerBridge.decodeAgentNotice(noticePayload("fatal", "X", "m")),
				"notices reject unknown severities");
		assertThrows(() -> MultiplexedServerBridge.decodeAgentNotice(noticePayload("error", "X", "x".repeat(2_049))),
				"notices reject messages beyond the wire bound");
		assertThrows(() -> MultiplexedServerBridge.decodeAgentNotice(noticePayload("error", "X", "line\nbreak")),
				"notices reject control characters");
		JsonObject missingCode = new JsonObject();
		missingCode.addProperty("severity", "error");
		missingCode.addProperty("message", "m");
		assertThrows(() -> MultiplexedServerBridge.decodeAgentNotice(missingCode), "notices require a reason code");
	}

	private static void verifyChatPresentation() {
		assertEquals(ChatFormatting.RED, AgentChatReporter.noticeColor("error"), "errors are shown in red");
		assertEquals(ChatFormatting.YELLOW, AgentChatReporter.noticeColor("warning"), "warnings are shown in yellow");
		assertEquals(ChatFormatting.GRAY, AgentChatReporter.noticeColor("info"), "information is shown in gray");
		Component line = AgentChatReporter.noticeLine("Claude Sonnet 5.5", "claude", "error", MESSAGE);
		assertEquals("[Claude Sonnet 5.5] " + MESSAGE, line.getString(), "chat line carries the agent prefix and message");
		assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GOLD), line.getStyle().getColor(),
				"the prefix uses the provider colour");
		assertEquals(TextColor.fromLegacyFormat(ChatFormatting.RED), line.getSiblings().getFirst().getStyle().getColor(),
				"the body uses the severity colour");
	}

	private static void verifyNoticeIsAcceptedForIdleAndUnknownAgents() {
		MultiplexedServerBridge bridge = null;
		Path secretFile = null;
		try {
			String secret = "0123456789abcdef0123456789abcdef";
			secretFile = Files.createTempFile("arena-agents-agent-notice-", ".txt");
			Files.writeString(secretFile, secret);
			CodexAgentManager manager = uninitializedManager();
			var record = manager.registry().create("gpt-5.6-luna", "high", Optional.of("NoticeAgent"), 1_000L);
			bridge = new MultiplexedServerBridge(manager, 0, secretFile);
			List<MultiplexedServerBridge.AcceptedAgentNotice> accepted = new ArrayList<>();
			bridge.setAgentNoticeHookForVerification(accepted::add);
			bridge.start();
			BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
			try (Socket socket = new Socket(MultiplexedServerBridge.LOOPBACK_HOST, bridge.boundPortForVerification());
				 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				socket.setSoTimeout(1_000);
				sendHello(socket, reader, codec, secret, "hello-agent-notice");
				BridgeEnvelope helloAck = codec.decode(reader.readLine());
				assertEquals("hello_ack", helloAck.type(), "notice fixture authenticates");
				assertEquals("verbose_control", codec.decode(reader.readLine()).type(), "notice fixture receives control");

				// The agent is idle (no goal revision), which is exactly when agent_error would be dropped.
				send(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), record.agentId().toString(), "agent_notice", "agent-notice-1",
						noticePayload("error", "PROVIDER_CLI_MISSING", MESSAGE)
				));
				send(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), UUID.randomUUID().toString(), "agent_notice", "agent-notice-2",
						noticePayload("warning", "PROVIDER_CLI_UNAUTHENTICATED", "unknown agent is logged only")
				));
				send(socket, codec, new BridgeEnvelope(
						2, helloAck.serverInstanceId(), "server", "heartbeat", "agent-notice-heartbeat", new JsonObject()
				));
				BridgeEnvelope response = MultiplexedServerBridgeVerification.pollBridgeResponse(bridge, socket, reader, codec);
				assertEquals("heartbeat", response.type(), "agent notices preserve the authenticated session");
				// Server tasks swallow RuntimeExceptions, so the heartbeat alone cannot prove delivery.
				assertEquals(2, accepted.size(), "both notices reached the reporter path before the heartbeat");
				assertEquals(record.agentId(), accepted.get(0).agentId(), "the idle agent notice is attributed to its agent");
				assertEquals(true, accepted.get(0).known(), "the idle agent is resolved from the registry");
				assertEquals("PROVIDER_CLI_MISSING", accepted.get(0).notice().code(), "the idle agent notice keeps its code");
				assertEquals(MESSAGE, accepted.get(0).notice().message(), "the idle agent notice keeps its message");
				assertEquals(false, accepted.get(1).known(), "an unknown agent is accepted and logged only");
				List<Component> replay = bridge.pendingAgentNoticeLines();
				assertEquals(1, replay.size(), "only notices for registered agents are replayed to late joiners");
				assertEquals("[" + manager.displayName(record) + "] " + MESSAGE, replay.getFirst().getString(),
						"the replayed line matches the broadcast line");
			}
		} catch (AssertionError error) {
			throw error;
		} catch (Exception exception) {
			throw new AssertionError("agent notice verification failed", exception);
		} finally {
			if (bridge != null) bridge.close();
			if (secretFile != null) {
				try {
					Files.deleteIfExists(secretFile);
				} catch (java.io.IOException exception) {
					throw new AssertionError("could not remove temporary notice secret", exception);
				}
			}
		}
	}

	private static void send(Socket socket, BridgeEnvelopeCodec codec, BridgeEnvelope envelope) throws Exception {
		socket.getOutputStream().write(codec.encode(envelope).getBytes(StandardCharsets.UTF_8));
		socket.getOutputStream().flush();
	}

	private static JsonObject noticePayload(String severity, String code, String message) {
		JsonObject payload = new JsonObject();
		payload.addProperty("severity", severity);
		payload.addProperty("code", code);
		payload.addProperty("message", message);
		return payload;
	}

	private static CodexAgentManager uninitializedManager() {
		try {
			Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
			CodexAgentManager manager = (CodexAgentManager) unsafe.allocateInstance(CodexAgentManager.class);
			Field savedData = CodexAgentManager.class.getDeclaredField("savedData");
			unsafe.putObject(manager, unsafe.objectFieldOffset(savedData), new AgentSavedData());
			Field pendingRegistrations = CodexAgentManager.class.getDeclaredField("pendingAgentRegistrations");
			unsafe.putObject(manager, unsafe.objectFieldOffset(pendingRegistrations), ConcurrentHashMap.newKeySet());
			return manager;
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not allocate notice-only manager", exception);
		}
	}

	private static void sendHello(Socket socket, BufferedReader reader, BridgeEnvelopeCodec codec, String secret, String messageId)
			throws Exception {
		String clientNonce = Base64.getUrlEncoder().withoutPadding().encodeToString(
				MessageDigest.getInstance("SHA-256").digest(messageId.getBytes(StandardCharsets.UTF_8))
		);
		JsonObject challenge = new JsonObject();
		challenge.addProperty("clientNonce", clientNonce);
		send(socket, codec, new BridgeEnvelope(2, "pending", "server", "auth_challenge", messageId + "-challenge", challenge));
		BridgeEnvelope response = codec.decode(reader.readLine());
		String serverNonce = response.payload().get("serverNonce").getAsString();
		JsonObject hello = new JsonObject();
		hello.addProperty("replyTo", response.messageId());
		hello.addProperty("clientNonce", clientNonce);
		hello.addProperty("serverNonce", serverNonce);
		hello.addProperty("proof", MultiplexedServerBridge.authenticationProof(
				secret, "coordinator", clientNonce, serverNonce, response.serverInstanceId(), null
		));
		send(socket, codec, new BridgeEnvelope(2, response.serverInstanceId(), "server", "hello", messageId, hello));
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
	}

	private static void assertThrows(Runnable action, String label) {
		try {
			action.run();
		} catch (BridgeProtocolException exception) {
			return;
		}
		throw new AssertionError(label);
	}
}
