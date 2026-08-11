package dev.agaminggod.arenaagents.client.presentation;

import dev.agaminggod.arenaagents.scenario.presentation.ArenaSpectatorSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ScenarioResultsScreen extends Screen {
	private static final int PANEL = 0xF21A1D23;
	private static final int EDGE = 0xFF3A3F4B;
	private static final int TEXT = 0xFFF2F3F5;
	private static final int MUTED = 0xFFABB1BC;
	private final ArenaSpectatorSnapshot snapshot;
	private final Runnable dismiss;
	private boolean dismissed;

	public ScenarioResultsScreen(ArenaSpectatorSnapshot snapshot, Runnable dismiss) {
		super(Component.literal("Arena Agents Results"));
		this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
		if (!snapshot.terminal()) throw new IllegalArgumentException("results require a terminal snapshot");
		this.dismiss = Objects.requireNonNull(dismiss, "dismiss must not be null");
	}

	public static List<String> resultLines(ArenaSpectatorSnapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot must not be null");
		if (!snapshot.terminal()) throw new IllegalArgumentException("results require a terminal snapshot");
		ArrayList<String> lines = new ArrayList<>();
		lines.add(snapshot.scenarioTitle() + " | " + snapshot.phaseTitle());
		lines.add("Elapsed: " + snapshot.elapsedTick() + " / " + snapshot.durationTicks() + " ticks");
		for (ArenaSpectatorSnapshot.Standing standing : snapshot.standings()) {
			lines.add(ArenaSpectatorHud.standingLabel(standing));
		}
		lines.add("Run: " + snapshot.runId());
		lines.add("Map: " + snapshot.scenarioId() + " @ " + snapshot.mapVersion());
		lines.add("Seeds: world=" + snapshot.worldSeed() + " event=" + snapshot.eventSeed());
		lines.add("Result SHA-256: " + snapshot.resultHash());
		return List.copyOf(lines);
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
				.bounds((width - 120) / 2, height - 34, 120, 20)
				.build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		List<String> lines = resultLines(snapshot);
		int panelWidth = Math.min(620, Math.max(320, width - 24));
		int left = (width - panelWidth) / 2;
		int top = Math.max(10, (height - Math.min(height - 20, 60 + lines.size() * 11)) / 2);
		int bottom = height - 44;
		graphics.fill(left - 1, top - 1, left + panelWidth + 1, bottom + 1, EDGE);
		graphics.fill(left, top, left + panelWidth, bottom, PANEL);
		graphics.centeredText(font, title, width / 2, top + 8, TEXT);
		int y = top + 25;
		for (int index = 0; index < lines.size(); index++) {
			String line = lines.get(index);
			int color = index >= 2 && index < 2 + snapshot.standings().size()
					? ArenaSpectatorHud.providerColor(snapshot.standings().get(index - 2).providerFamily())
					: (index < 2 ? TEXT : MUTED);
			graphics.text(font, line, left + 12, y, color, false);
			y += 11;
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public void onClose() {
		if (!dismissed) {
			dismissed = true;
			dismiss.run();
		}
		super.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
