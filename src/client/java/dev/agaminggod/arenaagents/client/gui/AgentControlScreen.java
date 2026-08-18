package dev.agaminggod.arenaagents.client.gui;

import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.client.control.AgentControlClient;
import dev.agaminggod.arenaagents.client.gui.scenario.ScenarioSetupScreen;
import dev.agaminggod.arenaagents.client.gui.widget.ConsoleButton;
import dev.agaminggod.arenaagents.client.gui.widget.ConsoleCycleButton;
import dev.agaminggod.arenaagents.client.gui.widget.ConsoleEditBox;
import dev.agaminggod.arenaagents.client.gui.widget.ConsoleSelectionRow;
import dev.agaminggod.arenaagents.client.presentation.ArenaHudPresentation;
import dev.agaminggod.arenaagents.client.presentation.ArenaSpectatorHud;
import dev.agaminggod.arenaagents.control.AgentControlActions;
import dev.agaminggod.arenaagents.control.AgentControlAgent;
import dev.agaminggod.arenaagents.control.AgentControlCatalog;
import dev.agaminggod.arenaagents.control.AgentControlCommandBuilder;
import dev.agaminggod.arenaagents.control.AgentControlPresentation;
import dev.agaminggod.arenaagents.control.AgentControlSelection;
import dev.agaminggod.arenaagents.control.AgentControlSnapshot;
import dev.agaminggod.arenaagents.scenario.presentation.ArenaSpectatorSnapshot;
import dev.agaminggod.arenaagents.scenario.presentation.ScenarioBuildProgress;
import dev.agaminggod.arenaagents.scenario.result.ScenarioPublicEvent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Human-oriented command center. Detailed workflows live on separate pages. */
public final class AgentControlScreen extends Screen {
	private static final int PANEL = ConsoleTheme.PANEL;
	private static final int PANEL_EDGE = ConsoleTheme.BORDER;
	private static final int BACKDROP = ConsoleTheme.BACKDROP;
	private static final int NAV_SURFACE = ConsoleTheme.NAVIGATION;
	private static final int TRACK = ConsoleTheme.TRACK;
	private static final int SURFACE = ConsoleTheme.SURFACE;
	private static final int SURFACE_SELECTED = ConsoleTheme.SURFACE_SELECTED;
	private static final int TEXT = ConsoleTheme.TEXT;
	private static final int MUTED = ConsoleTheme.MUTED;
	private static final int ACCENT = ConsoleTheme.ACCENT;
	private static final int SUCCESS = ConsoleTheme.SUCCESS;
	private static final int ERROR = ConsoleTheme.ERROR;
	private static final int ROW_HEIGHT = AgentControlLayout.CONTROL_HEIGHT;
	private static final int GAP = 6;

	private AgentControlSnapshot snapshot;
	private final Screen parent;
	private String selectedAgentId = "";
	private final Set<String> groupSelectedAgentIds = new LinkedHashSet<>();
	private String provider;
	private String model;
	private String reasoning;
	private String serviceTier = "priority";
	private AgentGameMode gameMode = AgentGameMode.SURVIVAL;
	private String name = "";
	private String prompt = "";
	private String feedback = "";
	private boolean feedbackError;
	private boolean selectNewlySummonedAgent;
	private int rosterScroll;
	private int liveScroll;
	private Page page = Page.OVERVIEW;
	private ConsoleEditBox nameInput;
	private MultiLineEditBox promptInput;

	public AgentControlScreen() {
		this(null);
	}

	public AgentControlScreen(Screen parent) {
		this(parent, Page.OVERVIEW);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private AgentControlScreen(Screen parent, Page initialPage) {
		super(Minecraft.getInstance(), ConsoleFont.create(Minecraft.getInstance()),
				Component.translatable("screen.arenaagents.controls.title"));
		this.parent = parent;
		this.page = initialPage;
		AgentControlClient.Preferences preferences = AgentControlClient.preferences();
		provider = preferences.provider();
		model = preferences.model();
		if (!AgentControlCatalog.models(provider).contains(model)) model = AgentControlCatalog.defaultModel(provider);
		reasoning = preferences.reasoning();
		if (!AgentControlCatalog.reasoningEfforts(provider, model).contains(reasoning)) {
			reasoning = AgentControlCatalog.defaultReasoning(provider, model);
		}
		snapshot = AgentControlClient.snapshot().orElse(null);
		if (snapshot != null) selectedAgentId = AgentControlSelection.resolve("", snapshot.agents());
	}

	public static AgentControlScreen live(Screen parent) {
		return new AgentControlScreen(parent, Page.LIVE);
	}

	public static AgentControlScreen group(Screen parent) {
		return new AgentControlScreen(parent, Page.GROUP);
	}

	public void acceptSnapshot(AgentControlSnapshot nextSnapshot) {
		AgentControlSnapshot previous = snapshot;
		snapshot = Objects.requireNonNull(nextSnapshot, "nextSnapshot must not be null");
		if (previous != null && snapshot.generatedAtEpochMs() > previous.generatedAtEpochMs() && !feedbackError) {
			feedback = "";
		}
		groupSelectedAgentIds.retainAll(snapshot.agents().stream().map(AgentControlAgent::agentId).toList());
		if (selectNewlySummonedAgent) {
			for (AgentControlAgent agent : snapshot.agents()) {
				boolean existed = previous != null && previous.agents().stream()
						.anyMatch(item -> item.agentId().equals(agent.agentId()));
				if (!existed) {
					selectedAgentId = agent.agentId();
					selectNewlySummonedAgent = false;
					page = Page.OVERVIEW;
					feedback = "";
					break;
				}
			}
		}
		selectedAgentId = AgentControlSelection.resolve(selectedAgentId, snapshot.agents());
		if (minecraft != null && !(getFocused() instanceof EditBox)
				&& !(getFocused() instanceof MultiLineEditBox)) rebuildWidgets();
	}

	@Override
	protected void init() {
		nameInput = null;
		promptInput = null;
		addNavigation();
		switch (page) {
			case OVERVIEW -> initOverview();
			case GROUP -> initGroup();
			case LIVE -> initLive();
			case CREATE -> initCreate();
			case TASK -> initTask();
			case MANAGE -> initManage();
			case REMOVE_CONFIRM -> initRemoveConfirm();
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
		AgentControlLayout layout = layout();
		graphics.fill(0, 0, width, height, BACKDROP);
		graphics.fill(layout.panelLeft() - 1, layout.panelTop() - 1,
				layout.panelRight() + 1, layout.panelBottom() + 1, PANEL_EDGE);
		graphics.fill(layout.panelLeft(), layout.panelTop(), layout.panelRight(), layout.panelBottom(), PANEL);
		renderShell(graphics);
		renderHeader(graphics);
		renderInputSurfaces(graphics);
		switch (page) {
			case OVERVIEW -> renderOverview(graphics);
			case GROUP -> renderGroup(graphics);
			case LIVE -> renderLive(graphics);
			case CREATE -> renderCreate(graphics);
			case TASK -> renderTask(graphics);
			case MANAGE -> renderManage(graphics);
			case REMOVE_CONFIRM -> renderRemoveConfirm(graphics);
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		renderStatus(graphics);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if ((page == Page.OVERVIEW || page == Page.GROUP) && snapshot != null) {
			int visible = visibleRows();
			int maximum = Math.max(0, snapshot.agents().size() - visible);
			int next = Math.clamp(rosterScroll + (verticalAmount > 0.0D ? -1 : 1), 0, maximum);
			if (next != rosterScroll) {
				rosterScroll = next;
				rebuildWidgets();
				return true;
			}
		}
		if (page == Page.LIVE) {
			ArenaSpectatorSnapshot arena = AgentControlClient.spectatorState().snapshot().orElse(null);
			if (arena != null) {
				AgentControlLayout shell = layout();
				LiveArenaLayout live = LiveArenaLayout.calculate(
						shell.contentWidth(), shell.contentHeight(), arena.standings().size());
				int next = Math.clamp(liveScroll + (verticalAmount > 0.0D ? -live.columns() : live.columns()),
						0, live.maximumScroll());
				if (next != liveScroll) {
					liveScroll = next;
					return true;
				}
			}
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	private void addNavigation() {
		AgentControlLayout layout = layout();
		boolean agentWorkflow = page == Page.OVERVIEW || page == Page.CREATE || page == Page.TASK
				|| page == Page.MANAGE || page == Page.REMOVE_CONFIRM;
		if (layout.sideNavigation()) {
			int left = layout.panelLeft() + 10;
			int navigationWidth = layout.contentLeft() - layout.panelLeft() - 24;
			int y = layout.navigationTop();
			addRenderableWidget(consoleButton(Component.translatable("screen.arenaagents.navigation.agents"),
					left, y, navigationWidth, ROW_HEIGHT, agentWorkflow,
					() -> show(Page.OVERVIEW)));
			addRenderableWidget(consoleButton(Component.translatable("screen.arenaagents.navigation.group"),
					left, y + 31, navigationWidth, ROW_HEIGHT, page == Page.GROUP,
					() -> show(Page.GROUP)));
			addRenderableWidget(consoleButton(Component.translatable("screen.arenaagents.navigation.live"),
					left, y + 62, navigationWidth, ROW_HEIGHT, page == Page.LIVE,
					() -> show(Page.LIVE)));
			addRenderableWidget(consoleButton(Component.translatable("screen.arenaagents.navigation.build"),
					left, y + 93, navigationWidth, ROW_HEIGHT, false,
					this::openArenaSetup));
			return;
		}
		String[] labels = {"AGENTS", "GROUP", "LIVE", "BUILD"};
		boolean[] selected = {agentWorkflow, page == Page.GROUP, page == Page.LIVE, false};
		Runnable[] actions = {() -> show(Page.OVERVIEW), () -> show(Page.GROUP), () -> show(Page.LIVE), this::openArenaSetup};
		int available = layout.contentWidth() - GAP * 3;
		int buttonWidth = available / 4;
		for (int index = 0; index < labels.length; index++) {
			int x = layout.contentLeft() + index * (buttonWidth + GAP);
			int actualWidth = index == labels.length - 1 ? layout.contentRight() - x : buttonWidth;
			addRenderableWidget(consoleButton(labels[index], x, layout.navigationTop(), actualWidth, ROW_HEIGHT,
					selected[index], actions[index]));
		}
	}

	private void openArenaSetup() {
		if (minecraft == null) return;
		if (parent instanceof ScenarioSetupScreen setup) minecraft.setScreen(setup);
		else minecraft.setScreen(new ScenarioSetupScreen());
	}

	@Override
	public void onClose() {
		if (minecraft != null && parent != null) minecraft.setScreen(parent);
		else super.onClose();
	}

	private void initOverview() {
		AgentControlLayout layout = layout();
		int top = layout.contentTop() + 24;
		if (layout.splitWorkspace()) {
			addAgentRows(layout.canvasLeft(), top, layout.canvasWidth());
			addOverviewActions(layout.contextLeft(), top, layout.contextWidth());
		} else {
			addAgentRows(layout.canvasLeft(), top, layout.canvasWidth());
			addCompactOverviewActions(layout.contentLeft(), layout.contentBottom() - ROW_HEIGHT, layout.contentWidth());
		}
		addOverviewFooter();
	}

	private void addAgentRows(int x, int y, int width) {
		if (snapshot == null) return;
		List<AgentControlAgent> agents = snapshot.agents();
		int visible = visibleRows();
		rosterScroll = Math.clamp(rosterScroll, 0, Math.max(0, agents.size() - visible));
		for (int row = 0; row < Math.min(visible, agents.size() - rosterScroll); row++) {
			AgentControlAgent agent = agents.get(rosterScroll + row);
			boolean selected = agent.agentId().equals(selectedAgentId);
			ConsoleSelectionRow button = new ConsoleSelectionRow(
					font, x, y + row * 43, width, 38,
					Component.literal(initials(agentDisplayName(agent))),
					Component.literal(agentDisplayName(agent)),
					Component.literal(capitalize(agent.provider()) + "  |  " + AgentControlPresentation.profileLabel(agent)),
					Component.literal(AgentControlPresentation.stateLabel(agent.state())),
					agent.agentId(),
					selected, false, providerColor(agent.provider()), () -> {
				selectedAgentId = agent.agentId();
				rebuildWidgets();
			});
			addRenderableWidget(button);
		}
	}

	private void initGroup() {
		AgentControlLayout layout = layout();
		int top = layout.contentTop() + (layout.sideNavigation() ? 24 : 0);
		boolean columns = layout.splitWorkspace() || layout.contentWidth() >= 500;
		if (columns) {
			int listX = layout.canvasLeft();
			int listWidth = layout.splitWorkspace()
					? layout.canvasWidth()
					: (layout.contentWidth() - 12) * 45 / 100;
			int controlX = layout.splitWorkspace()
					? layout.contextLeft()
					: listX + listWidth + 12;
			int controlWidth = layout.contentRight() - controlX;
			addGroupRows(listX, top, listWidth);
			promptInput = multiLineInput(controlX, top, controlWidth, 66,
					"Group task", "Describe one shared outcome for the selected agents");
			addRenderableWidget(promptInput);
			addVerticalGroupActions(controlX, top + 74, controlWidth);
		} else {
			addGroupRows(layout.contentLeft(), top, layout.contentWidth());
			int promptY = top + 43;
			int promptHeight = Math.max(30, layout.contentBottom() - promptY - ROW_HEIGHT - GAP);
			promptInput = multiLineInput(layout.contentLeft(), promptY, layout.contentWidth(), promptHeight,
					"Group task", "Tell the selected group what to do");
			addRenderableWidget(promptInput);
			addHorizontalGroupActions(layout.contentLeft(), layout.contentBottom() - ROW_HEIGHT, layout.contentWidth());
		}
		addOverviewFooter();
	}

	private void addVerticalGroupActions(int x, int y, int width) {
		List<AgentControlAgent> selected = selectedGroupAgents();
		for (int index = 0; index < 3; index++) {
			String operation = List.of("start", "queue", "steer").get(index);
			String label = List.of("START NOW", "ADD TO QUEUE", "ADJUST TASK").get(index);
			ConsoleButton action = index == 0
					? primaryButton(label, x, y + index * 31, width, ROW_HEIGHT, () -> submitGroupPrompt(operation))
					: consoleButton(label, x, y + index * 31, width, ROW_HEIGHT, false,
							() -> submitGroupPrompt(operation));
			action.active = canUseAutomation() && AgentControlActions.everySupports(selected, operation);
			addRenderableWidget(action);
		}
		addRenderableWidget(consoleButton("CLEAR SELECTION", x, y + 93, width, ROW_HEIGHT, false,
				this::clearGroupSelection));
	}

	private void addHorizontalGroupActions(int x, int y, int width) {
		List<AgentControlAgent> selected = selectedGroupAgents();
		String[] operations = {"start", "queue", "steer"};
		String[] labels = {"START", "QUEUE", "ADJUST", "CLEAR"};
		int buttonWidth = (width - GAP * 3) / 4;
		for (int index = 0; index < labels.length; index++) {
			int buttonX = x + index * (buttonWidth + GAP);
			int actualWidth = index == labels.length - 1 ? x + width - buttonX : buttonWidth;
			if (index == labels.length - 1) {
				addRenderableWidget(consoleButton(labels[index], buttonX, y, actualWidth, ROW_HEIGHT, false,
						this::clearGroupSelection));
				continue;
			}
			String operation = operations[index];
			ConsoleButton action = index == 0
					? primaryButton(labels[index], buttonX, y, actualWidth, ROW_HEIGHT,
							() -> submitGroupPrompt(operation))
					: consoleButton(labels[index], buttonX, y, actualWidth, ROW_HEIGHT, false,
							() -> submitGroupPrompt(operation));
			action.active = canUseAutomation() && AgentControlActions.everySupports(selected, operation);
			addRenderableWidget(action);
		}
	}

	private void clearGroupSelection() {
		groupSelectedAgentIds.clear();
		rebuildWidgets();
	}

	private void addGroupRows(int x, int y, int width) {
		if (snapshot == null) return;
		List<AgentControlAgent> agents = snapshot.agents();
		int visible = visibleRows();
		rosterScroll = Math.clamp(rosterScroll, 0, Math.max(0, agents.size() - visible));
		for (int row = 0; row < Math.min(visible, agents.size() - rosterScroll); row++) {
			AgentControlAgent agent = agents.get(rosterScroll + row);
			boolean selected = groupSelectedAgentIds.contains(agent.agentId());
			addRenderableWidget(new ConsoleSelectionRow(
					font, x, y + row * 43, width, 38,
					Component.literal(initials(agentDisplayName(agent))),
					Component.literal(agentDisplayName(agent)),
					Component.literal(AgentControlPresentation.profileLabel(agent)),
					Component.literal(AgentControlPresentation.stateLabel(agent.state())),
					agent.agentId(),
					selected, true, providerColor(agent.provider()), () -> {
				if (!groupSelectedAgentIds.add(agent.agentId())) groupSelectedAgentIds.remove(agent.agentId());
				rebuildWidgets();
			}));
		}
	}

	private void addOverviewActions(int x, int y, int width) {
		ConsoleButton create = primaryButton("CREATE AN AGENT", x, y, width, ROW_HEIGHT, () -> show(Page.CREATE));
		create.active = canControl();
		addRenderableWidget(create);
		ConsoleButton task = consoleButton("GIVE A TASK", x, y + 32, width, 24, false, () -> show(Page.TASK));
		task.active = canUseAutomation() && selectedAgent() != null;
		addRenderableWidget(task);
		ConsoleButton manage = consoleButton("MANAGE SELECTED AGENT", x, y + 64, width, 24, false,
				() -> show(Page.MANAGE));
		manage.active = canControl() && selectedAgent() != null;
		addRenderableWidget(manage);
	}

	private void addCompactOverviewActions(int x, int y, int width) {
		int buttonWidth = (width - GAP * 2) / 3;
		ConsoleButton create = primaryButton("CREATE", x, y, buttonWidth, ROW_HEIGHT, () -> show(Page.CREATE));
		create.active = canControl();
		addRenderableWidget(create);
		ConsoleButton task = consoleButton("GIVE TASK", x + buttonWidth + GAP, y, buttonWidth, ROW_HEIGHT,
				false, () -> show(Page.TASK));
		task.active = canUseAutomation() && selectedAgent() != null;
		addRenderableWidget(task);
		ConsoleButton manage = consoleButton("MANAGE", x + (buttonWidth + GAP) * 2, y,
				width - (buttonWidth + GAP) * 2, ROW_HEIGHT, false, () -> show(Page.MANAGE));
		manage.active = canControl() && selectedAgent() != null;
		addRenderableWidget(manage);
	}

	private void initLive() {
		int y = layout().footerY();
		addRenderableWidget(consoleButton("REFRESH AGENTS", contentLeft(), y, 118, ROW_HEIGHT, false,
				AgentControlClient::requestSnapshot));
		addRenderableWidget(consoleButton("CLOSE", contentRight() - 96, y, 96, ROW_HEIGHT, false, this::onClose));
	}

	private void initCreate() {
		AgentControlLayout layout = layout();
		int x = layout.contentLeft();
		int width = layout.contentWidth();
		int half = (width - GAP) / 2;
		int y = layout.contentTop() + (layout.sideNavigation() ? 22 : 0);
		addRenderableWidget(new ConsoleCycleButton<>(font, x, y, half, ROW_HEIGHT, Component.literal("Provider"),
				AgentControlCatalog.providers(), provider, value -> Component.literal(capitalize(value)), value -> {
					provider = value;
					model = AgentControlCatalog.defaultModel(provider);
					reasoning = AgentControlCatalog.defaultReasoning(provider, model);
					rememberPreferences();
					rebuildWidgets();
				}));
		addRenderableWidget(new ConsoleCycleButton<>(font, x + half + GAP, y, half, ROW_HEIGHT,
				Component.literal("Model"), AgentControlCatalog.models(provider), model,
				value -> Component.literal(AgentControlCatalog.displayName(provider, value)), value -> {
					model = value;
					reasoning = AgentControlCatalog.defaultReasoning(provider, model);
					rememberPreferences();
					rebuildWidgets();
				}));
		y += 31;
		addRenderableWidget(new ConsoleCycleButton<>(font, x, y, half, ROW_HEIGHT,
				Component.literal("Thinking depth"), AgentControlCatalog.reasoningEfforts(provider, model), reasoning,
				value -> Component.literal(capitalize(value)), value -> {
					reasoning = value;
					rememberPreferences();
				}));
		if (!AgentControlCatalog.serviceTiers(provider, model).contains(serviceTier)) serviceTier = "priority";
		if (AgentControlCatalog.hasSpeedMode(provider, model)) {
			addRenderableWidget(new ConsoleCycleButton<>(font, x + half + GAP, y, half, ROW_HEIGHT,
					Component.literal("Speed mode"), AgentControlCatalog.serviceTiers(provider, model), serviceTier,
					value -> Component.literal(AgentControlPresentation.speedLabel(value)),
					value -> serviceTier = value));
		} else {
			ConsoleButton unavailableSpeed = consoleButton(Component.translatable("screen.arenaagents.speed_unavailable"), x + half + GAP, y,
					half, ROW_HEIGHT, false, () -> { });
			unavailableSpeed.active = false;
			addRenderableWidget(unavailableSpeed);
		}
		y += 31;
		addRenderableWidget(new ConsoleCycleButton<>(font, x, y, half, ROW_HEIGHT,
				Component.literal("Game mode"), List.of(AgentGameMode.values()), gameMode,
				value -> Component.literal(value.displayName()), value -> gameMode = value));
		nameInput = consoleEditBox(x + half + GAP, y, half, ROW_HEIGHT,
				"agent-name", Component.translatable("screen.arenaagents.name_optional"));
		nameInput.setMaxLength(AgentConstants.MAX_USER_NAME_LENGTH);
		nameInput.setValue(name);
		nameInput.setResponder(value -> name = value);
		addRenderableWidget(nameInput);
		addCreateFooter();
	}

	private void initTask() {
		AgentControlAgent agent = selectedAgent();
		if (agent == null) {
			show(Page.OVERVIEW);
			return;
		}
		AgentControlLayout layout = layout();
		int x = layout.contentLeft();
		int width = layout.contentWidth();
		int promptY = layout.contentTop() + (layout.sideNavigation() ? 28 : 0);
		int promptHeight = Math.max(40, layout.contentBottom() - promptY - ROW_HEIGHT - 7);
		promptInput = multiLineInput(x, promptY, width, promptHeight, "Agent task",
				"Describe a clear outcome, for example: Build a safe shelter before night");
		addRenderableWidget(promptInput);
		int buttonWidth = (width - GAP * 2) / 3;
		int actionY = layout.contentBottom() - ROW_HEIGHT;
		for (int index = 0; index < 3; index++) {
			String operation = List.of("start", "queue", "steer").get(index);
			String label = List.of("Start now", "Add to queue", "Adjust current task").get(index);
			ConsoleButton action = index == 0
					? primaryButton(label.toUpperCase(Locale.ROOT),
							x + index * (buttonWidth + GAP), actionY, buttonWidth, ROW_HEIGHT,
							() -> submitPrompt(operation))
					: consoleButton(label.toUpperCase(Locale.ROOT),
							x + index * (buttonWidth + GAP), actionY, buttonWidth, ROW_HEIGHT, false,
							() -> submitPrompt(operation));
			action.active = canUseAutomation() && AgentControlActions.supports(agent, operation);
			addRenderableWidget(action);
		}
		addBackFooter();
	}

	private void initRemoveConfirm() {
		AgentControlAgent agent = selectedAgent();
		if (agent == null) {
			show(Page.OVERVIEW);
			return;
		}
		AgentControlLayout layout = layout();
		int buttonWidth = Math.min(148, (layout.contentWidth() - GAP) / 2);
		int y = layout.footerY();
		addRenderableWidget(consoleButton("KEEP AGENT", layout.contentLeft(), y, buttonWidth, ROW_HEIGHT,
				false, () -> show(Page.MANAGE)));
		ConsoleButton remove = dangerButton("REMOVE PERMANENTLY", layout.contentRight() - buttonWidth, y,
				buttonWidth, ROW_HEIGHT, this::submitConfirmedRemove);
		remove.active = canControl();
		addRenderableWidget(remove);
	}

	private void initManage() {
		AgentControlAgent agent = selectedAgent();
		if (agent == null) {
			show(Page.OVERVIEW);
			return;
		}
		AgentControlLayout layout = layout();
		int x = layout.contentLeft();
		int width = layout.contentWidth();
		int top = layout.contentTop() + (layout.sideNavigation() ? 28 : 0);
		int buttonWidth = (width - GAP) / 2;
		for (int index = 0; index < 2; index++) {
			String operation = List.of("stop", "resume").get(index);
			String label = List.of("Pause work", "Resume work").get(index);
			ConsoleButton action = consoleButton(label.toUpperCase(Locale.ROOT),
					x + index * (buttonWidth + GAP), top, buttonWidth, ROW_HEIGHT, false,
					() -> submitAgentOperation(operation));
			action.active = canControl()
					&& (!operation.equals("resume") || canUseAutomation())
					&& AgentControlActions.supports(agent, operation);
			addRenderableWidget(action);
		}
		ConsoleButton automatic = consoleButton(
				agent.automaticProgress() ? "AUTOMATIC PROGRESS: ON" : "AUTOMATIC PROGRESS: OFF",
				x, top + 32, (width - GAP) / 2, ROW_HEIGHT, agent.automaticProgress(),
				() -> submitAgentOperation("auto"));
		automatic.active = canControl();
		addRenderableWidget(automatic);
		ConsoleButton remove = dangerButton("REMOVE AGENT...", x + (width - GAP) / 2 + GAP, top + 32,
				(width - GAP) / 2, ROW_HEIGHT, this::confirmRemove);
		remove.active = canControl();
		addRenderableWidget(remove);
		addBackFooter();
	}

	private void addOverviewFooter() {
		int y = layout().footerY();
		int x = contentLeft();
		addRenderableWidget(consoleButton("REFRESH", x, y, 96, ROW_HEIGHT, false,
				AgentControlClient::requestSnapshot));
		addRenderableWidget(consoleButton("CLOSE", contentRight() - 96, y, 96, ROW_HEIGHT, false, this::onClose));
	}

	private void addCreateFooter() {
		int y = layout().footerY();
		addRenderableWidget(consoleButton("CANCEL", contentLeft(), y, 96, ROW_HEIGHT, false,
				() -> show(Page.OVERVIEW)));
		addRenderableWidget(primaryButton("CREATE AGENT", contentRight() - 130, y, 130, ROW_HEIGHT,
				this::submitSummon));
	}

	private void addBackFooter() {
		int y = layout().footerY();
		addRenderableWidget(consoleButton("BACK TO AGENTS", contentLeft(), y, 130, ROW_HEIGHT, false,
				() -> show(Page.OVERVIEW)));
	}

	private void renderShell(GuiGraphicsExtractor graphics) {
		AgentControlLayout layout = layout();
		int left = layout.panelLeft();
		if (layout.sideNavigation()) {
			int navigationRight = layout.contentLeft() - 14;
			graphics.fill(left, layout.panelTop(), navigationRight, layout.panelBottom(), NAV_SURFACE);
			graphics.fill(navigationRight, layout.panelTop(), navigationRight + 1, layout.panelBottom(), PANEL_EDGE);
			graphics.fill(layout.contentLeft(), layout.panelTop() + 31, layout.panelRight(), layout.panelTop() + 32, PANEL_EDGE);
			graphics.text(font, "ARENA", left + 14, layout.panelTop() + 8, ACCENT, false);
			graphics.text(font, "AGENTS", left + 14, layout.panelTop() + 19, TEXT, false);
			graphics.text(font, "FIELD CONSOLE", left + 14, layout.panelTop() + 35, MUTED, false);
			graphics.text(font, "OPERATIONS", left + 14, layout.navigationTop() - 15, MUTED, false);
		} else {
			graphics.fill(left, layout.panelTop(), layout.panelRight(), layout.panelTop() + 37, NAV_SURFACE);
			graphics.fill(left, layout.panelTop() + 37, layout.panelRight(), layout.panelTop() + 38, PANEL_EDGE);
			graphics.text(font, "ARENA AGENTS", left + 14, layout.panelTop() + 10, TEXT, false);
			graphics.text(font, "FIELD CONSOLE", left + 14, layout.panelTop() + 22, MUTED, false);
		}
		String connection = snapshot == null ? "SYNCING" : snapshot.canControl() ? "LINK ONLINE" : "VIEW ONLY";
		int connectionColor = snapshot != null && snapshot.canControl() ? SUCCESS : ACCENT;
		if (layout.sideNavigation()) {
			int navigationRight = layout.contentLeft() - 14;
			graphics.fill(left + 10, layout.panelBottom() - 50, navigationRight - 10, layout.panelBottom() - 24, TRACK);
			graphics.fill(left + 16, layout.panelBottom() - 42, left + 21, layout.panelBottom() - 37, connectionColor);
			graphics.text(font, connection, left + 27, layout.panelBottom() - 44, connectionColor, false);
			graphics.text(font, AgentControlClient.openControlKeyLabel() + "  open console",
					left + 16, layout.panelBottom() - 32, MUTED, false);
		} else {
			int textX = layout.panelRight() - font.width(connection) - 14;
			graphics.fill(textX - 10, layout.panelTop() + 10, textX - 5, layout.panelTop() + 15, connectionColor);
			graphics.text(font, connection, textX, layout.panelTop() + 8, connectionColor, false);
		}
	}

	private void renderHeader(GuiGraphicsExtractor graphics) {
		AgentControlLayout layout = layout();
		if (!layout.sideNavigation() || page == Page.GROUP) return;
		String heading = switch (page) {
			case OVERVIEW -> "Your agents";
			case GROUP -> throw new IllegalStateException("Group page uses its dedicated header");
			case LIVE -> "Live arena";
			case CREATE -> "Create an agent";
			case TASK -> selectedAgent() == null ? "Give a task" : "Give " + agentDisplayName(selectedAgent()) + " a task";
			case MANAGE -> selectedAgent() == null ? "Manage agent" : "Manage " + agentDisplayName(selectedAgent());
			case REMOVE_CONFIRM -> "Confirm agent removal";
		};
		graphics.text(font, heading, contentLeft(), layout.panelTop() + 9, TEXT, false);
		if (snapshot != null) {
			String automation = fit(snapshot.automationStatus(), Math.max(80, contentWidth() / 2));
			graphics.text(font, automation, contentRight() - font.width(automation), layout.panelTop() + 9,
					snapshot.automationAvailable() ? SUCCESS : ERROR, false);
		}
	}

	private void renderGroup(GuiGraphicsExtractor graphics) {
		AgentControlLayout layout = layout();
		if (!layout.sideNavigation()) return;
		int count = groupSelectedAgentIds.size();
		graphics.text(font, "Group prompt", contentLeft(), layout.panelTop() + 9, TEXT, false);
		graphics.text(font, count + (count == 1 ? " agent selected" : " agents selected"), contentLeft(), layout.contentTop() + 4,
				count == 0 ? MUTED : ACCENT, false);
		if (contentWidth() >= 560) {
			graphics.text(font, "Group selection changes messaging only. Configure agents one at a time.",
					contentLeft() + 126, layout.contentTop() + 4, MUTED, false);
		}
		if (snapshot == null || snapshot.agents().isEmpty()) {
			graphics.text(font, "Create agents before building a control group.", contentLeft(), layout.contentTop() + 28, MUTED, false);
		}
	}

	private void renderLive(GuiGraphicsExtractor graphics) {
		ArenaSpectatorSnapshot arena = AgentControlClient.spectatorState().snapshot().orElse(null);
		ScenarioBuildProgress build = AgentControlClient.buildProgressState().progress().orElse(null);
		if (AgentControlClient.buildProgressState().shouldDisplayBeforeMatch(arena != null)) {
			renderBuildProgress(graphics, build, true);
			return;
		}
		if (arena == null) {
			if (build == null) renderEmptyLiveArena(graphics);
			else renderBuildProgress(graphics, build, true);
			return;
		}
		AgentControlLayout layout = layout();
		int left = contentLeft();
		int available = contentWidth();
		int top = layout.contentTop();
		graphics.fill(left, top, contentRight(), top + 36, SURFACE);
		String clock = ArenaHudPresentation.timeLabel(arena.elapsedTick(), arena.durationTicks(), arena.terminal());
		graphics.text(font, fit(arena.scenarioTitle(), Math.max(60, available - font.width(clock) - 30)),
				left + 10, top + 7, TEXT, false);
		graphics.text(font, clock, contentRight() - font.width(clock) - 10, top + 7, TEXT, false);
		LiveArenaLayout live = LiveArenaLayout.calculate(available, layout.contentHeight(), arena.standings().size());
		liveScroll = Math.clamp(liveScroll, 0, live.maximumScroll());
		int end = Math.min(arena.standings().size(), liveScroll + live.visibleCount());
		String range = arena.standings().isEmpty() ? "NO AGENTS"
				: "AGENTS " + (liveScroll + 1) + "-" + end + " OF " + arena.standings().size()
				+ (live.maximumScroll() > 0 ? " | SCROLL" : "");
		String displayedRange = fit(range, Math.max(90, available / 2));
		graphics.text(font, fit(ArenaHudPresentation.statusLabel(arena.phaseTitle()),
				Math.max(40, available - font.width(displayedRange) - 30)), left + 10, top + 20, ACCENT, false);
		graphics.text(font, displayedRange, contentRight() - font.width(displayedRange) - 10, top + 20,
				live.maximumScroll() > 0 ? ACCENT : MUTED, false);
		int progressLeft = contentRight() - 180;
		int progressRight = contentRight() - 10;
		graphics.fill(progressLeft, top + 31, progressRight, top + 34, TRACK);
		int progress = (int) Math.round((progressRight - progressLeft)
				* Math.min(1.0D, (double) arena.elapsedTick() / arena.durationTicks()));
		graphics.fill(progressLeft, top + 31, progressLeft + progress, top + 34, ACCENT);

		int gridTop = top + live.gridTopOffset();
		for (int sourceIndex = liveScroll; sourceIndex < end; sourceIndex++) {
			int visibleIndex = sourceIndex - liveScroll;
			int column = visibleIndex % live.columns();
			int row = visibleIndex / live.columns();
			renderLiveStanding(graphics, arena.standings().get(sourceIndex),
					left + column * (live.cardWidth() + LiveArenaLayout.COLUMN_GAP),
					gridTop + row * (live.cardHeight() + LiveArenaLayout.ROW_GAP),
					live.cardWidth(), live.cardHeight());
		}
		int shown = end - liveScroll;
		int rows = (shown + live.columns() - 1) / live.columns();
		int feedTop = gridTop + rows * (live.cardHeight() + LiveArenaLayout.ROW_GAP) + 2;
		if (feedTop < layout.contentBottom() - 30) renderActivityFeed(graphics, arena.feed(), left, feedTop, available);
	}

	private void renderBuildProgress(GuiGraphicsExtractor graphics, ScenarioBuildProgress build, boolean detailed) {
		AgentControlLayout layout = layout();
		int left = contentLeft();
		int top = layout.contentTop();
		int right = contentRight();
		int color = build.status() == ScenarioBuildProgress.Status.FAILED ? ERROR
				: build.status() == ScenarioBuildProgress.Status.READY ? SUCCESS : ACCENT;
		graphics.fill(left, top, right, top + (detailed ? 104 : 38), SURFACE);
		graphics.text(font, build.status() == ScenarioBuildProgress.Status.BUILDING ? "BUILDING ARENA"
				: build.status() == ScenarioBuildProgress.Status.READY ? "ARENA READY" : "BUILD FAILED",
				left + 12, top + 10, color, false);
		graphics.text(font, fit(build.scenarioTitle(), Math.max(40, right - left - 170)), left + 12, top + 24, TEXT, false);
		String percentage = build.percent() + "%";
		graphics.text(font, percentage, right - font.width(percentage) - 12, top + 10, color, false);
		int barLeft = left + 12;
		int barRight = right - 12;
		graphics.fill(barLeft, top + 42, barRight, top + 47, TRACK);
		graphics.fill(barLeft, top + 42, barLeft + (barRight - barLeft) * build.percent() / 100, top + 47, color);
		if (!detailed) return;
		graphics.text(font, build.humanPhase() + " | " + build.completed() + " / " + build.total(),
				left + 12, top + 55, TEXT, false);
		graphics.text(font, "World changes made: " + build.changedBlocks(), left + 12, top + 69, MUTED, false);
		graphics.text(font, "Location: " + build.originLabel(), left + 12, top + 83, MUTED, false);
		graphics.text(font, fit(build.detail(), right - left - 24), left + 12, top + 95, color, false);
	}

	private void renderEmptyLiveArena(GuiGraphicsExtractor graphics) {
		int left = contentLeft();
		int top = layout().contentTop();
		graphics.fill(left, top, contentRight(), top + 86, SURFACE);
		graphics.text(font, "NO ACTIVE MATCH", left + 14, top + 14, ACCENT, false);
		graphics.text(font, "Health, score, state, timer and match activity will appear here.",
				left + 14, top + 35, TEXT, false);
		graphics.text(font, "Use Build Arena to prepare a deterministic showcase and dedicated spawn stations.",
				left + 14, top + 52, MUTED, false);
	}

	private void renderLiveStanding(
			GuiGraphicsExtractor graphics,
			ArenaSpectatorSnapshot.Standing standing,
			int left,
			int top,
			int width,
			int height
	) {
		int provider = ArenaSpectatorHud.providerColor(standing.providerFamily());
		graphics.fill(left, top, left + width, top + height, PANEL_EDGE);
		graphics.fill(left + 1, top + 1, left + width - 1, top + height - 1, SURFACE);
		graphics.fill(left + 7, top + 8, left + 31, top + 32, provider);
		graphics.fill(left + 8, top + 9, left + 30, top + 31, TRACK);
		ConsoleText.centered(graphics, font, Component.literal("#" + standing.rank()), left + 19, top + 16, provider);
		int textLeft = left + 39;
		graphics.text(font, fit(standing.displayName(), width - 150), textLeft, top + 7, TEXT, false);
		String score = "SCORE " + ArenaSpectatorHud.scoreText(standing.score());
		graphics.text(font, score, left + width - font.width(score) - 8, top + 7, provider, false);
		graphics.text(font, capitalize(standing.providerFamily()) + "  /  "
				+ ArenaHudPresentation.statusLabel(standing.status()), textLeft, top + 20, MUTED, false);
		String healthLabel = "HP " + standing.healthPercent() + "%";
		int barLeft = textLeft;
		int barRight = left + width - font.width(healthLabel) - 12;
		graphics.fill(barLeft, top + 36, barRight, top + 41, TRACK);
		graphics.fill(barLeft, top + 36,
				barLeft + (barRight - barLeft) * standing.healthPercent() / 100, top + 41,
				ArenaHudPresentation.healthColor(standing.healthPercent()));
		graphics.text(font, healthLabel, barRight + 6, top + 34,
				ArenaHudPresentation.healthColor(standing.healthPercent()), false);
	}

	private void renderActivityFeed(
			GuiGraphicsExtractor graphics,
			List<ScenarioPublicEvent> feed,
			int left,
			int top,
			int width
	) {
		graphics.text(font, "RECENT MATCH ACTIVITY", left, top, MUTED, false);
		int y = top + 14;
		if (feed.isEmpty()) {
			graphics.text(font, "Waiting for the first scored action.", left, y, MUTED, false);
			return;
		}
		for (ScenarioPublicEvent event : feed.reversed()) {
			if (y > layout().contentBottom() - 17) break;
			graphics.fill(left, y, left + width, y + 17, TRACK);
			graphics.text(font, fit(event.message(), width - 82), left + 7, y + 4, TEXT, false);
			String state = ArenaHudPresentation.statusLabel(event.state());
			graphics.text(font, state, left + width - font.width(state) - 7, y + 4, MUTED, false);
			y += 20;
		}
	}

	private void renderOverview(GuiGraphicsExtractor graphics) {
		AgentControlLayout layout = layout();
		if (snapshot == null) {
			graphics.text(font, "Loading your agents...", contentLeft(), layout.contentTop() + 27, MUTED, false);
			return;
		}
		if (snapshot.agents().isEmpty()) {
			graphics.text(font, "No agents yet", contentLeft(), layout.contentTop() + 27, TEXT, false);
			graphics.text(font, "Create one, choose its model, then give it a clear task.", contentLeft(), layout.contentTop() + 43, MUTED, false);
			return;
		}
		AgentControlAgent selected = selectedAgent();
		if (layout.splitWorkspace() && selected != null) {
			int x = layout.contextLeft();
			int detailTop = layout.contentTop() + 126;
			graphics.text(font, "SELECTED AGENT", x, detailTop, MUTED, false);
			graphics.text(font, agentDisplayName(selected), x, detailTop + 17, TEXT, false);
			graphics.text(font, AgentControlPresentation.profileLabel(selected), x, detailTop + 31, MUTED, false);
			graphics.text(font, AgentControlPresentation.stateLabel(selected.state()), x, detailTop + 45,
					stateColor(selected.state()), false);
			String goal = selected.currentGoal().isBlank() ? "No current task" : "Task: " + selected.currentGoal();
			graphics.textWithWordWrap(font, Component.literal(goal), x, detailTop + 64,
					contentRight() - x, MUTED);
			if (!selected.lastError().isBlank()) {
				graphics.textWithWordWrap(font, Component.literal("Needs attention: " + selected.lastError()), x, detailTop + 104,
						contentRight() - x, ERROR);
			}
		}
	}

	private void renderCreate(GuiGraphicsExtractor graphics) {
		AgentControlLayout layout = layout();
		if (!layout.sideNavigation()) return;
		graphics.text(font, "Configure one agent. Use Tab to move and Left or Right to change a value.",
				contentLeft(), layout.contentTop() + 4, MUTED, false);
	}

	private void renderTask(GuiGraphicsExtractor graphics) {
		AgentControlAgent agent = selectedAgent();
		if (agent == null) return;
		AgentControlLayout layout = layout();
		if (layout.sideNavigation()) {
			graphics.text(font, "Current status: " + AgentControlPresentation.stateLabel(agent.state()), contentLeft(), layout.contentTop() + 4, MUTED, false);
			graphics.text(font, "Write one clear outcome. Follow-up work can be queued later.", contentLeft(), layout.contentTop() + 16, MUTED, false);
		}
		if (!canUseAutomation()) {
			graphics.text(font, "Tasks are disabled until automation is ready.", contentLeft(), layout.contentBottom() - 38, ERROR, false);
		}
	}

	private void renderManage(GuiGraphicsExtractor graphics) {
		AgentControlAgent agent = selectedAgent();
		if (agent == null) return;
		AgentControlLayout layout = layout();
		if (!layout.sideNavigation()) return;
		graphics.text(font, AgentControlPresentation.profileLabel(agent), contentLeft(), layout.contentTop() + 4, MUTED, false);
		graphics.text(font, "Status: " + AgentControlPresentation.stateLabel(agent.state()), contentLeft(), layout.contentTop() + 16,
				stateColor(agent.state()), false);
		graphics.text(font, "Routine controls are separated here so creating and assigning agents stays simple.",
				contentLeft(), layout.contentTop() + 94, MUTED, false);
	}

	private void renderRemoveConfirm(GuiGraphicsExtractor graphics) {
		AgentControlAgent agent = selectedAgent();
		if (agent == null) return;
		AgentControlLayout layout = layout();
		int left = layout.contentLeft();
		int top = layout.contentTop() + (layout.sideNavigation() ? 22 : 0);
		int right = layout.contentRight();
		int bottom = Math.min(layout.contentBottom(), top + 104);
		graphics.fill(left, top, right, bottom, ConsoleTheme.ERROR);
		graphics.fill(left + 2, top + 2, right - 2, bottom - 2, ConsoleTheme.SURFACE);
		graphics.text(font, "REMOVE " + fit(agentDisplayName(agent).toUpperCase(Locale.ROOT), right - left - 34),
				left + 14, top + 14, ERROR, false);
		graphics.text(font, "This deletes the agent and its saved state.", left + 14, top + 37, TEXT, false);
		graphics.text(font, "This cannot be undone from the Field Console.", left + 14, top + 53, MUTED, false);
		graphics.text(font, "Choose Keep Agent to return without changing anything.", left + 14, top + 75,
				MUTED, false);
	}

	private void renderInputSurfaces(GuiGraphicsExtractor graphics) {
		if (promptInput == null) return;
		int outline = promptInput.isFocused() ? ConsoleTheme.FOCUS
				: promptInput.isHovered() ? ConsoleTheme.ACCENT : ConsoleTheme.BORDER;
		graphics.fill(promptInput.getX() - 1, promptInput.getY() - 1,
				promptInput.getRight() + 1, promptInput.getBottom() + 1, outline);
		graphics.fill(promptInput.getX(), promptInput.getY(), promptInput.getRight(), promptInput.getBottom(),
				ConsoleTheme.TRACK);
	}

	private void renderStatus(GuiGraphicsExtractor graphics) {
		if (!feedback.isBlank()) {
			AgentControlLayout layout = layout();
			int y = layout.sideNavigation() ? layout.footerY() - 14 : layout.contentTop() - 9;
			ConsoleText.centered(graphics, font, fit(feedback, layout.contentWidth()), width / 2, y,
					feedbackError ? ERROR : SUCCESS);
		} else if (page != Page.LIVE) {
			ScenarioBuildProgress build = AgentControlClient.buildProgressState().progress().orElse(null);
			if (build != null && build.status() == ScenarioBuildProgress.Status.BUILDING) {
				AgentControlLayout layout = layout();
				String message = "Arena build | " + build.humanPhase() + " | " + build.percent()
						+ "% | " + build.originLabel();
				ConsoleText.centered(graphics, font, fit(message, layout.contentWidth()), width / 2,
						layout.footerY() - 14, ACCENT);
			}
		}
	}

	private void submitSummon() {
		try {
			name = nameInput.getValue();
			selectNewlySummonedAgent = true;
			send(AgentControlCommandBuilder.summon(provider, model, reasoning, serviceTier, name, gameMode),
					"Creating agent...");
			rememberPreferences();
		} catch (IllegalArgumentException exception) {
			selectNewlySummonedAgent = false;
			setFeedback(exception.getMessage(), true);
		}
	}

	private void submitPrompt(String operation) {
		AgentControlAgent agent = selectedAgent();
		if (agent == null) return;
		if (!canUseAutomation()) {
			setFeedback(snapshot == null ? "Automation is not ready" : snapshot.automationStatus(), true);
			return;
		}
		try {
			prompt = promptInput.getValue();
			send(AgentControlCommandBuilder.prompt(operation, agent.agentId(), prompt), "Sending task...");
		} catch (IllegalArgumentException exception) {
			setFeedback(exception.getMessage(), true);
		}
	}

	private void submitGroupPrompt(String operation) {
		List<AgentControlAgent> agents = selectedGroupAgents();
		if (!canUseAutomation()) {
			setFeedback(snapshot == null ? "Automation is not ready" : snapshot.automationStatus(), true);
			return;
		}
		if (!AgentControlActions.everySupports(agents, operation)) {
			setFeedback("That command is not available for every selected agent", true);
			return;
		}
		try {
			prompt = promptInput.getValue();
			int queued = 0;
			for (AgentControlAgent agent : agents) {
				if (AgentControlClient.sendCommand(
						AgentControlCommandBuilder.prompt(operation, agent.agentId(), prompt))) queued++;
			}
			setFeedback(queued == agents.size()
					? "Queued for " + queued + (queued == 1 ? " agent" : " agents") + "; awaiting server updates"
					: "Only " + queued + " of " + agents.size() + " commands reached the server connection",
					queued != agents.size());
		} catch (IllegalArgumentException exception) {
			setFeedback(exception.getMessage(), true);
		}
	}

	private void submitAgentOperation(String operation) {
		AgentControlAgent agent = selectedAgent();
		if (agent == null) return;
		send(AgentControlCommandBuilder.agent(operation, agent.agentId()), "Updating " + agentDisplayName(agent) + "...");
	}

	private void confirmRemove() {
		if (selectedAgent() != null) show(Page.REMOVE_CONFIRM);
	}

	private void submitConfirmedRemove() {
		AgentControlAgent agent = selectedAgent();
		if (agent == null) return;
		page = Page.OVERVIEW;
		rebuildWidgets();
		send(AgentControlCommandBuilder.agent("remove", agent.agentId()),
				"Removing " + agentDisplayName(agent) + "...");
	}

	private void send(String command, String pendingMessage) {
		if (AgentControlClient.sendCommand(command)) setFeedback(pendingMessage + " Awaiting server update.", false);
		else setFeedback("Not connected to a compatible server", true);
	}

	private void show(Page next) {
		page = next;
		feedback = "";
		rebuildWidgets();
	}

	private void setFeedback(String message, boolean error) {
		feedback = Objects.requireNonNullElse(message, "");
		feedbackError = error;
	}

	private void rememberPreferences() {
		AgentControlClient.rememberPreferences(provider, model, reasoning);
	}

	private AgentControlAgent selectedAgent() {
		if (snapshot == null || selectedAgentId.isBlank()) return null;
		return snapshot.agents().stream().filter(agent -> agent.agentId().equals(selectedAgentId)).findFirst().orElse(null);
	}

	private List<AgentControlAgent> selectedGroupAgents() {
		if (snapshot == null) return List.of();
		return snapshot.agents().stream()
				.filter(agent -> groupSelectedAgentIds.contains(agent.agentId()))
				.toList();
	}

	private MultiLineEditBox multiLineInput(
			int x,
			int y,
			int width,
			int height,
			String narration,
			String placeholder
	) {
		MultiLineEditBox input = MultiLineEditBox.builder()
				.setX(x)
				.setY(y)
				.setPlaceholder(Component.literal(placeholder))
				.setTextColor(TEXT)
				.setTextShadow(false)
				.setCursorColor(ACCENT)
				.setShowBackground(false)
				.setShowDecorations(false)
				.build(font, width, height, Component.literal(narration));
		input.setCharacterLimit(AgentConstants.MAX_PROMPT_LENGTH);
		input.setLineLimit(Math.max(2, height / 12));
		input.setValue(prompt);
		input.setValueListener(value -> prompt = value);
		return input;
	}

	private ConsoleEditBox consoleEditBox(
			int x,
			int y,
			int width,
			int height,
			String identity,
			Component placeholder
	) {
		return new ConsoleEditBox(font, x, y, width, height,
				Component.translatable("screen.arenaagents.name"), placeholder, identity);
	}

	private ConsoleButton consoleButton(
			String label,
			int x,
			int y,
			int width,
			int height,
			boolean selected,
			Runnable action
	) {
		return consoleButton(Component.literal(label), x, y, width, height, selected, action);
	}

	private ConsoleButton consoleButton(
			Component label, int x, int y, int width, int height, boolean selected, Runnable action
	) {
		return new ConsoleButton(font, x, y, width, height, label, selected, ACCENT, action);
	}

	private ConsoleButton primaryButton(
			String label, int x, int y, int width, int height, Runnable action
	) {
		return new ConsoleButton(font, x, y, width, height, Component.literal(label), false, ACCENT,
				ConsoleButton.Tone.PRIMARY, action);
	}

	private ConsoleButton dangerButton(
			String label, int x, int y, int width, int height, Runnable action
	) {
		return new ConsoleButton(font, x, y, width, height, Component.literal(label), false, ACCENT,
				ConsoleButton.Tone.DANGER, action);
	}

	private boolean canControl() {
		return snapshot != null && snapshot.canControl();
	}

	private boolean canUseAutomation() {
		return canControl() && snapshot.automationAvailable();
	}

	private int visibleRows() {
		AgentControlLayout layout = layout();
		int top = layout.contentTop() + (page == Page.GROUP && !layout.sideNavigation() ? 0 : 24);
		if (page == Page.GROUP && !(layout.splitWorkspace() || layout.contentWidth() >= 500)) return 1;
		int bottom = page == Page.OVERVIEW && !layout.splitWorkspace()
				? layout.contentBottom() - ROW_HEIGHT - GAP
				: layout.contentBottom();
		return Math.max(1, Math.min(7, Math.max(1, bottom - top + 5) / 43));
	}

	private int panelWidth() {
		return layout().panelWidth();
	}

	private int panelLeft() {
		return layout().panelLeft();
	}

	private int contentLeft() {
		return layout().contentLeft();
	}

	private int contentRight() {
		return layout().contentRight();
	}

	private int contentWidth() {
		return contentRight() - contentLeft();
	}

	private AgentControlLayout layout() {
		return AgentControlLayout.calculate(width, height);
	}

	private String agentDisplayName(AgentControlAgent agent) {
		String defaultTag = agent.model() + " | " + agent.reasoning();
		return agent.displayName().equals(defaultTag)
				? AgentControlPresentation.profileLabel(agent)
				: agent.displayName();
	}

	private static int providerColor(String provider) {
		return ConsoleTheme.providerColor(provider);
	}

	private static int stateColor(String state) {
		return switch (state.toUpperCase(Locale.ROOT)) {
			case "ERROR", "DEAD", "DISCONNECTED" -> ERROR;
			case "IDLE", "COMPLETED" -> SUCCESS;
			default -> ACCENT;
		};
	}

	private static String capitalize(String value) {
		if (value == null || value.isBlank()) return "";
		return Character.toUpperCase(value.charAt(0)) + value.substring(1).toLowerCase(Locale.ROOT);
	}

	private static String initials(String value) {
		StringBuilder result = new StringBuilder(2);
		for (String part : value.trim().split("\\s+")) {
			if (!part.isBlank()) result.append(Character.toUpperCase(part.charAt(0)));
			if (result.length() == 2) break;
		}
		return result.isEmpty() ? "AI" : result.toString();
	}

	private String fit(String value, int available) {
		if (font.width(value) <= available) return value;
		String suffix = "...";
		return font.plainSubstrByWidth(value, Math.max(1, available - font.width(suffix))) + suffix;
	}

	private enum Page {
		OVERVIEW,
		GROUP,
		LIVE,
		CREATE,
		TASK,
		MANAGE,
		REMOVE_CONFIRM
	}
}
