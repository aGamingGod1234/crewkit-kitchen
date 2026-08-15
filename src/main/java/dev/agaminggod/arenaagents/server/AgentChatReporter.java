package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.server.runtime.ServerActionRequest;
import dev.agaminggod.arenaagents.server.runtime.ServerActionResult;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class AgentChatReporter {
	private AgentChatReporter() {
	}

	public static void planning(CodexAgentManager manager, AgentRecord record) {
		// Planning state belongs in the field console; chat is reserved for meaningful activity.
	}

	public static void stillPlanning(CodexAgentManager manager, AgentRecord record) {
		// Deliberately quiet: periodic provider heartbeat text drowned out actual actions.
	}

	public static void acting(CodexAgentManager manager, AgentRecord record, ServerActionRequest request) {
		if (AgentActivityPresentation.shouldAnnounceAction(request.type())) {
			report(manager, record, AgentActivityPresentation.action(request.type()), ChatFormatting.WHITE);
		}
	}

	public static void decision(CodexAgentManager manager, AgentRecord record, String summary) {
		// The concrete action that follows is clearer than repeating the planner's internal summary.
	}

	public static void result(CodexAgentManager manager, AgentRecord record, ServerActionResult result) {
		AgentActivityPresentation.result(result).ifPresent(message ->
				report(manager, record, message, ChatFormatting.RED));
	}

	public static void completed(CodexAgentManager manager, AgentRecord record, String summary) {
		report(manager, record, summary == null || summary.isBlank() ? "Task complete" : summary, ChatFormatting.GREEN);
	}

	public static void failed(CodexAgentManager manager, AgentRecord record, String error) {
		report(manager, record, "Needs attention: " + readableError(error), ChatFormatting.RED);
	}

	public static void disconnected(CodexAgentManager manager, AgentRecord record) {
		report(manager, record,
				"Connection lost. Reconnect the coordinator, then resume or restart this task.", ChatFormatting.RED);
	}

	private static void report(
			CodexAgentManager manager,
			AgentRecord record,
			String message,
			ChatFormatting messageColor
	) {
		if (!manager.automaticProgress(record.agentId())) return;
		MutableComponent prefix = Component.literal("[" + manager.displayName(record) + "]")
				.withStyle(familyColor(record.profile().provider()));
		MutableComponent body = Component.literal(" " + (message == null || message.isBlank() ? "No details." : message))
				.withStyle(messageColor);
		manager.server().getPlayerList().broadcastSystemMessage(prefix.append(body), false);
	}

	private static ChatFormatting familyColor(String provider) {
		return switch (provider.toLowerCase(Locale.ROOT)) {
			case "codex" -> ChatFormatting.AQUA;
			case "gemini", "antigravity" -> ChatFormatting.LIGHT_PURPLE;
			case "kimi" -> ChatFormatting.GOLD;
			default -> ChatFormatting.WHITE;
		};
	}

	private static String readableError(String error) {
		if (error == null || error.isBlank()) return "the model provider returned no error details.";
		String compact = error.replace('\n', ' ').replace('\r', ' ').trim();
		return compact.length() <= 240 ? compact : compact.substring(0, 237) + "...";
	}
}
