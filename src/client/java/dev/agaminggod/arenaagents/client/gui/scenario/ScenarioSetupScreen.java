package dev.agaminggod.arenaagents.client.gui.scenario;

import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.client.gui.AgentControlScreen;
import dev.agaminggod.arenaagents.client.gui.ConsoleFont;
import dev.agaminggod.arenaagents.client.gui.ConsoleTheme;
import dev.agaminggod.arenaagents.client.gui.ConsoleFocusIdentity;
import dev.agaminggod.arenaagents.client.gui.ConsoleText;
import dev.agaminggod.arenaagents.client.control.AgentControlClient;
import dev.agaminggod.arenaagents.client.gui.widget.ConsoleButton;
import dev.agaminggod.arenaagents.client.gui.widget.ConsoleCycleButton;
import dev.agaminggod.arenaagents.client.gui.widget.ConsoleEditBox;
import dev.agaminggod.arenaagents.client.gui.widget.ConsoleScenarioTile;
import dev.agaminggod.arenaagents.client.gui.widget.ConsoleSelectionRow;
import dev.agaminggod.arenaagents.control.AgentControlCatalog;
import dev.agaminggod.arenaagents.control.AgentControlPresentation;
import dev.agaminggod.arenaagents.scenario.ScenarioPlacementMode;
import dev.agaminggod.arenaagents.scenario.presentation.ScenarioBuildProgress;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Responsive Arena tab for the in-game command center. */
public final class ScenarioSetupScreen extends Screen {
	private static final int PANEL_COLOR = ConsoleTheme.PANEL;
	private static final int PANEL_EDGE = ConsoleTheme.BORDER;
	private static final int SURFACE_COLOR = ConsoleTheme.SURFACE;
	private static final int SURFACE_SELECTED = ConsoleTheme.SURFACE_SELECTED;
	private static final int GOLD = ConsoleTheme.ACCENT;
	private static final int TEXT = ConsoleTheme.TEXT;
	private static final int MUTED = ConsoleTheme.MUTED;
	private static final int ERROR = ConsoleTheme.ERROR;
	private static final int SUCCESS = ConsoleTheme.SUCCESS;
	private static final int ROW_HEIGHT = ScenarioSetupLayout.ROW_HEIGHT;
	private static final int GAP = 5;

	private final ScenarioSetupState state;
	private int rosterScroll;
	private int visibleRosterRows = 7;
	private int compactEditorSection;
	private String feedback = "";
	private boolean feedbackError;
	private boolean launchPending;
	private boolean firstInitialization = true;
	private ConsoleEditBox nameInput;

	public ScenarioSetupScreen() {
		this(defaultState());
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private static ScenarioSetupState defaultState() {
		AgentControlClient.Preferences preferences = AgentControlClient.preferences();
		ScenarioBuildProgress progress = AgentControlClient.buildProgressState().progress().orElse(null);
		if (progress != null && progress.status() != ScenarioBuildProgress.Status.READY) {
			ScenarioSetupState retained = ScenarioLaunchRegistry.lastAcceptedPlan()
					.map(ScenarioSetupState::fromLaunchPlan)
					.orElse(null);
			if (retained != null) return retained;
		}
		return ScenarioSetupState.defaults(preferences.provider(), preferences.model(), preferences.reasoning());
	}

	ScenarioSetupScreen(ScenarioSetupState state) {
		super(Minecraft.getInstance(), ConsoleFont.create(Minecraft.getInstance()),
				Component.translatable("screen.arenaagents.setup.title"));
		this.state = state;
	}

	@Override
	protected void init() {
		nameInput = null;
		if (firstInitialization) {
			firstInitialization = false;
			ScenarioBuildProgress progress = AgentControlClient.buildProgressState().progress().orElse(null);
			if (progress != null && progress.status() != ScenarioBuildProgress.Status.READY) {
				for (ScenarioPreset preset : ScenarioPreset.values()) {
					if (preset.title().equals(progress.scenarioTitle())) state.selectScenario(preset);
				}
				state.showBuildDashboard();
				launchPending = true;
			}
		}
		ScenarioSetupLayout layout = layout();
		addTabs(layout);
		switch (state.step()) {
			case ARENA -> initArena(layout);
			case ROSTER -> initRoster(layout);
			case REVIEW -> initReview(layout);
		}
	}

	@Override
	protected void rebuildWidgets() {
		String focusKey = getFocused() instanceof AbstractWidget widget ? ConsoleFocusIdentity.of(widget) : "";
		super.rebuildWidgets();
		if (focusKey.isBlank()) return;
		children().stream()
				.filter(AbstractWidget.class::isInstance)
				.map(AbstractWidget.class::cast)
				.filter(widget -> ConsoleFocusIdentity.of(widget).equals(focusKey))
				.findFirst()
				.ifPresent(this::setInitialFocus);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		ScenarioSetupLayout layout = layout();
		graphics.fill(0, 0, width, height, ConsoleTheme.BACKDROP);
		graphics.fill(layout.panelLeft() - 1, layout.panelTop() - 1,
				layout.panelRight() + 1, layout.panelBottom() + 1, PANEL_EDGE);
		graphics.fill(layout.panelLeft(), layout.panelTop(), layout.panelRight(), layout.panelBottom(), PANEL_COLOR);
		renderShell(graphics, layout);
		renderStepRail(graphics, layout);
		renderStepContent(graphics, layout);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		renderStatus(graphics, layout);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (getFocused() instanceof EditBox) return super.keyPressed(event);
		if (state.step() == ScenarioWizardStep.ARENA) {
			ScenarioPreset preset = ScenarioPreset.fromLetter(event.key());
			if (preset != null) {
				state.selectScenario(preset);
				setFeedback(preset.title() + " selected", false);
				rebuildWidgets();
				return true;
			}
		}
		if (event.key() == GLFW.GLFW_KEY_LEFT && state.step() != ScenarioWizardStep.ARENA) {
			goPrevious();
			return true;
		}
		if (event.key() == GLFW.GLFW_KEY_RIGHT && state.step() != ScenarioWizardStep.REVIEW) {
			goNext();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if ((state.step() == ScenarioWizardStep.ROSTER || state.step() == ScenarioWizardStep.REVIEW)
				&& state.roster().size() > visibleRosterRows) {
			int next = Math.clamp(rosterScroll + (verticalAmount > 0.0D ? -1 : 1),
					0, state.roster().size() - visibleRosterRows);
			if (next != rosterScroll) {
				rosterScroll = next;
				rebuildWidgets();
				return true;
			}
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	private void addTabs(ScenarioSetupLayout layout) {
		if (layout.sideNavigation()) {
			int x = layout.panelLeft() + 10;
			int width = layout.contentLeft() - layout.panelLeft() - 24;
			int y = layout.navigationTop();
			addRenderableWidget(consoleButton(Component.translatable("screen.arenaagents.navigation.agents"),
					x, y, width, ROW_HEIGHT, false, this::openAgents));
			addRenderableWidget(consoleButton(Component.translatable("screen.arenaagents.navigation.group"),
					x, y + 31, width, ROW_HEIGHT, false, this::openGroup));
			addRenderableWidget(consoleButton(Component.translatable("screen.arenaagents.navigation.live"),
					x, y + 62, width, ROW_HEIGHT, false, this::openLiveArena));
			addRenderableWidget(consoleButton(Component.translatable("screen.arenaagents.navigation.build"),
					x, y + 93, width, ROW_HEIGHT, true, () -> { }));
			return;
		}
		int x = layout.contentLeft();
		String[] labels = {"AGENTS", "GROUP", "LIVE", "BUILD"};
		Runnable[] actions = {this::openAgents, this::openGroup, this::openLiveArena, () -> { }};
		int available = layout.contentWidth() - GAP * 3;
		int buttonWidth = available / 4;
		for (int index = 0; index < labels.length; index++) {
			int buttonX = x + index * (buttonWidth + GAP);
			int actualWidth = index == labels.length - 1 ? layout.contentRight() - buttonX : buttonWidth;
			addRenderableWidget(consoleButton(labels[index], buttonX, layout.navigationTop(), actualWidth,
					ROW_HEIGHT, index == labels.length - 1, actions[index]));
		}
	}

	private void initArena(ScenarioSetupLayout layout) {
		int left = layout.contentLeft();
		int available = layout.contentWidth();
		if (layout.compactArenaPicker()) {
			int tileWidth = (available - GAP * (ScenarioPreset.values().length - 1))
					/ ScenarioPreset.values().length;
			for (int index = 0; index < ScenarioPreset.values().length; index++) {
				ScenarioPreset preset = ScenarioPreset.values()[index];
				int x = left + index * (tileWidth + GAP);
				int width = index == ScenarioPreset.values().length - 1
						? layout.contentRight() - x : tileWidth;
				addRenderableWidget(consoleButton(String.valueOf(preset.letter()), x, layout.contentTop(), width,
						ROW_HEIGHT, state.selectedScenario() == preset, preset.accentColor(), () -> {
							state.selectScenario(preset);
							setFeedback(preset.title() + " selected", false);
							rebuildWidgets();
						}));
			}
			int placementY = layout.contentBottom() - ROW_HEIGHT;
			addRenderableWidget(new ConsoleCycleButton<>(font, left, placementY, available, ROW_HEIGHT,
					Component.translatable("screen.arenaagents.build.location"),
					List.of(ScenarioPlacementMode.values()), state.placementMode(),
					mode -> Component.literal(mode.displayName()), this::setPlacementMode));
			addFooter(layout, false, true, "Next");
			return;
		}
		int columns = 2;
		int cardWidth = (available - (columns - 1) * GAP) / columns;
		int rows = (ScenarioPreset.values().length + columns - 1) / columns;
		int infoHeight = 38;
		int cardsHeight = layout.contentHeight() - infoHeight - GAP;
		int cardHeight = Math.clamp((cardsHeight - (rows - 1) * GAP) / rows, 32, 64);
		for (int index = 0; index < ScenarioPreset.values().length; index++) {
			ScenarioPreset preset = ScenarioPreset.values()[index];
			int column = index % columns;
			int row = index / columns;
			int x = left + column * (cardWidth + GAP);
			int y = layout.contentTop() + row * (cardHeight + GAP);
			boolean selected = state.selectedScenario() == preset;
			addRenderableWidget(new ConsoleScenarioTile(
					font, x, y, cardWidth, cardHeight,
					Component.literal(String.valueOf(preset.letter())), Component.literal(preset.title()),
					Component.literal(preset.category() + "  |  " + preset.duration()),
					selected, preset.accentColor(), () -> {
						state.selectScenario(preset);
						setFeedback(preset.category() + " | " + preset.duration(), false);
						rebuildWidgets();
					}
			));
		}
		int placementY = layout.contentBottom() - ROW_HEIGHT;
		addRenderableWidget(new ConsoleCycleButton<>(font, left, placementY, available, ROW_HEIGHT,
				Component.translatable("screen.arenaagents.build.location"),
				List.of(ScenarioPlacementMode.values()), state.placementMode(),
				mode -> Component.literal(mode.displayName()), this::setPlacementMode));
		addFooter(layout, false, true, "Next");
	}

	private void initRoster(ScenarioSetupLayout layout) {
		int left = layout.contentLeft();
		int contentWidth = layout.contentWidth();
		int toolbarY = layout.contentTop();
		addCountControls(left, toolbarY, contentWidth, layout.wideRoster());
		int bodyTop = toolbarY + ROW_HEIGHT + GAP;
		if (layout.wideRoster()) {
			int listWidth = Math.clamp(contentWidth * 45 / 100, 210, 310);
			int editorX = left + listWidth + 12;
			visibleRosterRows = Math.clamp((layout.contentBottom() - bodyTop - ROW_HEIGHT) / 40, 2, 7);
			addRosterRows(left, bodyTop, listWidth, visibleRosterRows);
			addRosterEditor(editorX, bodyTop, contentWidth - listWidth - 12, false);
			int navY = Math.min(bodyTop + 178, layout.contentBottom() - ROW_HEIGHT);
			int navWidth = (contentWidth - listWidth - 12 - GAP) / 2;
			addRenderableWidget(consoleButton("PREVIOUS AGENT", editorX, navY, navWidth, ROW_HEIGHT,
					false, () -> moveCompactSelection(-1)));
			addRenderableWidget(consoleButton("NEXT AGENT", editorX + navWidth + GAP, navY,
					contentWidth - listWidth - 12 - navWidth - GAP, ROW_HEIGHT,
					false, () -> moveCompactSelection(1)));
		} else {
			visibleRosterRows = 1;
			addCompactRosterSelection(left + 154, toolbarY, contentWidth - 154);
			bodyTop = toolbarY + ROW_HEIGHT + 3;
			addCompactRosterEditor(left, bodyTop, contentWidth);
		}
		addFooter(layout, true, true, "Review setup");
	}

	private void initReview(ScenarioSetupLayout layout) {
		if (launchPending) {
			addBuildFooter(layout);
			return;
		}
		int left = layout.contentLeft();
		int contentWidth = layout.contentWidth();
		int half = (contentWidth - GAP) / 2;
		addRenderableWidget(new ConsoleCycleButton<>(font, left, layout.contentTop(), half, ROW_HEIGHT,
				Component.literal("Event seed"), List.of(true, false), state.deterministicEvents(),
				value -> Component.literal(value ? "Deterministic" : "Randomized"), state::setDeterministicEvents));
		addRenderableWidget(new ConsoleCycleButton<>(font, left + half + GAP, layout.contentTop(), half, ROW_HEIGHT,
				Component.translatable("screen.arenaagents.build.location"), List.of(ScenarioPlacementMode.values()),
				state.placementMode(), mode -> Component.literal(mode.displayName()), this::setPlacementMode));
		int rosterTop = layout.contentTop() + ROW_HEIGHT + GAP + 42;
		visibleRosterRows = Math.clamp((layout.contentBottom() - rosterTop) / 18, 1, 10);
		addFooter(layout, true, true, "Launch arena");
	}

	private void addBuildFooter(ScenarioSetupLayout layout) {
		ScenarioBuildProgress progress = AgentControlClient.buildProgressState().progress().orElse(null);
		int buttonWidth = layout.footerButtonWidth();
		addRenderableWidget(consoleButton("CLOSE", layout.contentLeft(), layout.footerY(), buttonWidth,
				ROW_HEIGHT, false, this::onClose));
		if (progress != null && progress.status() == ScenarioBuildProgress.Status.FAILED) {
			addRenderableWidget(new ConsoleButton(font, layout.contentRight() - buttonWidth, layout.footerY(), buttonWidth,
					ROW_HEIGHT, Component.literal("RETRY BUILD"), false, GOLD, ConsoleButton.Tone.PRIMARY, () -> {
				launchPending = false;
				setFeedback("Review the setup, then retry the arena build.", false);
				rebuildWidgets();
			}));
		} else {
			addRenderableWidget(new ConsoleButton(font, layout.contentRight() - buttonWidth, layout.footerY(), buttonWidth,
					ROW_HEIGHT, Component.literal("OPEN LIVE ARENA"), false, GOLD, ConsoleButton.Tone.PRIMARY,
					this::openLiveArena));
		}
	}

	private void addCountControls(int x, int y, int width, boolean showQuickCounts) {
		int countWidth = 34;
		addRenderableWidget(consoleButton("-", x, y, countWidth, ROW_HEIGHT, false, () -> changeCount(-1)));
		ConsoleButton count = consoleButton(state.roster().size() + " AGENTS", x + countWidth + GAP, y,
				72, ROW_HEIGHT, false, () -> { });
		count.active = false;
		addRenderableWidget(count);
		addRenderableWidget(consoleButton("+", x + countWidth + GAP + 72 + GAP, y, countWidth, ROW_HEIGHT,
				false, () -> changeCount(1)));
		if (!showQuickCounts) return;
		int quickX = x + countWidth * 2 + 72 + GAP * 3;
		for (int requested : List.of(2, 4, 8, 16)) {
			addRenderableWidget(consoleButton(String.valueOf(requested), quickX, y, 30, ROW_HEIGHT,
					state.roster().size() == requested, () -> setCount(requested)));
			quickX += 30 + GAP;
		}
	}

	private void addCompactRosterSelection(int x, int y, int width) {
		int buttonWidth = Math.max(38, (width - GAP) / 2);
		addRenderableWidget(consoleButton("PREV", x, y, buttonWidth, ROW_HEIGHT, false,
				() -> moveCompactSelection(-1)));
		addRenderableWidget(consoleButton("NEXT", x + buttonWidth + GAP, y,
				width - buttonWidth - GAP, ROW_HEIGHT, false, () -> moveCompactSelection(1)));
	}

	private void addCompactRosterEditor(int x, int y, int width) {
		ScenarioAgentConfig anchor = selectedConfig();
		if (anchor == null) return;
		int tabWidth = (width - GAP) / 2;
		addRenderableWidget(consoleButton(Component.translatable("screen.arenaagents.roster.model_speed"),
				x, y, tabWidth, ROW_HEIGHT,
				compactEditorSection == 0, () -> {
					compactEditorSection = 0;
					rebuildWidgets();
				}));
		addRenderableWidget(consoleButton(Component.translatable("screen.arenaagents.roster.identity_game"),
				x + tabWidth + GAP, y,
				width - tabWidth - GAP, ROW_HEIGHT, compactEditorSection == 1, () -> {
					compactEditorSection = 1;
					rebuildWidgets();
				}));
		int editorY = y + ROW_HEIGHT + 3;
		int columnWidth = (width - GAP) / 2;
		int right = x + columnWidth + GAP;
		if (compactEditorSection == 0) {
			addRenderableWidget(new ConsoleCycleButton<>(font, x, editorY, columnWidth, ROW_HEIGHT,
					Component.literal("Provider"), AgentControlCatalog.providers(), anchor.provider(),
					value -> Component.literal(capitalize(value)),
					value -> applyAndRebuild(() -> state.applyProvider(value))));
			addRenderableWidget(new ConsoleCycleButton<>(font, right, editorY, width - columnWidth - GAP, ROW_HEIGHT,
					Component.literal("Model"), AgentControlCatalog.models(anchor.provider()), anchor.model(),
					value -> Component.literal(AgentControlCatalog.displayName(anchor.provider(), value)),
					value -> applyAndRebuild(() -> state.applyModel(value))));
			int secondY = editorY + ROW_HEIGHT + 2;
			addRenderableWidget(new ConsoleCycleButton<>(font, x, secondY, columnWidth, ROW_HEIGHT,
					Component.literal("Thinking"), AgentControlCatalog.reasoningEfforts(anchor.provider(), anchor.model()),
					anchor.reasoning(), Component::literal,
					value -> applyAndRebuild(() -> state.applyReasoning(value))));
			addSpeedModeControl(anchor, right, secondY, width - columnWidth - GAP);
			return;
		}

		nameInput = consoleEditBox(x, editorY, width, "compact-agent-name");
		nameInput.setMaxLength(AgentConstants.MAX_USER_NAME_LENGTH);
		nameInput.setValue(anchor.name());
		nameInput.setResponder(value -> state.renameSelected(value));
		addRenderableWidget(nameInput);
		int secondY = editorY + ROW_HEIGHT + 2;
		addRenderableWidget(new ConsoleCycleButton<>(font, x, secondY, columnWidth, ROW_HEIGHT,
				Component.literal("Team"), ScenarioAgentConfig.TEAMS, anchor.team(), Component::literal,
				value -> applyAndRebuild(() -> state.applyTeam(value))));
		addRenderableWidget(new ConsoleCycleButton<>(font, right, secondY, width - columnWidth - GAP, ROW_HEIGHT,
				Component.literal("Game mode"), List.of(AgentGameMode.values()), anchor.gameMode(),
				value -> Component.literal(value.displayName()),
				value -> applyAndRebuild(() -> state.applyGameMode(value))));
	}

	private void addRosterRows(int x, int y, int width, int maximumRows) {
		List<ScenarioAgentConfig> roster = state.roster();
		rosterScroll = Math.clamp(rosterScroll, 0, Math.max(0, roster.size() - maximumRows));
		int visible = Math.min(maximumRows, roster.size() - rosterScroll);
		for (int row = 0; row < visible; row++) {
			int index = rosterScroll + row;
			ScenarioAgentConfig config = roster.get(index);
			boolean selected = state.selectedIndex() == index;
			addRenderableWidget(new ConsoleSelectionRow(
					font, x, y + row * 40, width, 36,
					Component.literal(String.valueOf(index + 1)), Component.literal(state.displayNameAt(index)),
					Component.literal(capitalize(config.provider()) + "  |  "
							+ AgentControlCatalog.displayName(config.provider(), config.model())),
					Component.literal(AgentControlPresentation.speedLabel(config.serviceTier())),
					"setup-" + index,
					selected, false, providerColor(config.provider()), () -> {
						state.selectOnly(index);
						rebuildWidgets();
					}
			));
		}
	}

	private void addRosterEditor(int x, int y, int width, boolean compact) {
		ScenarioAgentConfig anchor = selectedConfig();
		if (anchor == null) return;
		int columnWidth = compact ? (width - GAP) / 2 : width;
		int right = x + columnWidth + GAP;
		addRenderableWidget(new ConsoleCycleButton<>(font, x, y, columnWidth, ROW_HEIGHT,
				Component.literal("Provider"), AgentControlCatalog.providers(), anchor.provider(),
				value -> Component.literal(capitalize(value)),
				value -> applyAndRebuild(() -> state.applyProvider(value))));
		addRenderableWidget(new ConsoleCycleButton<>(font, compact ? right : x, compact ? y : y + 25,
				columnWidth, ROW_HEIGHT, Component.literal("Model"), AgentControlCatalog.models(anchor.provider()),
				anchor.model(), value -> Component.literal(AgentControlCatalog.displayName(anchor.provider(), value)),
				value -> applyAndRebuild(() -> state.applyModel(value))));
		int secondY = compact ? y + 25 : y + 50;
		addRenderableWidget(new ConsoleCycleButton<>(font, x, secondY, columnWidth, ROW_HEIGHT,
				Component.literal("Thinking"), AgentControlCatalog.reasoningEfforts(anchor.provider(), anchor.model()),
				anchor.reasoning(), Component::literal,
				value -> applyAndRebuild(() -> state.applyReasoning(value))));
		addSpeedModeControl(anchor, compact ? right : x, compact ? secondY : y + 75, columnWidth);
		addRenderableWidget(new ConsoleCycleButton<>(font, x, compact ? y + 50 : y + 100,
				columnWidth, ROW_HEIGHT, Component.literal("Team"), ScenarioAgentConfig.TEAMS, anchor.team(),
				Component::literal, value -> applyAndRebuild(() -> state.applyTeam(value))));
		int thirdY = compact ? y + 50 : y + 125;
		addRenderableWidget(new ConsoleCycleButton<>(font, compact ? right : x, thirdY, columnWidth, ROW_HEIGHT,
				Component.literal("Game mode"), List.of(AgentGameMode.values()), anchor.gameMode(),
				value -> Component.literal(value.displayName()),
				value -> applyAndRebuild(() -> state.applyGameMode(value))));
		nameInput = consoleEditBox(x, compact ? y + 75 : y + 150,
				compact ? width : columnWidth, "roster-agent-name");
		nameInput.setMaxLength(AgentConstants.MAX_USER_NAME_LENGTH);
		nameInput.setValue(anchor.name());
		nameInput.setResponder(value -> state.renameSelected(value));
		addRenderableWidget(nameInput);
	}

	private void addSpeedModeControl(ScenarioAgentConfig anchor, int x, int y, int width) {
		if (AgentControlCatalog.hasSpeedMode(anchor.provider(), anchor.model())) {
			addRenderableWidget(new ConsoleCycleButton<>(font, x, y, width, ROW_HEIGHT,
					Component.literal("Speed mode"), AgentControlCatalog.serviceTiers(anchor.provider(), anchor.model()),
					anchor.serviceTier(), value -> Component.literal(AgentControlPresentation.speedLabel(value)),
					value -> applyAndRebuild(() -> state.applyServiceTier(value))));
			return;
		}
		ConsoleButton unavailable = consoleButton(
				Component.translatable("screen.arenaagents.speed_unavailable"),
				x, y, width, ROW_HEIGHT, false, () -> { }
		);
		unavailable.active = false;
		addRenderableWidget(unavailable);
	}

	private void addFooter(ScenarioSetupLayout layout, boolean previous, boolean primary, String primaryLabel) {
		int left = layout.contentLeft();
		int right = layout.contentRight();
		int buttonWidth = layout.footerButtonWidth();
		if (previous) {
			String previousLabel = state.step() == ScenarioWizardStep.ROSTER ? "Back to arena" : "Back to agents";
			addRenderableWidget(consoleButton(previousLabel.toUpperCase(Locale.ROOT), left, layout.footerY(),
					buttonWidth, ROW_HEIGHT, false, this::goPrevious));
		} else {
			addRenderableWidget(consoleButton("AGENTS", left, layout.footerY(), buttonWidth, ROW_HEIGHT,
					false, this::openAgents));
		}
		addRenderableWidget(consoleButton("CANCEL", right - buttonWidth * 2 - GAP, layout.footerY(),
				buttonWidth, ROW_HEIGHT, false, this::onClose));
		ConsoleButton action = new ConsoleButton(font, right - buttonWidth, layout.footerY(), buttonWidth,
				ROW_HEIGHT, Component.literal(primaryLabel.toUpperCase(Locale.ROOT)), false, GOLD,
				ConsoleButton.Tone.PRIMARY, () -> {
			if (state.step() == ScenarioWizardStep.REVIEW) launch();
			else goNext();
		});
		action.active = primary && (state.step() != ScenarioWizardStep.REVIEW
				|| !launchPending && state.canLaunch() && ScenarioLaunchRegistry.isAvailable());
		addRenderableWidget(action);
	}

	private void renderStepRail(GuiGraphicsExtractor graphics, ScenarioSetupLayout layout) {
		ScenarioWizardStep[] steps = ScenarioWizardStep.values();
		int x = layout.contentLeft();
		int y = layout.stepRailBottom() - (layout.sideNavigation() ? 20 : 14);
		int width = layout.contentWidth();
		int segmentWidth = width / steps.length;
		for (int index = 0; index < steps.length; index++) {
			ScenarioWizardStep step = steps[index];
			int segmentX = x + index * segmentWidth;
			int color = index <= state.step().ordinal() ? GOLD : 0xFF4A4F59;
			graphics.fill(segmentX, y, segmentX + segmentWidth - 3, y + 2, color);
			ConsoleText.centered(graphics, font, (index + 1) + "  " + step.displayName(),
					segmentX + (segmentWidth - 3) / 2, y + 7,
					index == state.step().ordinal() ? TEXT : MUTED);
		}
	}

	private void renderStepContent(GuiGraphicsExtractor graphics, ScenarioSetupLayout layout) {
		int x = layout.contentLeft();
		int width = layout.contentWidth();
		switch (state.step()) {
			case ARENA -> renderArenaDetails(graphics, layout, x, width);
			case ROSTER -> renderRosterBands(graphics, layout, x, width);
			case REVIEW -> renderReview(graphics, layout, x, width);
		}
	}

	private void renderShell(GuiGraphicsExtractor graphics, ScenarioSetupLayout layout) {
		if (layout.sideNavigation()) {
			int navigationRight = layout.contentLeft() - 14;
			graphics.fill(layout.panelLeft(), layout.panelTop(), navigationRight, layout.panelBottom(), 0xFF111820);
			graphics.fill(navigationRight, layout.panelTop(), navigationRight + 1, layout.panelBottom(), PANEL_EDGE);
			graphics.text(font, "ARENA", layout.panelLeft() + 14, layout.panelTop() + 8, GOLD, false);
			graphics.text(font, "AGENTS", layout.panelLeft() + 14, layout.panelTop() + 19, TEXT, false);
			graphics.text(font, "FIELD CONSOLE", layout.panelLeft() + 14, layout.panelTop() + 35, MUTED, false);
			graphics.text(font, "OPERATIONS", layout.panelLeft() + 14, layout.navigationTop() - 15, MUTED, false);
			return;
		}
		graphics.fill(layout.panelLeft(), layout.panelTop(), layout.panelRight(), layout.panelTop() + 37, 0xFF111820);
		graphics.fill(layout.panelLeft(), layout.panelTop() + 37, layout.panelRight(), layout.panelTop() + 38, PANEL_EDGE);
		graphics.text(font, "ARENA AGENTS", layout.panelLeft() + 14, layout.panelTop() + 10, TEXT, false);
	}

	private void renderArenaDetails(GuiGraphicsExtractor graphics, ScenarioSetupLayout layout, int x, int width) {
		ScenarioPreset preset = state.selectedScenario();
		if (layout.compactArenaPicker()) {
			int y = layout.contentTop() + ROW_HEIGHT + 3;
			int bottom = layout.contentBottom() - ROW_HEIGHT - 3;
			graphics.fill(x, y, x + width, bottom, SURFACE_COLOR);
			graphics.text(font, preset.letter() + " | " + fit(preset.title(), width - 34), x + 7, y + 5,
					preset.accentColor(), false);
			graphics.text(font, fit(preset.category() + " | " + preset.duration() + " | "
					+ preset.minimumAgents() + "-" + preset.maximumAgents() + " agents", width - 14),
					x + 7, y + 18, MUTED, false);
			return;
		}
		int y = layout.contentBottom() - 35;
		graphics.fill(x, y, x + width, y + 35, SURFACE_COLOR);
		graphics.text(font, preset.category() + " | " + preset.duration() + " | "
				+ preset.minimumAgents() + "-" + preset.maximumAgents() + " agents", x + 7, y + 5, TEXT, false);
		String description = fit(preset.description(), width - 14);
		graphics.text(font, description, x + 7, y + 19, MUTED, false);
	}

	private void renderRosterBands(GuiGraphicsExtractor graphics, ScenarioSetupLayout layout, int x, int width) {
		// Agent rows render their own complete selected, hover, badge, and metadata states.
	}

	private void renderReview(GuiGraphicsExtractor graphics, ScenarioSetupLayout layout, int x, int width) {
		if (launchPending) {
			renderBuildProgress(graphics, layout, x, width);
			return;
		}
		ScenarioPreset preset = state.selectedScenario();
		int y = layout.contentTop() + ROW_HEIGHT + GAP;
		graphics.fill(x, y, x + width, y + 36, SURFACE_COLOR);
		graphics.text(font, preset.letter() + " | " + preset.title(), x + 8, y + 6, preset.accentColor(), false);
		graphics.text(font, preset.category() + " | " + preset.duration() + " | map " + preset.mapVersion()
				+ " | " + state.placementMode().displayName(),
				x + 8, y + 20, MUTED, false);
		int rosterY = y + 42;
		List<ScenarioAgentConfig> roster = state.roster();
		rosterScroll = Math.clamp(rosterScroll, 0, Math.max(0, roster.size() - visibleRosterRows));
		int visible = Math.min(visibleRosterRows, roster.size() - rosterScroll);
		for (int row = 0; row < visible; row++) {
			int index = rosterScroll + row;
			ScenarioAgentConfig config = roster.get(index);
			int rowY = rosterY + row * 18;
			graphics.fill(x, rowY, x + width, rowY + 16, row % 2 == 0 ? SURFACE_COLOR : SURFACE_SELECTED);
			graphics.text(font, (index + 1) + "  " + state.displayNameAt(index), x + 6, rowY + 4,
					providerColor(config.provider()), false);
			if (width >= 500) {
				graphics.text(font, config.provider() + " | " + config.model() + " | " + config.reasoning()
						+ " | " + AgentControlPresentation.speedLabel(config.serviceTier())
						+ " | " + config.team() + " | " + config.gameMode().displayName(),
						x + Math.min(190, width / 3), rowY + 4, MUTED, false);
			}
		}
	}

	private void renderBuildProgress(
			GuiGraphicsExtractor graphics,
			ScenarioSetupLayout layout,
			int x,
			int width
	) {
		ScenarioBuildProgress progress = AgentControlClient.buildProgressState().progress().orElse(null);
		int y = layout.contentTop();
		int color = progress == null ? GOLD
				: progress.status() == ScenarioBuildProgress.Status.FAILED ? ERROR
				: progress.status() == ScenarioBuildProgress.Status.READY ? SUCCESS : GOLD;
		graphics.fill(x, y, x + width, layout.contentBottom(), SURFACE_COLOR);
		if (progress == null) {
			graphics.text(font, "BUILD REQUEST SENT", x + 12, y + 12, GOLD, false);
			graphics.text(font, "Waiting for the server to publish the build location and first progress update.",
					x + 12, y + 31, TEXT, false);
			return;
		}
		String heading = progress.status() == ScenarioBuildProgress.Status.BUILDING ? "BUILDING ARENA"
				: progress.status() == ScenarioBuildProgress.Status.READY ? "ARENA READY" : "BUILD FAILED";
		graphics.text(font, heading, x + 12, y + 12, color, false);
		graphics.text(font, fit(progress.scenarioTitle(), width - 100), x + 12, y + 27, TEXT, false);
		String percentage = progress.percent() + "%";
		graphics.text(font, percentage, x + width - font.width(percentage) - 12, y + 12, color, false);
		int barLeft = x + 12;
		int barRight = x + width - 12;
		graphics.fill(barLeft, y + 43, barRight, y + 49, 0xFF0D1319);
		graphics.fill(barLeft, y + 43, barLeft + (barRight - barLeft) * progress.percent() / 100, y + 49, color);
		graphics.text(font, progress.humanPhase() + " | " + progress.completed() + " / " + progress.total(),
				x + 12, y + 58, TEXT, false);
		graphics.text(font, "World changes made: " + progress.changedBlocks(), x + 12, y + 72, MUTED, false);
		graphics.text(font, "Build location: " + progress.originLabel(), x + 12, y + 86, MUTED, false);
		if (layout.contentHeight() >= 112) {
			graphics.text(font, fit(progress.detail(), width - 24), x + 12, y + 100, color, false);
		}
	}

	private void renderStatus(GuiGraphicsExtractor graphics, ScenarioSetupLayout layout) {
		String message = feedback;
		int color = feedbackError ? ERROR : SUCCESS;
		if (launchPending) {
			ScenarioBuildProgress progress = AgentControlClient.buildProgressState().progress().orElse(null);
			if (progress != null) {
				message = progress.detail();
				color = progress.status() == ScenarioBuildProgress.Status.FAILED ? ERROR
						: progress.status() == ScenarioBuildProgress.Status.READY ? SUCCESS : GOLD;
			}
		} else if (message.isBlank() && state.step() == ScenarioWizardStep.ROSTER) {
			message = "Configuring agent " + (state.selectedIndex() + 1) + " of " + state.roster().size()
					+ " | Previous/Next changes which agent you edit";
			color = MUTED;
		} else if (message.isBlank() && state.step() == ScenarioWizardStep.REVIEW) {
			if (!ScenarioLaunchRegistry.isAvailable()) {
				message = "Launch blocked: the server has not registered arena launches";
				color = ERROR;
			} else if (!state.validationErrors().isEmpty()) {
				message = "Launch blocked: " + state.validationErrors().getFirst();
				color = ERROR;
			} else {
				message = "Ready | exact model settings preserved | no silent fallback";
				color = SUCCESS;
			}
		}
		if (!message.isBlank()) {
			int y = layout.sideNavigation() ? layout.statusTop() : layout.panelTop() + 22;
			ConsoleText.centered(graphics, font, fit(message, layout.panelWidth() - 28), width / 2, y, color);
		}
	}

	private void openAgents() {
		if (minecraft != null) minecraft.setScreen(new AgentControlScreen(this));
	}

	private void openGroup() {
		if (minecraft != null) minecraft.setScreen(AgentControlScreen.group(this));
	}

	private void openLiveArena() {
		if (minecraft != null) minecraft.setScreen(AgentControlScreen.live(this));
	}

	public void acceptBuildProgress() {
		if (minecraft != null && launchPending) rebuildWidgets();
	}

	private void goPrevious() {
		state.previous();
		setFeedback("", false);
		rebuildWidgets();
	}

	private void goNext() {
		state.next();
		setFeedback("", false);
		rebuildWidgets();
	}

	private void changeCount(int delta) {
		setCount(state.roster().size() + delta);
	}

	private void moveCompactSelection(int direction) {
		int current = state.selectedIndex();
		state.selectOnly(Math.floorMod(current + direction, state.roster().size()));
		setFeedback("Configuring agent " + (state.selectedIndex() + 1) + " of " + state.roster().size()
				+ " | " + state.displayNameAt(state.selectedIndex()), false);
		rebuildWidgets();
	}

	private void setCount(int count) {
		state.setAgentCount(count);
		rosterScroll = Math.min(rosterScroll, Math.max(0, state.roster().size() - visibleRosterRows));
		rebuildWidgets();
	}

	private void launch() {
		ScenarioLaunchRegistry.Result result = ScenarioLaunchRegistry.launch(state.launchPlan());
		setFeedback(result.message(), !result.accepted());
		if (result.accepted()) {
			launchPending = true;
			rebuildWidgets();
		}
	}

	private void applyAndRebuild(Runnable operation) {
		try {
			operation.run();
			setFeedback("Agent " + (state.selectedIndex() + 1) + " of " + state.roster().size()
					+ " updated | " + state.displayNameAt(state.selectedIndex()), false);
		} catch (IllegalArgumentException | IllegalStateException exception) {
			setFeedback(exception.getMessage(), true);
		}
		rebuildWidgets();
	}

	private void setPlacementMode(ScenarioPlacementMode mode) {
		state.setPlacementMode(mode);
		setFeedback(mode.description(), false);
	}

	private ScenarioAgentConfig selectedConfig() {
		return state.roster().get(state.selectedIndex());
	}

	private ConsoleButton consoleButton(
			String label, int x, int y, int width, int height, boolean selected, Runnable action
	) {
		return consoleButton(label, x, y, width, height, selected, GOLD, action);
	}

	private ConsoleEditBox consoleEditBox(int x, int y, int width, String identity) {
		return new ConsoleEditBox(font, x, y, width, ROW_HEIGHT,
				Component.translatable("screen.arenaagents.name"),
				Component.translatable("screen.arenaagents.name_optional"), identity);
	}

	private ConsoleButton consoleButton(
			Component label, int x, int y, int width, int height, boolean selected, Runnable action
	) {
		return new ConsoleButton(font, x, y, width, height, label, selected, GOLD, action);
	}

	private ConsoleButton consoleButton(
			String label, int x, int y, int width, int height, boolean selected, int accent, Runnable action
	) {
		return new ConsoleButton(font, x, y, width, height, Component.literal(label), selected, accent, action);
	}

	private void setFeedback(String message, boolean error) {
		feedback = message == null ? "" : message;
		feedbackError = error;
	}

	private String fit(String value, int available) {
		if (font.width(value) <= available) return value;
		String suffix = "...";
		return font.plainSubstrByWidth(value, Math.max(1, available - font.width(suffix))) + suffix;
	}

	private ScenarioSetupLayout layout() {
		return ScenarioSetupLayout.calculate(width, height);
	}

	private static int providerColor(String provider) {
		return ConsoleTheme.providerColor(provider);
	}

	private static String capitalize(String value) {
		if (value == null || value.isBlank()) return "";
		return Character.toUpperCase(value.charAt(0)) + value.substring(1);
	}
}
