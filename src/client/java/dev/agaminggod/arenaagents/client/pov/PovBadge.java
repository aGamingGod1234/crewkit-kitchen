package dev.agaminggod.arenaagents.client.pov;

import dev.agaminggod.arenaagents.client.gui.ConsoleTheme;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;

/** Session-scoped badge: whose view this is, the mode, the operator's own body and how to leave. */
public final class PovBadge {
	private static final Identifier ID = Identifier.fromNamespaceAndPath("arenaagents", "pov_badge");
	private static final int MARGIN = 4;
	private static final int PADDING = 4;
	private static final int LINE_HEIGHT = 10;
	private static final int FLASH_BACKGROUND = (ConsoleTheme.ERROR & 0x00FFFFFF) | 0x70000000;

	private PovBadge() {
	}

	static void register() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, ID, PovBadge::extract);
	}

	private static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		PovClientSession session = PovClient.session().orElse(null);
		LocalPlayer body = client.player;
		if (session == null || body == null || client.options.hideGui || client.gui.getDebugOverlay().showDebugScreen()) return;
		Font font = client.font;
		String name = session.agentName().isBlank() ? "Agent" : session.agentName();
		String mode = session.takeover() ? "Controlling" : "Spectating";
		List<Line> lines = new ArrayList<>();
		// The mode label carries the accent so it reads at a glance.
		lines.add(new Line(name + "  ", ConsoleTheme.TEXT, false, mode));
		if (PovClient.signalLost()) lines.add(new Line("Signal lost", ConsoleTheme.ERROR, false, ""));
		boolean highlighted = PovClient.bodyHighlighted();
		lines.add(new Line(bodyText(body, session.takeover()), highlighted ? ConsoleTheme.ERROR : ConsoleTheme.TEXT, highlighted, ""));
		lines.add(new Line(PovClient.exitHint(), ConsoleTheme.MUTED, false, ""));
		int width = 0;
		for (Line line : lines) width = Math.max(width, font.width(line.text() + line.accent()));
		int left = MARGIN;
		int top = MARGIN;
		int right = left + width + PADDING * 2;
		graphics.fill(left, top, right, top + lines.size() * LINE_HEIGHT + PADDING * 2 - 1, ConsoleTheme.BACKDROP);
		int y = top + PADDING;
		for (Line line : lines) {
			if (line.flash()) graphics.fill(left, y - 1, right, y + LINE_HEIGHT - 1, FLASH_BACKGROUND);
			graphics.text(font, line.text(), left + PADDING, y, line.color(), false);
			if (!line.accent().isEmpty()) graphics.text(font, line.accent(), left + PADDING + font.width(line.text()), y, ConsoleTheme.ACCENT, false);
			y += LINE_HEIGHT;
		}
	}

	private static String bodyText(LocalPlayer body, boolean takeover) {
		if (body.getAbilities().invulnerable) return takeover ? "Body protected, damage exit off" : "Body protected";
		String health = String.format(Locale.ROOT, "Body %.1f/%.0f", body.getHealth(), body.getMaxHealth());
		float absorption = body.getAbsorptionAmount();
		if (absorption > 0.0F) health += String.format(Locale.ROOT, " (+%.1f)", absorption);
		return takeover ? health + ", exit at 2 hearts lost" : health;
	}

	private record Line(String text, int color, boolean flash, String accent) {
	}
}
