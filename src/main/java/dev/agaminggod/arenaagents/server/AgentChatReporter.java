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
		report(manager, record, "Planning", "Reviewing the goal and current world state.", ChatFormatting.GRAY);
	}

	public static void acting(CodexAgentManager manager, AgentRecord record, ServerActionRequest request) {
		String action = request.type().wireName().replace('_', ' ');
		report(manager, record, "Acting", action, ChatFormatting.WHITE);
	}

	public static void decision(CodexAgentManager manager, AgentRecord record, String summary) {
		report(manager, record, "Decision", summary, ChatFormatting.YELLOW);
	}

	public static void result(CodexAgentManager manager, AgentRecord record, ServerActionResult result) {
		String status = result.state().name().toLowerCase(Locale.ROOT);
		ChatFormatting color = result.state().name().equals("SUCCEEDED") ? ChatFormatting.GREEN : ChatFormatting.RED;
		report(manager, record, capitalize(status), result.message(), color);
	}

	public static void completed(CodexAgentManager manager, AgentRecord record, String summary) {
		report(manager, record, "Completed", summary, ChatFormatting.GREEN);
	}

	private static void report(
			CodexAgentManager manager,
			AgentRecord record,
			String status,
			String message,
			ChatFormatting statusColor
	) {
		if (!manager.automaticProgress(record.agentId())) return;
		MutableComponent prefix = Component.literal("[" + manager.displayName(record) + "]")
				.withStyle(familyColor(record.profile().provider()));
		MutableComponent body = Component.literal(" " + status + " · ").withStyle(statusColor)
				.append(Component.literal(message == null || message.isBlank() ? "No details." : message)
						.withStyle(ChatFormatting.GRAY));
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

	private static String capitalize(String value) {
		return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
	}
}
