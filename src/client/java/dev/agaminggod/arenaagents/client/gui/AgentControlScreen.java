package dev.agaminggod.arenaagents.client.gui;

import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.client.control.AgentControlClient;
import dev.agaminggod.arenaagents.client.render.CodexAgentRenderer;
import dev.agaminggod.arenaagents.control.AgentControlAgent;
import dev.agaminggod.arenaagents.control.AgentControlCatalog;
import dev.agaminggod.arenaagents.control.AgentControlCommandBuilder;
import dev.agaminggod.arenaagents.control.AgentControlSelection;
import dev.agaminggod.arenaagents.control.AgentControlSnapshot;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class AgentControlScreen extends Screen {
	private static final int MARGIN = 12;
	private static final int ROW_HEIGHT = 20;
	private static final int ROW_GAP = 4;
	private static final int LABEL_COLOR = 0xFFE0E0E0;
	private static final int MUTED_COLOR = 0xFF9A9A9A;
	private static final int ERROR_COLOR = 0xFFFF7777;
	private static final int PANEL_COLOR = 0xB0101218;
	private static final int MAX_PANEL_WIDTH = 620;
	private static final int MIN_PANEL_WIDTH = 300;
	private static final int SIDEBAR_WIDTH = 184;
	private static final int SIDEBAR_ROW_HEIGHT = 28;
	private static final int MAX_SIDEBAR_AGENTS = 8;

	private AgentControlSnapshot snapshot;
	private String selectedAgentId = "";
	private final Set<String> selectedAgentIds = new LinkedHashSet<>();
	private final Set<String> hiddenAgentIds = AgentControlClient.hiddenAgentIds();
	private final Set<String> automaticAgentIds = AgentControlClient.automaticAgentIds();
	private boolean showHiddenAgents;
	private String provider;
	private String model;
	private String reasoning;
	private AgentGameMode gameMode = AgentGameMode.SURVIVAL;
	private String name = "";
	private String prompt = "";
	private String feedback = "";
	private boolean feedbackError;
	private boolean selectNewlySummonedAgent;
	private int sidebarScroll;

	private EditBox modelInput;
	private EditBox nameInput;
	private EditBox promptInput;

	public AgentControlScreen() {
		super(Component.translatable("screen.arenaagents.controls.title"));
		AgentControlClient.Preferences preferences = AgentControlClient.preferences();
		provider = preferences.provider();
		model = preferences.model();
		reasoning = preferences.reasoning();
		snapshot = AgentControlClient.snapshot().orElse(null);
		if (snapshot != null) {
			selectedAgentId = AgentControlSelection.resolve(selectedAgentId, snapshot.agents());
			if (!selectedAgentId.isEmpty()) selectedAgentIds.add(selectedAgentId);
			snapshot.agents().forEach(agent -> {
				AgentControlClient.knownAgentIds().add(agent.agentId());
				if (agent.automaticProgress()) automaticAgentIds.add(agent.agentId());
				else automaticAgentIds.remove(agent.agentId());
			});
		}
	}

	public void acceptSnapshot(AgentControlSnapshot nextSnapshot) {
		AgentControlSnapshot previousSnapshot = snapshot;
		snapshot = Objects.requireNonNull(nextSnapshot, "nextSnapshot must not be null");
		if (selectNewlySummonedAgent) {
			for (AgentControlAgent agent : snapshot.agents()) {
				boolean alreadyPresent = previousSnapshot != null && previousSnapshot.agents().stream()
						.anyMatch(previous -> previous.agentId().equals(agent.agentId()));
				if (!alreadyPresent) {
					selectedAgentId = agent.agentId();
					selectedAgentIds.add(agent.agentId());
					AgentControlClient.knownAgentIds().add(agent.agentId());
					selectNewlySummonedAgent = false;
					break;
				}
			}
		}
		selectedAgentId = AgentControlSelection.resolve(selectedAgentId, snapshot.agents());
		snapshot.agents().forEach(agent -> {
			AgentControlClient.knownAgentIds().add(agent.agentId());
			if (agent.automaticProgress()) automaticAgentIds.add(agent.agentId());
			else automaticAgentIds.remove(agent.agentId());
		});
		Set<String> current = snapshot.agents().stream()
				.map(AgentControlAgent::agentId)
				.collect(java.util.stream.Collectors.toSet());
		selectedAgentIds.retainAll(current);
		hiddenAgentIds.retainAll(current);
		automaticAgentIds.retainAll(current);
		AgentControlClient.knownAgentIds().retainAll(current);
		if (selectedAgentIds.isEmpty() && !selectedAgentId.isEmpty()) selectedAgentIds.add(selectedAgentId);
		if (minecraft != null && !(getFocused() instanceof EditBox)) {
			rebuildWidgets();
		}
	}

	@Override
	protected void init() {
		int panelWidth = Math.max(MIN_PANEL_WIDTH, Math.min(MAX_PANEL_WIDTH, width - (MARGIN * 2)));
		int left = (width - panelWidth) / 2;
		boolean shortLayout = height < 300;
		int top = shortLayout ? 10 : 34;
		int verticalGap = shortLayout ? 2 : ROW_GAP;
		int verticalStep = ROW_HEIGHT + verticalGap;
		int fullWidth = panelWidth - (MARGIN * 2);
		boolean sidebarEnabled = panelWidth >= 500;
		int contentWidth = fullWidth - (sidebarEnabled ? SIDEBAR_WIDTH + ROW_GAP : 0);
		int halfWidth = (contentWidth - ROW_GAP) / 2;
		boolean compact = contentWidth < 350;
		int fieldWidth = compact ? contentWidth : halfWidth;
		int right = compact ? left + MARGIN : left + MARGIN + halfWidth + ROW_GAP;
		int y = top + 20;

		if (sidebarEnabled) {
			addAgentSidebar(left + MARGIN + contentWidth + ROW_GAP, y, SIDEBAR_WIDTH);
		} else {
			addAgentSelection(left + MARGIN, y, contentWidth);
			y += verticalStep;
		}

		CycleButton<String> providerButton = CycleButton.builder(
						value -> Component.literal(capitalize(value)),
						provider
				)
				.withValues(AgentControlCatalog.providers())
				.create(left + MARGIN, y, fieldWidth, ROW_HEIGHT, Component.translatable("screen.arenaagents.provider"),
						(button, value) -> changeProvider(value));
		addRenderableWidget(providerButton);

		modelInput = new EditBox(font, right, compact ? y + verticalStep : y, fieldWidth, ROW_HEIGHT,
				Component.translatable("screen.arenaagents.model"));
		modelInput.setMaxLength(AgentConstants.MAX_MODEL_LENGTH);
		modelInput.setHint(Component.translatable("screen.arenaagents.model"));
		modelInput.setValue(model);
		modelInput.setResponder(value -> model = value);
		addRenderableWidget(modelInput);
		y += compact ? verticalStep * 2 : verticalStep;

		List<String> efforts = AgentControlCatalog.reasoningEfforts(provider, model);
		if (!efforts.contains(reasoning)) {
			reasoning = AgentControlCatalog.defaultReasoning(provider, model);
		}
		CycleButton<String> reasoningButton = CycleButton.builder(Component::literal, reasoning)
				.withValues(efforts)
				.create(left + MARGIN, y, fieldWidth, ROW_HEIGHT,
						Component.translatable("screen.arenaagents.thinking"),
						(button, value) -> {
							reasoning = value;
							rememberPreferences();
						});
		addRenderableWidget(reasoningButton);

		nameInput = new EditBox(font, right, compact ? y + verticalStep : y, fieldWidth, ROW_HEIGHT,
				Component.translatable("screen.arenaagents.name"));
		nameInput.setMaxLength(AgentConstants.MAX_USER_NAME_LENGTH);
		nameInput.setHint(Component.translatable("screen.arenaagents.name_optional"));
		nameInput.setValue(name);
		nameInput.setResponder(value -> name = value);
		addRenderableWidget(nameInput);
		y += compact ? verticalStep * 2 : verticalStep;

		CycleButton<AgentGameMode> gameModeButton = CycleButton.builder(
						value -> Component.literal(value.displayName()),
						gameMode
				)
				.withValues(List.of(AgentGameMode.values()))
				.create(left + MARGIN, y, contentWidth, ROW_HEIGHT,
						Component.translatable("screen.arenaagents.game_mode"),
						(button, value) -> gameMode = value);
		addRenderableWidget(gameModeButton);
		y += verticalStep;
		Button summonButton = Button.builder(
						Component.translatable("screen.arenaagents.summon"),
						button -> submitSummon()
				)
				.bounds(left + MARGIN, y, contentWidth, ROW_HEIGHT)
				.build();
		summonButton.active = canControl();
		addRenderableWidget(summonButton);
		y += ROW_HEIGHT + (shortLayout ? verticalGap : ROW_GAP * 2);

		promptInput = new EditBox(font, left + MARGIN, y, contentWidth, ROW_HEIGHT,
				Component.translatable("screen.arenaagents.prompt"));
		promptInput.setMaxLength(AgentConstants.MAX_PROMPT_LENGTH);
		promptInput.setHint(Component.translatable("screen.arenaagents.prompt_hint"));
		promptInput.setValue(prompt);
		promptInput.setResponder(value -> prompt = value);
		addRenderableWidget(promptInput);
		y += verticalStep;

		addPromptActions(left + MARGIN, y, contentWidth);
		y += verticalStep;
		if (shortLayout) {
			int lifecycleWidth = ((contentWidth - ROW_GAP) * 3) / 5;
			int footerWidth = contentWidth - lifecycleWidth - ROW_GAP;
			addLifecycleActions(left + MARGIN, y, lifecycleWidth);
			addFooterActions(left + MARGIN + lifecycleWidth + ROW_GAP, y, footerWidth);
		} else {
			addLifecycleActions(left + MARGIN, y, contentWidth);
			y += ROW_HEIGHT + ROW_GAP;
			addFooterActions(left + MARGIN, y, contentWidth);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int panelWidth = Math.max(MIN_PANEL_WIDTH, Math.min(MAX_PANEL_WIDTH, width - (MARGIN * 2)));
		int left = (width - panelWidth) / 2;
		boolean shortLayout = height < 300;
		int panelTop = shortLayout ? 2 : 20;
		int panelHeight = shortLayout ? height - panelTop - 4 : Math.min(height - panelTop, 300);
		graphics.fill(left, panelTop, left + panelWidth, panelTop + panelHeight, PANEL_COLOR);
		graphics.centeredText(font, title.getString(), width / 2, shortLayout ? 4 : 25, LABEL_COLOR);
		graphics.text(font, selectedSummary(), left + MARGIN, shortLayout ? 16 : 39, LABEL_COLOR, false);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		renderAgentSidebarIcons(graphics, left, panelWidth, shortLayout);

		AgentControlAgent selected = selectedAgent();
		int detailsY = Math.min(height - 40, 278);
		if (selected != null) {
			String details = Component.translatable(
					"screen.arenaagents.agent_details",
					selected.state(),
					selected.queuedGoalCount(),
					selected.provider()
			).getString();
			graphics.text(font, details, left + MARGIN, detailsY, MUTED_COLOR, false);
			if (!selected.currentGoal().isEmpty()) {
				graphics.textWithWordWrap(
						font,
						Component.translatable("screen.arenaagents.current_goal", selected.currentGoal()),
						left + MARGIN,
						detailsY + 12,
						panelWidth - (MARGIN * 2),
						MUTED_COLOR
				);
			}
		}
		if (!feedback.isEmpty()) {
			graphics.centeredText(font, feedback, width / 2, height - 14,
					feedbackError ? ERROR_COLOR : LABEL_COLOR);
		}
	}

	private void addAgentSidebar(int x, int y, int availableWidth) {
		if (snapshot == null) {
			return;
		}
		List<AgentControlAgent> visible = visibleAgents();
		sidebarScroll = Math.min(sidebarScroll, Math.max(0, visible.size() - MAX_SIDEBAR_AGENTS));
		int count = Math.min(visible.size() - sidebarScroll, MAX_SIDEBAR_AGENTS);
		for (int index = 0; index < count; index++) {
			AgentControlAgent agent = visible.get(sidebarScroll + index);
			String marker = selectedAgentIds.contains(agent.agentId()) ? "✓" : " ";
			String automatic = automaticAgentIds.contains(agent.agentId()) ? "A" : " ";
			Button button = Button.builder(
					Component.literal(marker + automatic + "  " + agentDisplayName(agent)).withColor(providerColor(agent.provider())),
					ignored -> selectAgent(agent.agentId())
				)
					.bounds(x, y + (index * SIDEBAR_ROW_HEIGHT), availableWidth, SIDEBAR_ROW_HEIGHT - 2)
					.build();
			addRenderableWidget(button);
		}
		int controlsY = y + (MAX_SIDEBAR_AGENTS * SIDEBAR_ROW_HEIGHT);
		int half = (availableWidth - ROW_GAP) / 2;
		addRenderableWidget(Button.builder(Component.literal("Select all"), ignored -> {
			selectedAgentIds.clear();
			visibleAgents().forEach(agent -> selectedAgentIds.add(agent.agentId()));
			if (!selectedAgentIds.isEmpty()) selectedAgentId = selectedAgentIds.iterator().next();
			rebuildWidgets();
		}).bounds(x, controlsY, half, ROW_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.literal("Clear"), ignored -> {
			selectedAgentIds.clear();
			rebuildWidgets();
		}).bounds(x + half + ROW_GAP, controlsY, half, ROW_HEIGHT).build());
		boolean allSelectedHidden = !selectedAgentIds.isEmpty() && hiddenAgentIds.containsAll(selectedAgentIds);
		addRenderableWidget(Button.builder(Component.literal(allSelectedHidden ? "Show selected" : "Hide selected"), ignored -> {
			if (hiddenAgentIds.containsAll(selectedAgentIds)) hiddenAgentIds.removeAll(selectedAgentIds);
			else hiddenAgentIds.addAll(selectedAgentIds);
			rebuildWidgets();
		}).bounds(x, controlsY + ROW_HEIGHT + ROW_GAP, availableWidth, ROW_HEIGHT).build());
		addRenderableWidget(Button.builder(
				Component.literal(showHiddenAgents ? "Hide hidden agents" : "Show hidden agents"),
				ignored -> {
					showHiddenAgents = !showHiddenAgents;
					sidebarScroll = 0;
					rebuildWidgets();
				}
		).bounds(x, controlsY + ((ROW_HEIGHT + ROW_GAP) * 2), availableWidth, ROW_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.literal("Toggle automatic"), ignored -> {
			for (String id : selectedAgentIds) {
				if (!automaticAgentIds.add(id)) automaticAgentIds.remove(id);
				send(AgentControlCommandBuilder.agent("auto", id));
			}
			rebuildWidgets();
		}).bounds(x, controlsY + ((ROW_HEIGHT + ROW_GAP) * 3), availableWidth, ROW_HEIGHT).build());
	}

	private void renderAgentSidebarIcons(GuiGraphicsExtractor graphics, int left, int panelWidth, boolean shortLayout) {
		if (panelWidth < 500 || snapshot == null) {
			return;
		}
		int x = left + panelWidth - MARGIN - SIDEBAR_WIDTH + 6;
		int y = (shortLayout ? 10 : 34) + 25;
		List<AgentControlAgent> visible = visibleAgents();
		sidebarScroll = Math.min(sidebarScroll, Math.max(0, visible.size() - MAX_SIDEBAR_AGENTS));
		int count = Math.min(visible.size() - sidebarScroll, MAX_SIDEBAR_AGENTS);
		for (int index = 0; index < count; index++) {
			AgentControlAgent agent = visible.get(sidebarScroll + index);
			var texture = CodexAgentRenderer.textureFor(agent.provider(), skinVariant(agent));
			int iconY = y + (index * SIDEBAR_ROW_HEIGHT);
			graphics.blit(texture, x, iconY, x + 16, iconY + 16, 0.125F, 0.25F, 0.125F, 0.25F);
			graphics.blit(texture, x, iconY, x + 16, iconY + 16, 0.625F, 0.75F, 0.125F, 0.25F);
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		int panelWidth = Math.max(MIN_PANEL_WIDTH, Math.min(MAX_PANEL_WIDTH, width - (MARGIN * 2)));
		int left = (width - panelWidth) / 2;
		int sidebarLeft = left + panelWidth - MARGIN - SIDEBAR_WIDTH;
		if (panelWidth >= 500 && mouseX >= sidebarLeft && mouseX <= sidebarLeft + SIDEBAR_WIDTH
				&& snapshot != null && visibleAgents().size() > MAX_SIDEBAR_AGENTS) {
			int maximum = visibleAgents().size() - MAX_SIDEBAR_AGENTS;
			int next = Math.clamp(sidebarScroll + (verticalAmount > 0.0D ? -1 : 1), 0, maximum);
			if (next != sidebarScroll) {
				sidebarScroll = next;
				rebuildWidgets();
			}
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	private void selectAgent(String agentId) {
		selectedAgentId = agentId;
		if (!selectedAgentIds.add(agentId)) selectedAgentIds.remove(agentId);
		rebuildWidgets();
	}

	private void addAgentSelection(int x, int y, int availableWidth) {
		int buttonWidth = 56;
		Button previous = Button.builder(
						Component.translatable("screen.arenaagents.previous"),
						button -> moveSelection(-1)
				)
				.bounds(x, y, buttonWidth, ROW_HEIGHT)
				.build();
		Button next = Button.builder(
						Component.translatable("screen.arenaagents.next"),
						button -> moveSelection(1)
				)
				.bounds(x + availableWidth - buttonWidth, y, buttonWidth, ROW_HEIGHT)
				.build();
		boolean multiple = snapshot != null && snapshot.agents().size() > 1;
		previous.active = multiple;
		next.active = multiple;
		addRenderableWidget(previous);
		addRenderableWidget(next);
	}

	private void addPromptActions(int x, int y, int availableWidth) {
		List<String> operations = List.of("start", "queue", "steer");
		int buttonWidth = (availableWidth - (ROW_GAP * (operations.size() - 1))) / operations.size();
		for (int index = 0; index < operations.size(); index++) {
			String operation = operations.get(index);
			Button button = Button.builder(
							Component.translatable("screen.arenaagents." + operation),
							ignored -> submitPrompt(operation)
					)
					.bounds(x + (index * (buttonWidth + ROW_GAP)), y, buttonWidth, ROW_HEIGHT)
					.build();
			button.active = canControl() && !selectedAgents().isEmpty();
			addRenderableWidget(button);
		}
	}

	private void addLifecycleActions(int x, int y, int availableWidth) {
		List<String> operations = List.of("stop", "resume", "respawn", "remove");
		int buttonWidth = (availableWidth - (ROW_GAP * (operations.size() - 1))) / operations.size();
		for (int index = 0; index < operations.size(); index++) {
			String operation = operations.get(index);
			Button button = Button.builder(
							Component.translatable("screen.arenaagents." + operation),
							ignored -> {
								if (operation.equals("remove")) {
									confirmRemove();
								} else {
									submitAgentOperation(operation);
								}
							}
					)
					.bounds(x + (index * (buttonWidth + ROW_GAP)), y, buttonWidth, ROW_HEIGHT)
					.build();
			button.active = canControl() && !selectedAgents().isEmpty();
			addRenderableWidget(button);
		}
	}

	private void addFooterActions(int x, int y, int availableWidth) {
		int buttonWidth = (availableWidth - ROW_GAP) / 2;
		addRenderableWidget(Button.builder(
						Component.translatable("screen.arenaagents.refresh"),
						button -> AgentControlClient.requestSnapshot()
				)
				.bounds(x, y, buttonWidth, ROW_HEIGHT)
				.build());
		addRenderableWidget(Button.builder(
						Component.translatable("gui.done"),
						button -> onClose()
				)
				.bounds(x + buttonWidth + ROW_GAP, y, buttonWidth, ROW_HEIGHT)
				.build());
	}

	private void changeProvider(String value) {
		provider = value;
		model = AgentControlCatalog.defaultModel(provider);
		reasoning = AgentControlCatalog.defaultReasoning(provider, model);
		rememberPreferences();
		rebuildWidgets();
	}

	private void moveSelection(int direction) {
		if (snapshot == null || snapshot.agents().isEmpty()) {
			return;
		}
		List<AgentControlAgent> agents = snapshot.agents();
		int current = 0;
		for (int index = 0; index < agents.size(); index++) {
			if (agents.get(index).agentId().equals(selectedAgentId)) {
				current = index;
				break;
			}
		}
		selectedAgentId = agents.get(Math.floorMod(current + direction, agents.size())).agentId();
		rebuildWidgets();
	}

	private void submitSummon() {
		try {
			model = modelInput.getValue();
			name = nameInput.getValue();
			selectNewlySummonedAgent = true;
			send(AgentControlCommandBuilder.summon(provider, model, reasoning, name, gameMode));
			rememberPreferences();
		} catch (IllegalArgumentException exception) {
			selectNewlySummonedAgent = false;
			setFeedback(exception.getMessage(), true);
		}
	}

	private void submitPrompt(String operation) {
		List<AgentControlAgent> selected = selectedAgents();
		if (selected.isEmpty()) {
			setFeedback(Component.translatable("screen.arenaagents.no_agent").getString(), true);
			return;
		}
		try {
			prompt = promptInput.getValue();
			for (AgentControlAgent agent : selected) {
				send(AgentControlCommandBuilder.prompt(operation, agent.agentId(), prompt));
			}
		} catch (IllegalArgumentException exception) {
			setFeedback(exception.getMessage(), true);
		}
	}

	private void submitAgentOperation(String operation) {
		List<AgentControlAgent> selected = selectedAgents();
		if (selected.isEmpty()) {
			setFeedback(Component.translatable("screen.arenaagents.no_agent").getString(), true);
			return;
		}
		for (AgentControlAgent agent : selected) {
			send(AgentControlCommandBuilder.agent(operation, agent.agentId()));
		}
	}

	private void confirmRemove() {
		AgentControlAgent selected = selectedAgent();
		if (selected == null || minecraft == null) {
			return;
		}
		minecraft.setScreen(new ConfirmScreen(
				confirmed -> {
					minecraft.setScreen(this);
					if (confirmed) {
						submitAgentOperation("remove");
					}
				},
				Component.translatable("screen.arenaagents.remove_confirm_title"),
				Component.translatable("screen.arenaagents.remove_confirm_message", selected.displayName())
		));
	}

	private void send(String command) {
		if (AgentControlClient.sendCommand(command)) {
			setFeedback(Component.translatable("screen.arenaagents.command_sent").getString(), false);
		} else {
			setFeedback(Component.translatable("screen.arenaagents.disconnected").getString(), true);
		}
	}

	private void rememberPreferences() {
		AgentControlClient.rememberPreferences(provider, model, reasoning);
	}

	private void setFeedback(String message, boolean error) {
		feedback = Objects.requireNonNullElse(message, "");
		feedbackError = error;
	}

	private boolean canControl() {
		return snapshot != null && snapshot.canControl();
	}

	private AgentControlAgent selectedAgent() {
		if (snapshot == null || selectedAgentIds.isEmpty()) {
			return null;
		}
		return snapshot.agents().stream()
				.filter(agent -> agent.agentId().equals(selectedAgentId))
				.findFirst()
				.orElse(null);
	}

	private List<AgentControlAgent> selectedAgents() {
		if (snapshot == null) return List.of();
		return snapshot.agents().stream()
				.filter(agent -> selectedAgentIds.contains(agent.agentId()))
				.toList();
	}

	private List<AgentControlAgent> visibleAgents() {
		if (snapshot == null) return List.of();
		return snapshot.agents().stream()
				.filter(agent -> showHiddenAgents || !hiddenAgentIds.contains(agent.agentId()))
				.toList();
	}

	private String selectedSummary() {
		if (snapshot == null) {
			return Component.translatable("screen.arenaagents.loading").getString();
		}
		AgentControlAgent selected = selectedAgent();
		if (selectedAgentIds.size() > 1) {
			return selectedAgentIds.size() + " agents selected";
		}
		if (selected == null) {
			return snapshot.canControl()
					? Component.translatable("screen.arenaagents.no_agents").getString()
					: Component.translatable("screen.arenaagents.no_permission").getString();
		}
		return agentDisplayName(selected) + "  ·  " + capitalize(selected.state().toLowerCase(Locale.ROOT));
	}

	private static int providerColor(String provider) {
		return switch (provider.toLowerCase(Locale.ROOT)) {
			case "codex" -> 0xFF42D39B;
			case "gemini", "antigravity" -> 0xFF8E86FF;
			case "kimi" -> 0xFFFFB45E;
			default -> LABEL_COLOR;
		};
	}

	private String agentDisplayName(AgentControlAgent agent) {
		String base = agent.displayName().equals(agent.model() + " · " + agent.reasoning())
				? humanize(agent.model()) + " " + capitalize(agent.reasoning())
				: agent.displayName();
		int duplicateIndex = 0;
		if (snapshot != null) {
			for (AgentControlAgent candidate : snapshot.agents()) {
				if (candidate.agentId().equals(agent.agentId())) {
					break;
				}
				if (candidate.provider().equals(agent.provider())
						&& candidate.model().equals(agent.model())
						&& candidate.reasoning().equals(agent.reasoning())) {
					duplicateIndex++;
				}
			}
		}
		return duplicateIndex == 0 ? base : base + " (" + duplicateIndex + ")";
	}

	private static int skinVariant(AgentControlAgent agent) {
		return Math.floorMod(agent.agentId().hashCode(), CodexAgentRenderer.SKIN_VARIANT_COUNT);
	}

	private static String humanize(String value) {
		String[] words = value.replace('-', ' ').replace('_', ' ').split(" +");
		StringBuilder display = new StringBuilder();
		for (String word : words) {
			if (display.length() > 0) display.append(' ');
			if (word.equalsIgnoreCase("gpt")) display.append("GPT");
			else display.append(capitalize(word));
		}
		return display.toString();
	}

	private static String capitalize(String value) {
		if (value.isEmpty()) {
			return value;
		}
		return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
	}
}
