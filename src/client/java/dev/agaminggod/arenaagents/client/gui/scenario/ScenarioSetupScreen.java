package dev.agaminggod.arenaagents.client.gui.scenario;

import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.client.control.AgentControlClient;
import dev.agaminggod.arenaagents.client.gui.AgentControlScreen;
import dev.agaminggod.arenaagents.control.AgentControlCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class ScenarioSetupScreen extends Screen {
	private static final int PANEL_COLOR = 0xF21A1D23;
	private static final int PANEL_EDGE = 0xFF3A3F4B;
	private static final int SURFACE_COLOR = 0xF5272B34;
	private static final int SURFACE_SELECTED = 0xFF343B48;
	private static final int GOLD = 0xFFF2BD58;
	private static final int TEXT = 0xFFF2F3F5;
	private static final int MUTED = 0xFFAAB0BA;
	private static final int ERROR = 0xFFFF6B70;
	private static final int SUCCESS = 0xFF66D9A3;
	private static final int ROW_HEIGHT = 20;
	private static final int GAP = 5;
	private static final int MAX_VISIBLE_ROSTER_ROWS = 7;

	private final ScenarioSetupState state;
	private int rosterScroll;
	private String feedback = "";
	private boolean feedbackError;
	private EditBox nameInput;

	public ScenarioSetupScreen() {
		this(ScenarioSetupState.defaults());
	}

	ScenarioSetupScreen(ScenarioSetupState state) {
		super(Component.literal("Arena Agents · Showcase Setup"));
		this.state = state;
	}

	@Override
	protected void init() {
		switch (state.step()) {
			case MODE -> initMode();
			case ARENA -> initArena();
			case ROSTER -> initRoster();
			case REVIEW -> initReview();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int left = panelLeft();
		int top = 10;
		int panelWidth = panelWidth();
		int bottom = height - 10;
		graphics.fill(left - 1, top - 1, left + panelWidth + 1, bottom + 1, PANEL_EDGE);
		graphics.fill(left, top, left + panelWidth, bottom, PANEL_COLOR);
		graphics.centeredText(font, title.getString(), width / 2, 16, TEXT);
		renderStepRail(graphics, left + 14, 31, panelWidth - 28);
		renderStepContent(graphics, left + 16, 62, panelWidth - 32);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		if (!feedback.isBlank()) {
			graphics.centeredText(font, feedback, width / 2, height - 43, feedbackError ? ERROR : SUCCESS);
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (getFocused() instanceof EditBox) {
			return super.keyPressed(event);
		}
		if (state.step() == ScenarioWizardStep.ARENA) {
			ScenarioPreset preset = ScenarioPreset.fromLetter(event.key());
			if (preset != null) {
				state.selectScenario(preset);
				feedback = preset.title() + " selected";
				feedbackError = false;
				rebuildWidgets();
				return true;
			}
		}
		if (event.key() == GLFW.GLFW_KEY_LEFT && state.step() != ScenarioWizardStep.MODE) {
			goPrevious();
			return true;
		}
		if (event.key() == GLFW.GLFW_KEY_RIGHT
				&& state.step() != ScenarioWizardStep.MODE
				&& state.step() != ScenarioWizardStep.REVIEW) {
			goNext();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if ((state.step() == ScenarioWizardStep.ROSTER || state.step() == ScenarioWizardStep.REVIEW)
				&& state.roster().size() > MAX_VISIBLE_ROSTER_ROWS) {
			int next = Math.clamp(
					rosterScroll + (verticalAmount > 0.0D ? -1 : 1),
					0,
					state.roster().size() - MAX_VISIBLE_ROSTER_ROWS
			);
			if (next != rosterScroll) {
				rosterScroll = next;
				rebuildWidgets();
				return true;
			}
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	private void initMode() {
		int center = width / 2;
		int cardWidth = Math.min(300, panelWidth() - 50);
		addRenderableWidget(Button.builder(
				Component.literal("Spawn agents normally"),
				button -> openNormalControls()
		).bounds(center - cardWidth / 2, 86, cardWidth, 36).build());
		addRenderableWidget(Button.builder(
				Component.literal("Run a preset arena"),
				button -> {
					state.choosePresetWorkflow();
					rebuildWidgets();
				}
		).bounds(center - cardWidth / 2, 152, cardWidth, 36).build());
		addCancelButton();
	}

	private void initArena() {
		int left = panelLeft() + 18;
		int available = panelWidth() - 36;
		int cardWidth = (available - GAP) / 2;
		int cardHeight = Math.max(48, Math.min(64, (height - 142) / 2));
		ScenarioPreset[] presets = ScenarioPreset.values();
		for (int index = 0; index < presets.length; index++) {
			ScenarioPreset preset = presets[index];
			int column = index % 2;
			int row = index / 2;
			int x = left + column * (cardWidth + GAP);
			int y = 70 + row * (cardHeight + GAP);
			String marker = state.selectedScenario() == preset ? "◆ " : "  ";
			addRenderableWidget(Button.builder(
					Component.literal(marker + preset.letter() + " · " + preset.title())
							.withColor(preset.accentColor()),
					button -> {
						state.selectScenario(preset);
						feedback = preset.category() + " · " + preset.duration();
						feedbackError = false;
						rebuildWidgets();
					}
			).bounds(x, y, cardWidth, cardHeight).build());
		}
		addPreviousNext();
	}

	private void initRoster() {
		int left = panelLeft() + 16;
		int contentWidth = panelWidth() - 32;
		int top = 64;
		int countWidth = 34;
		addRenderableWidget(Button.builder(Component.literal("−"), button -> changeCount(-1))
				.bounds(left, top, countWidth, ROW_HEIGHT).build());
		addRenderableWidget(Button.builder(
				Component.literal(state.roster().size() + " agents"),
				button -> {
				}
		).bounds(left + countWidth + GAP, top, 72, ROW_HEIGHT).build()).active = false;
		addRenderableWidget(Button.builder(Component.literal("+"), button -> changeCount(1))
				.bounds(left + countWidth + GAP + 72 + GAP, top, countWidth, ROW_HEIGHT).build());
		int quickX = left + countWidth * 2 + 72 + GAP * 3;
		for (int count : List.of(2, 4, 8, 16)) {
			addRenderableWidget(Button.builder(Component.literal(String.valueOf(count)), button -> setCount(count))
					.bounds(quickX, top, 30, ROW_HEIGHT).build());
			quickX += 30 + GAP;
		}

		int listTop = top + ROW_HEIGHT + GAP;
		int listWidth = Math.clamp(contentWidth / 2, 190, 270);
		addRosterRows(left, listTop, listWidth);
		int editorX = left + listWidth + 12;
		int editorWidth = contentWidth - listWidth - 12;
		addRosterEditor(editorX, listTop, editorWidth);
		addPreviousNext();
	}

	private void initReview() {
		int left = panelLeft() + 18;
		int contentWidth = panelWidth() - 36;
		int top = 72;
		int half = (contentWidth - GAP) / 2;
		addRenderableWidget(CycleButton.builder(
						value -> Component.literal(value ? "Deterministic events" : "Randomized events"),
						state.deterministicEvents()
				)
				.withValues(List.of(true, false))
				.create(left, top, half, ROW_HEIGHT, Component.literal("Event seed"),
						(button, value) -> state.setDeterministicEvents(value)));
		addRenderableWidget(Button.builder(
				Component.literal("Save preset"),
				button -> setFeedback("Roster preset saving arrives with persistent arena packs", true)
		).bounds(left + half + GAP, top, half, ROW_HEIGHT).build()).active = false;

		int footerY = height - 31;
		int buttonWidth = 92;
		addRenderableWidget(Button.builder(Component.literal("Previous"), button -> goPrevious())
				.bounds(left, footerY, buttonWidth, ROW_HEIGHT).build());
		Button launch = Button.builder(Component.literal("Launch arena"), button -> launch())
				.bounds(left + contentWidth - buttonWidth, footerY, buttonWidth, ROW_HEIGHT)
				.build();
		launch.active = state.canLaunch() && ScenarioLaunchRegistry.isAvailable();
		addRenderableWidget(launch);
		addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
				.bounds(left + contentWidth - (buttonWidth * 2) - GAP, footerY, buttonWidth, ROW_HEIGHT).build());
	}

	private void addRosterRows(int x, int y, int width) {
		List<ScenarioAgentConfig> roster = state.roster();
		rosterScroll = Math.clamp(rosterScroll, 0, Math.max(0, roster.size() - MAX_VISIBLE_ROSTER_ROWS));
		int visible = Math.min(MAX_VISIBLE_ROSTER_ROWS, roster.size() - rosterScroll);
		for (int row = 0; row < visible; row++) {
			int index = rosterScroll + row;
			ScenarioAgentConfig config = roster.get(index);
			boolean selected = state.selectedIndices().contains(index);
			String marker = selected ? "◆ " : "◇ ";
			String label = marker + (index + 1) + "  " + state.displayNameAt(index);
			addRenderableWidget(Button.builder(
					Component.literal(label).withColor(providerColor(config.provider())),
					button -> {
						state.toggleSelected(index);
						rebuildWidgets();
					}
			).bounds(x + 4, y + row * (ROW_HEIGHT + 2), width - 4, ROW_HEIGHT).build());
		}
		int controlsY = y + MAX_VISIBLE_ROSTER_ROWS * (ROW_HEIGHT + 2);
		int half = (width - GAP) / 2;
		addRenderableWidget(Button.builder(Component.literal("Select all"), button -> {
			state.selectAll();
			rebuildWidgets();
		}).bounds(x, controlsY, half, ROW_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.literal("Clear"), button -> {
			state.clearSelection();
			rebuildWidgets();
		}).bounds(x + half + GAP, controlsY, half, ROW_HEIGHT).build());
	}

	private void addRosterEditor(int x, int y, int width) {
		ScenarioAgentConfig anchor = selectedConfig();
		if (anchor == null) {
			return;
		}
		int fieldWidth = Math.max(90, width);
		addRenderableWidget(CycleButton.builder(
						value -> Component.literal(capitalize(value)),
						anchor.provider()
				)
				.withValues(AgentControlCatalog.providers())
				.create(x, y, fieldWidth, ROW_HEIGHT, Component.literal("Provider"),
						(button, value) -> applyAndRebuild(() -> state.applyProvider(value))));
		y += ROW_HEIGHT + GAP;

		addRenderableWidget(CycleButton.builder(Component::literal, anchor.model())
				.withValues(AgentControlCatalog.models(anchor.provider()))
				.create(x, y, fieldWidth, ROW_HEIGHT, Component.literal("Model"),
						(button, value) -> applyAndRebuild(() -> state.applyModel(value))));
		y += ROW_HEIGHT + GAP;

		addRenderableWidget(CycleButton.builder(Component::literal, anchor.reasoning())
				.withValues(AgentControlCatalog.reasoningEfforts(anchor.provider(), anchor.model()))
				.create(x, y, fieldWidth, ROW_HEIGHT, Component.literal("Thinking"),
						(button, value) -> applyAndRebuild(() -> state.applyReasoning(value))));
		y += ROW_HEIGHT + GAP;

		addRenderableWidget(CycleButton.builder(Component::literal, anchor.team())
				.withValues(ScenarioAgentConfig.TEAMS)
				.create(x, y, fieldWidth, ROW_HEIGHT, Component.literal("Team"),
						(button, value) -> applyAndRebuild(() -> state.applyTeam(value))));
		y += ROW_HEIGHT + GAP;

		addRenderableWidget(CycleButton.builder(
						value -> Component.literal(value.displayName()),
						anchor.gameMode()
				)
				.withValues(List.of(AgentGameMode.values()))
				.create(x, y, fieldWidth, ROW_HEIGHT, Component.literal("Game mode"),
						(button, value) -> applyAndRebuild(() -> state.applyGameMode(value))));
		y += ROW_HEIGHT + GAP;

		nameInput = new EditBox(font, x, y, fieldWidth, ROW_HEIGHT, Component.literal("Agent name"));
		nameInput.setMaxLength(AgentConstants.MAX_USER_NAME_LENGTH);
		nameInput.setHint(Component.literal(state.selectedIndices().size() == 1 ? "Optional agent name" : "Select one agent to rename"));
		nameInput.setValue(state.selectedIndices().size() == 1 ? anchor.name() : "");
		nameInput.active = state.selectedIndices().size() == 1;
		if (nameInput.active) {
			nameInput.setResponder(value -> {
				try {
					state.renameSelected(value);
				} catch (IllegalArgumentException | IllegalStateException exception) {
					setFeedback(exception.getMessage(), true);
				}
			});
		}
		addRenderableWidget(nameInput);
	}

	private void renderStepRail(GuiGraphicsExtractor graphics, int x, int y, int width) {
		ScenarioWizardStep[] steps = ScenarioWizardStep.values();
		int segmentWidth = width / steps.length;
		for (int index = 0; index < steps.length; index++) {
			ScenarioWizardStep step = steps[index];
			int color = index <= state.step().ordinal() ? GOLD : 0xFF4A4F59;
			int segmentX = x + index * segmentWidth;
			graphics.fill(segmentX, y, segmentX + segmentWidth - 3, y + 2, color);
			graphics.centeredText(font, (index + 1) + "  " + step.displayName(),
					segmentX + (segmentWidth - 3) / 2, y + 7, index == state.step().ordinal() ? TEXT : MUTED);
		}
	}

	private void renderStepContent(GuiGraphicsExtractor graphics, int x, int y, int width) {
		switch (state.step()) {
			case MODE -> {
				graphics.centeredText(font, "Choose how this run begins", width / 2 + x, y, TEXT);
				graphics.centeredText(font, "Free-world controls and existing agents", width / 2 + x, 126, MUTED);
				graphics.centeredText(font, "Repeatable Survival, Building, PvP, or Parkour showcase", width / 2 + x, 192, MUTED);
			}
			case ARENA -> {
				ScenarioPreset preset = state.selectedScenario();
				int infoY = Math.min(height - 69, 214);
				graphics.fill(x, infoY, x + width, infoY + 34, SURFACE_COLOR);
				graphics.text(font, preset.category() + " · " + preset.duration() + " · "
						+ preset.minimumAgents() + "–" + preset.maximumAgents() + " agents", x + 8, infoY + 5, TEXT, false);
				graphics.text(font, preset.description(), x + 8, infoY + 18, MUTED, false);
			}
			case ROSTER -> {
				renderRosterBands(graphics, x, y + ROW_HEIGHT + GAP + 2, Math.clamp(width / 2, 190, 270));
				graphics.text(font, "Select any rows, then apply settings in bulk", x, y - 1, MUTED, false);
				String modeLabel = state.selectedScenario().gameModeLocked()
						? state.selectedScenario().title() + " requires " + state.selectedScenario().defaultGameMode().displayName()
						: "Building mode is configurable";
				graphics.text(font, modeLabel, x + width - 230, y - 1, GOLD, false);
			}
			case REVIEW -> renderReview(graphics, x, y + 38, width);
		}
	}

	private void renderRosterBands(GuiGraphicsExtractor graphics, int x, int y, int width) {
		List<ScenarioAgentConfig> roster = state.roster();
		int visible = Math.min(MAX_VISIBLE_ROSTER_ROWS, roster.size() - rosterScroll);
		for (int row = 0; row < visible; row++) {
			int index = rosterScroll + row;
			int rowY = y + row * (ROW_HEIGHT + 2);
			graphics.fill(x, rowY, x + width, rowY + ROW_HEIGHT,
					state.selectedIndices().contains(index) ? SURFACE_SELECTED : SURFACE_COLOR);
			graphics.fill(x, rowY, x + 4, rowY + ROW_HEIGHT, providerColor(roster.get(index).provider()));
		}
	}

	private void renderReview(GuiGraphicsExtractor graphics, int x, int y, int width) {
		ScenarioPreset preset = state.selectedScenario();
		graphics.fill(x, y, x + width, y + 36, SURFACE_COLOR);
		graphics.text(font, preset.letter() + " · " + preset.title(), x + 8, y + 6, preset.accentColor(), false);
		graphics.text(font, preset.category() + " · " + preset.duration() + " · map " + preset.mapVersion(),
				x + 8, y + 20, MUTED, false);
		int rosterY = y + 43;
		List<ScenarioAgentConfig> roster = state.roster();
		rosterScroll = Math.clamp(rosterScroll, 0, Math.max(0, roster.size() - MAX_VISIBLE_ROSTER_ROWS));
		int visible = Math.min(MAX_VISIBLE_ROSTER_ROWS, roster.size() - rosterScroll);
		for (int row = 0; row < visible; row++) {
			int index = rosterScroll + row;
			ScenarioAgentConfig config = roster.get(index);
			int rowY = rosterY + row * 18;
			graphics.fill(x, rowY, x + width, rowY + 16, index % 2 == 0 ? SURFACE_COLOR : SURFACE_SELECTED);
			graphics.text(font, (index + 1) + "  " + state.displayNameAt(index), x + 6, rowY + 4,
					providerColor(config.provider()), false);
			String details = config.provider() + " · " + config.model() + " · " + config.reasoning()
					+ " · " + config.team() + " · " + config.gameMode().displayName();
			graphics.text(font, details, x + Math.min(190, width / 3), rowY + 4, MUTED, false);
		}
		if (!ScenarioLaunchRegistry.isAvailable()) {
			graphics.text(font, "Launch blocked: arena runtime has not registered its client launch handler.",
					x, height - 48, ERROR, false);
		} else if (!state.validationErrors().isEmpty()) {
			graphics.text(font, "Launch blocked: " + state.validationErrors().getFirst(), x, height - 48, ERROR, false);
		} else {
			graphics.text(font, "Ready · exact model settings preserved · no silent fallback", x, height - 48, SUCCESS, false);
		}
	}

	private void addPreviousNext() {
		int left = panelLeft() + 18;
		int y = height - 31;
		int width = 90;
		addRenderableWidget(Button.builder(Component.literal("Previous"), button -> goPrevious())
				.bounds(left, y, width, ROW_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.literal("Next"), button -> goNext())
				.bounds(panelLeft() + panelWidth() - 18 - width, y, width, ROW_HEIGHT).build());
	}

	private void addCancelButton() {
		addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
				.bounds(width / 2 - 50, height - 31, 100, ROW_HEIGHT).build());
	}

	private void openNormalControls() {
		if (minecraft == null) {
			return;
		}
		minecraft.setScreen(new AgentControlScreen());
		AgentControlClient.requestSnapshot();
	}

	private void goPrevious() {
		state.previous();
		feedback = "";
		rebuildWidgets();
	}

	private void goNext() {
		state.next();
		feedback = "";
		rebuildWidgets();
	}

	private void changeCount(int delta) {
		setCount(state.roster().size() + delta);
	}

	private void setCount(int count) {
		state.setAgentCount(count);
		rosterScroll = Math.min(rosterScroll, Math.max(0, state.roster().size() - MAX_VISIBLE_ROSTER_ROWS));
		rebuildWidgets();
	}

	private void launch() {
		ScenarioLaunchRegistry.Result result = ScenarioLaunchRegistry.launch(state.launchPlan());
		setFeedback(result.message(), !result.accepted());
		if (result.accepted() && minecraft != null) {
			onClose();
		}
	}

	private void applyAndRebuild(Runnable operation) {
		try {
			operation.run();
			setFeedback(state.selectedIndices().size() + " agent setting(s) updated", false);
		} catch (IllegalArgumentException | IllegalStateException exception) {
			setFeedback(exception.getMessage(), true);
		}
		rebuildWidgets();
	}

	private ScenarioAgentConfig selectedConfig() {
		Set<Integer> selected = state.selectedIndices();
		if (selected.isEmpty()) {
			return null;
		}
		int index = selected.stream().min(Integer::compareTo).orElseThrow();
		return state.roster().get(index);
	}

	private void setFeedback(String message, boolean error) {
		feedback = message == null ? "" : message;
		feedbackError = error;
	}

	private int panelWidth() {
		return Math.max(300, Math.min(820, width - 20));
	}

	private int panelLeft() {
		return (width - panelWidth()) / 2;
	}

	private static int providerColor(String provider) {
		return switch (provider.toLowerCase(Locale.ROOT)) {
			case "codex" -> 0xFF42D39B;
			case "gemini", "antigravity" -> 0xFF8E86FF;
			case "kimi" -> 0xFFFFB45E;
			default -> TEXT;
		};
	}

	private static String capitalize(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		return Character.toUpperCase(value.charAt(0)) + value.substring(1);
	}
}
