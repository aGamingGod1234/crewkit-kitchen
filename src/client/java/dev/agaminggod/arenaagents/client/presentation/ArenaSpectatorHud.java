package dev.agaminggod.arenaagents.client.presentation;

import dev.agaminggod.arenaagents.scenario.presentation.ArenaSpectatorSnapshot;
import dev.agaminggod.arenaagents.scenario.result.ScenarioPublicEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

public final class ArenaSpectatorHud {
	private static final Identifier ELEMENT_ID = Identifier.fromNamespaceAndPath("arenaagents", "spectator_hud");
	private static final int PANEL = 0xD9191D24;
	private static final int TEXT = 0xFFF2F3F5;
	private static final int MUTED = 0xFFABB1BC;
	private static final int FEED = 0xFFD2D5DB;
	private static ArenaSpectatorState state;
	private static boolean registered;

	private ArenaSpectatorHud() {
	}

	public static synchronized void register(ArenaSpectatorState spectatorState) {
		Objects.requireNonNull(spectatorState, "spectatorState must not be null");
		if (registered) return;
		state = spectatorState;
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, ELEMENT_ID, ArenaSpectatorHud::render);
		registered = true;
	}

	public static int providerColor(String providerFamily) {
		Objects.requireNonNull(providerFamily, "providerFamily must not be null");
		return switch (providerFamily.toLowerCase(Locale.ROOT)) {
			case "codex" -> 0xFF42D39B;
			case "gemini", "antigravity" -> 0xFF8E86FF;
			case "kimi" -> 0xFFFFB45E;
			default -> TEXT;
		};
	}

	public static String standingLabel(ArenaSpectatorSnapshot.Standing standing) {
		Objects.requireNonNull(standing, "standing must not be null");
		return "#" + standing.rank() + " " + standing.displayName()
				+ " [" + standing.providerFamily() + "] " + scoreText(standing.score())
				+ " | HP " + standing.healthPercent() + "% | " + standing.status();
	}

	private static void render(GuiGraphicsExtractor graphics, DeltaTracker ignored) {
		Minecraft client = Minecraft.getInstance();
		if (state == null || client.player == null || client.level == null || client.screen != null) return;
		ArenaSpectatorSnapshot snapshot = state.snapshot().orElse(null);
		if (snapshot == null) return;
		List<ScenarioPublicEvent> feed = state.visibleFeed(snapshot.elapsedTick());
		int lineCount = 2 + snapshot.standings().size() + (feed.isEmpty() ? 0 : 1 + feed.size());
		int width = Math.min(300, Math.max(220, graphics.guiWidth() - 16));
		int left = 8;
		int top = 8;
		int bottom = top + 8 + (lineCount * 10);
		graphics.fill(left, top, left + width, bottom, PANEL);
		int x = left + 6;
		int y = top + 5;
		graphics.text(client.font, snapshot.scenarioTitle() + " | " + snapshot.phaseTitle(), x, y, TEXT, false);
		y += 10;
		graphics.text(client.font, timeLabel(snapshot), x, y, MUTED, false);
		for (ArenaSpectatorSnapshot.Standing standing : snapshot.standings()) {
			y += 10;
			graphics.text(client.font, standingLabel(standing), x, y, providerColor(standing.providerFamily()), false);
		}
		if (!feed.isEmpty()) {
			y += 10;
			graphics.text(client.font, "LIVE FEED", x, y, MUTED, false);
			for (ScenarioPublicEvent event : feed) {
				y += 10;
				graphics.text(client.font, "[" + event.elapsedTick() + "] " + event.message(), x, y, FEED, false);
			}
		}
	}

	private static String timeLabel(ArenaSpectatorSnapshot snapshot) {
		return "Tick " + snapshot.elapsedTick() + " / " + snapshot.durationTicks()
				+ (snapshot.terminal() ? " | FINAL" : "");
	}

	static String scoreText(double score) {
		return (score == 0.0D ? BigDecimal.ZERO : BigDecimal.valueOf(score).stripTrailingZeros()).toPlainString();
	}
}
