package dev.agaminggod.arenaagents.client.gui;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.client.camera.CameraDirectorClient;
import dev.agaminggod.arenaagents.client.control.AgentControlClient;
import dev.agaminggod.arenaagents.client.control.DirectorClientState;
import dev.agaminggod.arenaagents.client.gui.widget.ConsoleButton;
import dev.agaminggod.arenaagents.client.gui.widget.ConsoleCycleButton;
import dev.agaminggod.arenaagents.client.gui.widget.ConsoleEditBox;
import dev.agaminggod.arenaagents.client.gui.widget.ConsoleDropdown;
import dev.agaminggod.arenaagents.control.DirectorCommandRequestPayload;
import dev.agaminggod.arenaagents.control.DirectorEditorPayload;
import dev.agaminggod.arenaagents.control.DirectorCommandResultPayload;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/** Point-and-click controls for skit actors, actions, voices, and cinematic camera paths. */
public final class SkitDirectorScreen extends Screen {
	private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(SkitDirectorScreen.class);
	private static final int PANEL = ConsoleTheme.PANEL;
	private static final int PANEL_EDGE = ConsoleTheme.BORDER;
	private static final int BACKDROP = ConsoleTheme.BACKDROP;
	private static final int TEXT = ConsoleTheme.TEXT;
	private static final int MUTED = ConsoleTheme.MUTED;
	private static final int ACCENT = ConsoleTheme.ACCENT;
	private static final int SUCCESS = ConsoleTheme.SUCCESS;
	private static final int ERROR = ConsoleTheme.ERROR;
	private static final int ROW = 26;
	private static final int GAP = 6;
	private static final List<String> PROVIDERS = List.of("codex", "claude", "gemini");
	private static final List<String> ACTIONS = List.of("move", "walk", "wait", "jump", "equip", "use", "swing", "emote");
	private static final List<String> TONES = List.of("neutral", "warm", "excited", "serious", "dramatic", "whisper", "robotic", "angry");
	private static final List<String> SPEEDS = List.of("0.75", "1.0", "1.25", "1.5");
	private static final List<String> RADII = List.of("16", "32", "48", "64", "96");
	private static final List<String> VOICES = dev.agaminggod.arenaagents.server.voice.VoiceCatalog.selectableIds();

	private final Screen parent;
	private final Map<String, String> drafts = DirectorClientState.drafts();
	private final Map<String, DirectorEditorPayload.Snapshot> libraries = new HashMap<>();
	private java.util.UUID pendingEditor;
	private String pendingEditorOperation;
	private int selectedRow = -1;
	private ConsoleEditBox actionDuration;
	private ConsoleEditBox actionDescription;
	private ConsoleEditBox walkForward;
	private ConsoleEditBox walkStrafe;
	private boolean walkSprint;
	private boolean actionSneak = true;
	private boolean initialRead;
	private int scrollRows;
	private boolean buildingContent;
	private boolean detailMode;
	private Tab tab = Tab.SPAWN;
	private String provider = "codex";
	private String selectedActor = "";
	private final Map<ConsoleEditBox, String> fieldLabels = new HashMap<>();
	private String selectedAction = "move";
	private String voice = VOICES.getFirst();
	private ConsoleDropdown<String> voiceDropdown;
	private ConsoleCycleButton<String> savedScriptSelector;
	private String tone = "neutral";
	private String speed = "1.0";
	private String radius = "48";
	private String feedback = "";
	private boolean feedbackError;
	private ConsoleEditBox takeName;
	private ConsoleEditBox takeMotion;
	private ConsoleEditBox takeVoice;
	private String deleteConfirmation = "";
	private ConsoleEditBox actorName;
	private String legacyAgent = "";
	private ConsoleEditBox scriptName;
	private ConsoleEditBox actionArgs;
	private ConsoleEditBox right;
	private ConsoleEditBox up;
	private ConsoleEditBox forward;
	private ConsoleEditBox voiceText;
	private ConsoleEditBox voiceScript;
	private ConsoleEditBox voiceDelay;
	private ConsoleEditBox cameraPath;
	private ConsoleEditBox retainedEditor;
	private java.util.UUID pendingCommand;
	private long commandDeadline;
	private long editorDeadline;

	public SkitDirectorScreen(Screen parent) {
		super(Minecraft.getInstance(), ConsoleFont.create(Minecraft.getInstance()),
				Component.literal("Skit Director"));
		this.parent = parent;
		selectedActor = drafts.getOrDefault("selected-actor", "");
		selectedAction = drafts.getOrDefault("selected-action", "move");
		voice = drafts.getOrDefault("selected-voice", VOICES.getFirst());
		if (!VOICES.contains(voice)) voice = VOICES.getFirst();
		tone = drafts.getOrDefault("selected-tone", "neutral"); speed = drafts.getOrDefault("selected-speed", "1.0"); radius = drafts.getOrDefault("selected-radius", "48");
		walkSprint = Boolean.parseBoolean(drafts.getOrDefault("walk-sprint", "false")); actionSneak = Boolean.parseBoolean(drafts.getOrDefault("action-sneak", "true"));
		tab = Tab.valueOf(drafts.getOrDefault("selected-tab", "SPAWN"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	public void acceptCatalogUpdate() {
		AbstractWidget focused = getFocused() instanceof AbstractWidget widget ? widget : null;
		String focusIdentity = ConsoleFocusIdentity.of(focused);
		// Snapshot updates keep the same layout. Reuse the editor to retain its caret and selection.
		retainedEditor = focused instanceof ConsoleEditBox edit ? edit : null;
		try {
			rebuildWidgets();
			if (!focusIdentity.isBlank()) children().stream()
					.filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
					.filter(widget -> widget.visible && widget.active && ConsoleFocusIdentity.of(widget).equals(focusIdentity))
					.findFirst().ifPresent(this::setInitialFocus);
		} finally {
			retainedEditor = null;
		}
	}

	public void acceptLocalFeedback(String message, boolean error) { feedback = message; feedbackError = error; }

	public void acceptCommandResult(DirectorCommandResultPayload result) {
		if (!result.requestId().equals(pendingCommand)) return;
		pendingCommand = null;
		feedback = result.message();
		feedbackError = !result.success();
		if (result.success() && pendingEditor == null && (tab == Tab.SPAWN || tab == Tab.ACTIONS || tab == Tab.VOICE)) requestEditor("read", 0);
	}

	@Override
	protected void init() {
		clearFields();
		fieldLabels.clear();
		voiceDropdown = null;
		savedScriptSelector = null;
		scrollRows = Math.min(scrollRows, maxScrollRows());
		int left = panelLeft();
		int top = panelTop();
		int width = panelWidth();
		int tabWidth = (width - GAP * 3) / 4;
		for (int index = 0; index < Tab.values().length; index++) {
			Tab value = Tab.values()[index];
			int x = left + index * (tabWidth + GAP);
			addRenderableWidget(button(value.label, x, top + (compact() ? 12 : 42),
					index == 3 ? left + width - x : tabWidth, compact() ? 24 : ROW, value == tab,
					() -> { tab = value; drafts.put("selected-tab", value.name()); scrollRows = 0; selectedRow = -1; rebuildWidgets(); if (tab == Tab.SPAWN || tab == Tab.ACTIONS || tab == Tab.VOICE) requestEditor("read", 0); }));
		}
		buildingContent = true;
		if (!detailMode) initQuickView();
		else switch (tab) {
			case SPAWN -> { initSpawn(); initTake(); }
			case ACTIONS -> initActions();
			case VOICE -> initVoice();
			case CAMERA -> initCamera();
		}
		buildingContent = false;
		if (detailMode) addRenderableWidget(button("Simple view", left + width - 110, panelTop() + panelHeight() - (compact() ? ROW : 30), 110, ROW, false, () -> { detailMode = false; scrollRows = 0; rebuildWidgets(); }));
		if (!compact() && detailMode) {
			addRenderableWidget(button("Play take", left + width - 172, top + 8, 75, ROW, false, () -> send("codex skit take play " + word(drafts.getOrDefault("director-take-name", "")))));
			addRenderableWidget(button("Stop take", left + width - 91, top + 8, 75, ROW, false, () -> send("codex skit take stop")));
		}
		addRenderableWidget(button("Back to console", left, panelTop() + panelHeight() - (compact() ? ROW : 30), 140, ROW, false, this::onClose));
		if (!initialRead && minecraft != null && minecraft.getConnection() != null) { initialRead = true; runAction(() -> requestEditor("read", 0)); }
	}

	private void clearFields() {
		actorName = scriptName = actionArgs = right = up = forward = null;
		voiceText = voiceScript = voiceDelay = cameraPath = null;
	}

	/** Everyday controls stay separate from row editing and scene management. */
	private void initQuickView() {
		int[] c = columns();
		int y = contentTop();
		int full = c[2] * 2 + GAP;
		switch (tab) {
			case SPAWN -> {
				actorName = addEdit("Actor name", "GPT 6-Astra", c[0], y, c[2], AgentConstants.MAX_USER_NAME_LENGTH, "director-actor-name");
				addRenderableWidget(new ConsoleCycleButton<>(font, c[1], y, c[2], ROW, Component.literal("Appearance"), PROVIDERS, provider, this::title, value -> provider = value)).visible = contentVisible(y, ROW);
				y += ROW + GAP;
				boolean enabled = DirectorClientState.snapshot().map(value -> value.enabled()).orElse(false);
				var spawn = addRenderableWidget(primary("Add actor here", c[0], y, full, ROW, this::summon));
				spawn.active = enabled;
				y += ROW + GAP;
				if (!enabled) {
					addRenderableWidget(button("Enable Director mode", c[0], y, full, ROW, false, () -> send("codex skit on")));
					y += ROW + GAP;
				}
				addActorPicker(c[0], y, full);
				y += ROW + GAP;
				addRenderableWidget(button("Place selected actor here", c[0], y, full, ROW, false, () -> place("here")));
				y += ROW + GAP;
				openDetailsButton("Manage cast and scenes", c[0], y, full);
			}
			case ACTIONS -> {
				addActorPicker(c[0], y, c[2]);
				scriptName = addEdit("Script name", "intro", c[1], y, c[2], 64, "director-script-name");
				y += ROW + GAP;
				actionDescription = addEdit("What should happen?", "Fly here, land, then wave", c[0], y, full, 2048, "director-action-description");
				y += ROW + GAP;
				var generate = addRenderableWidget(primary(DirectorClientState.generationPending() ? "Luna is writing..." : "Write actions", c[0], y, full, ROW, this::generateScript));
				generate.active = !DirectorClientState.generationPending();
				y += ROW + GAP;
				addRenderableWidget(button("Preview script", c[0], y, c[2], ROW, false, () -> scriptCommand("play")));
				addRenderableWidget(button("Stop", c[1], y, c[2], ROW, false, () -> scriptCommand("stop")));
				y += ROW + GAP;
				openDetailsButton("Saved scripts and editing", c[0], y, full);
			}
			case VOICE -> {
				addActorPicker(c[0], y, full);
				loadActorVoice();
				y += ROW + GAP;
				voiceDropdown = addRenderableWidget(new ConsoleDropdown<>(font, c[0], y, full, ROW, "Voice", withSavedValue(VOICES, voice), voice, this::voiceLabel,
						value -> { voice = value; drafts.put(voiceDraftKey("voice"), value); }, width, height));
				voiceDropdown.visible = contentVisible(y, ROW);
				y += ROW + GAP;
				voiceText = addEdit("What should they say?", "Your line", c[0], y, full, 280, "director-voice-text");
				y += ROW + GAP;
				addRenderableWidget(primary("Listen", c[0], y, c[2], ROW, this::sayLine));
				addRenderableWidget(button("Save actor voice", c[1], y, c[2], ROW, false, this::setVoice));
				y += ROW + GAP;
				openDetailsButton("Delivery and saved dialogue", c[0], y, full);
			}
			case CAMERA -> {
				addRenderableWidget(primary("Get tripod camera", c[0], y, full, ROW, () -> send("codex skit camera kit")));
				y += ROW + GAP;
				cameraPath = addEdit("Shot name", "intro", c[0], y, full, 64, "director-camera-path");
				y += ROW + GAP;
				addRenderableWidget(button("Record at camera", c[0], y, c[2], ROW, false, () -> cameraStart(false)));
				addRenderableWidget(button("Save shot", c[1], y, c[2], ROW, false, CameraDirectorClient::stopRecordingFromGui));
				y += ROW + GAP;
				addRenderableWidget(button("Preview shot", c[0], y, c[2], ROW, false, () -> cameraPlay(false)));
				addRenderableWidget(button("Exit camera", c[1], y, c[2], ROW, false, CameraDirectorClient::stopPlaybackFromGui));
				y += ROW + GAP;
				openDetailsButton("Camera tools and saved shots", c[0], y, full);
			}
		}
	}

	private void openDetailsButton(String label, int x, int y, int width) {
		addRenderableWidget(button(label, x, y, width, ROW, false, () -> { detailMode = true; scrollRows = 0; rebuildWidgets(); requestEditor("read", 0); }));
	}

	private void initSpawn() {
		int[] c = columns();
		int y = contentTop();
		actorName = addEdit("New actor name", "Name", c[0], y, c[2], AgentConstants.MAX_USER_NAME_LENGTH, "director-actor-name");
		addRenderableWidget(new ConsoleCycleButton<>(font, c[1], y, c[2], ROW, Component.literal("Appearance"),
				PROVIDERS, provider, this::title, value -> provider = value)).visible = contentVisible(y, ROW);
		y += ROW + GAP;
		addRenderableWidget(primary("Spawn here", c[0], y, c[2], ROW, this::summon));
		boolean enabled = DirectorClientState.snapshot().map(value -> value.enabled()).orElse(false);
		ConsoleButton mode = button(enabled ? "Disable skit mode" : "Enable skit mode", c[1], y, c[2], ROW, enabled,
				() -> send("codex skit " + (enabled ? "off" : "on")));
		mode.active = DirectorClientState.snapshot().map(value -> value.canControl()).orElse(false);
		addRenderableWidget(mode);
		y += ROW + GAP;
		addActorPicker(c[0], y, c[2] * 2 + GAP);
		y += ROW + GAP;
		addRenderableWidget(button("Respawn", c[0], y, c[2], ROW, false,
				() -> send("codex skit respawn " + actorTarget())));
		addRenderableWidget(button("Remove from cast", c[1], y, c[2], ROW, false,
				() -> send("codex skit remove " + actorTarget())));
		y += ROW + GAP;
		int third = (c[2] * 2 - GAP) / 3;
		right = addEdit("Right", "0", c[0], y, third, 24, "director-right");
		up = addEdit("Up", "0", c[0] + third + GAP, y, third, 24, "director-up");
		forward = addEdit("Forward", "2", c[0] + 2 * (third + GAP), y, third, 24, "director-forward");
		y += ROW + GAP;
		addRenderableWidget(button("Place here", c[0], y, c[2], ROW, false, () -> place("here")));
		addRenderableWidget(button("Place relative to me", c[1], y, c[2], ROW, false, () -> place("relative")));
		y += ROW + GAP;
		addRenderableWidget(button("Face where I look", c[0], y, c[2], ROW, false, () -> place("look_at")));
		var legacy = DirectorClientState.importCandidates();
		if (!legacy.isEmpty()) {
			y += ROW + GAP;
			if (legacy.stream().noneMatch(agent -> agent.agentId().equals(legacyAgent))) legacyAgent = legacy.getFirst().agentId();
			addRenderableWidget(new ConsoleCycleButton<>(font, c[0], y, c[2], ROW, Component.literal("Agent"),
					legacy.stream().map(agent -> agent.agentId()).toList(), legacyAgent,
					id -> Component.literal(legacy.stream().filter(agent -> agent.agentId().equals(id)).findFirst().orElseThrow().displayName()),
					value -> legacyAgent = value)).visible = contentVisible(y, ROW);
			addRenderableWidget(button("Move to cast", c[1], y, c[2], ROW, false,
					() -> send("codex skit adopt " + word(legacyAgent))));
		}
	}

	private void addActorPicker(int x, int y, int width) {
		var actors = DirectorClientState.snapshot().map(value -> value.actors()).orElse(List.of());
		if (actors.isEmpty()) {
			selectedActor = "";
			ConsoleButton empty = button("Cast is empty", x, y, width, ROW, false, () -> { });
			empty.active = false;
			addRenderableWidget(empty);
			return;
		}
		if (actors.stream().noneMatch(actor -> actor.id().equals(selectedActor))) selectedActor = actors.getFirst().id();
		addRenderableWidget(new ConsoleCycleButton<>(font, x, y, width, ROW, Component.literal("Cast"),
				actors.stream().map(actor -> actor.id()).toList(), selectedActor, id -> {
					var actor = actors.stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
					return Component.literal(actor.name() + (actor.dead() ? " / Dead" : actor.present() ? " / Alive" : " / Offline") + (tab == Tab.VOICE && !actor.speechStatus().isEmpty() ? " / " + actor.speechStatus() : ""));
				}, value -> { selectedActor = value; drafts.put("selected-actor", value); if (tab == Tab.VOICE) rebuildWidgets(); })).visible = contentVisible(y, ROW);
	}
	private String actorTarget() { return word(selectedActor); }

	private void initActions() {
		int[] c = columns();
		int y = contentTop();
		addActorPicker(c[0], y, c[2]);
		scriptName = addEdit("Script name", "intro", c[1], y, c[2], 64, "director-script-name");
		y += ROW + GAP;
		actionDescription = addEdit("Describe the action", "Fly here, land, then wave", c[0], y, c[2] * 2 + GAP, 2048, "director-action-description");
		y += ROW + GAP;
		var generate = addRenderableWidget(primary(DirectorClientState.generationPending() ? "Luna is writing..." : "Write script with Luna", c[0], y, c[2] * 2 + GAP, ROW, this::generateScript));
		generate.active = !DirectorClientState.generationPending();
		generate.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Luna writes a new editable script. Here means your current position; the actor starts where it is. Nothing plays until you click Play.")));
		y += ROW + GAP;
		addRenderableWidget(new ConsoleCycleButton<>(font, c[0], y, c[2], ROW, Component.literal("Action"), ACTIONS,
				selectedAction, value -> Component.literal(value.equals("move") ? "Glide to my position" : value), value -> { selectedAction = value; drafts.put("selected-action", value); drafts.remove("director-action-args"); rebuildWidgets(); })).visible = contentVisible(y, ROW);
		actionDuration = addEdit("Duration (seconds)", "2", c[1], y, c[2], 8, "director-action-duration");
		y += ROW + GAP;
		actionDuration.active = !List.of("jump", "swing", "equip").contains(selectedAction);
		if (selectedAction.equals("walk")) {
			int third = (c[2] * 2 - GAP) / 3;
			walkForward = addEdit("Forward (-1 to 1)", "1", c[0], y, third, 8, "director-walk-forward");
			walkStrafe = addEdit("Strafe (-1 to 1)", "0", c[0] + third + GAP, y, third, 8, "director-walk-strafe");
			addRenderableWidget(new ConsoleCycleButton<>(font, c[0] + 2 * (third + GAP), y, third, ROW, Component.literal("Sprint"), List.of(false,true), walkSprint,
					value -> Component.literal(value ? "On" : "Off"), value -> { walkSprint = value; drafts.put("walk-sprint", value.toString()); })).visible = contentVisible(y, ROW);
		} else if (selectedAction.equals("emote")) {
			addRenderableWidget(new ConsoleCycleButton<>(font, c[0], y, c[2] * 2 + GAP, ROW, Component.literal("Crouch"), List.of(false,true), actionSneak,
					value -> Component.literal(value ? "On" : "Off"), value -> { actionSneak = value; drafts.put("action-sneak", value.toString()); })).visible = contentVisible(y, ROW);
		} else {
			actionArgs = addEdit(selectedAction.equals("equip") ? "Item ID" : "No extra settings needed", selectedAction.equals("equip") ? "minecraft:stick" : "", c[0], y, c[2] * 2 + GAP, 128, "director-action-args");
			actionArgs.active = selectedAction.equals("equip");
		}
		y += ROW + GAP;
		addRenderableWidget(primary("Create script", c[0], y, c[2], ROW, this::createScript));
		addRenderableWidget(button("Add action", c[1], y, c[2], ROW, false, () -> requestEditor("append", 0)));
		y += ROW + GAP;
		addRenderableWidget(button("Play script", c[0], y, c[2], ROW, false, () -> scriptCommand("play")));
		addRenderableWidget(button("Stop actor", c[1], y, c[2], ROW, false, () -> scriptCommand("stop")));
		initLibrary(y + ROW + GAP);
	}

	private static List<String> withSavedValue(List<String> options, String saved) {
		return options.contains(saved) ? options : java.util.stream.Stream.concat(options.stream(), java.util.stream.Stream.of(saved)).toList();
	}

	private String voiceDraftKey(String setting) { return "actor-voice:" + selectedActor + ":" + setting; }

	private String voiceLabel(String id) {
		if (!id.equals("voice.auto.v1")) return dev.agaminggod.arenaagents.server.voice.VoiceCatalog.label(id);
		return DirectorClientState.snapshot().stream().flatMap(value -> value.actors().stream()).filter(actor -> actor.id().equals(selectedActor)).findFirst()
				.map(actor -> "Default: " + dev.agaminggod.arenaagents.server.voice.VoiceCatalog.label(dev.agaminggod.arenaagents.server.voice.VoiceCatalog.defaultFor(actor.name(), actor.appearance())))
				.orElse("Character default");
	}

	private void loadActorVoice() {
		var saved = DirectorClientState.snapshot().stream().flatMap(value -> value.actors().stream()).filter(actor -> actor.id().equals(selectedActor)).findFirst()
				.map(actor -> actor.voiceProfile()).orElse(dev.agaminggod.arenaagents.server.voice.VoiceProfile.defaults());
		voice = drafts.getOrDefault(voiceDraftKey("voice"), saved.profileId());
		tone = drafts.getOrDefault(voiceDraftKey("tone"), saved.tone());
		speed = drafts.getOrDefault(voiceDraftKey("speed"), Double.toString(saved.speed()));
		radius = drafts.getOrDefault(voiceDraftKey("radius"), Integer.toString(saved.radius()));
	}

	private void initVoice() {
		int[] c = columns();
		int y = contentTop();
		addActorPicker(c[0], y, c[2]);
		loadActorVoice();
		voiceDropdown = addRenderableWidget(new ConsoleDropdown<>(font, c[1], y, c[2], ROW, "Voice", withSavedValue(VOICES, voice),
				voice, this::voiceLabel, value -> { voice = value; drafts.put(voiceDraftKey("voice"), value); }, width, height));
		voiceDropdown.visible = contentVisible(y, ROW);
		y += ROW + GAP;
		addRenderableWidget(new ConsoleCycleButton<>(font, c[0], y, c[2], ROW, Component.literal("Tone"), withSavedValue(TONES, tone),
				tone, Component::literal, value -> { tone = value; drafts.put(voiceDraftKey("tone"), value); })).visible = contentVisible(y, ROW);
		addRenderableWidget(new ConsoleCycleButton<>(font, c[1], y, c[2], ROW, Component.literal("Speed"), withSavedValue(SPEEDS, speed),
				speed, Component::literal, value -> { speed = value; drafts.put(voiceDraftKey("speed"), value); })).visible = contentVisible(y, ROW);
		y += ROW + GAP;
		addRenderableWidget(new ConsoleCycleButton<>(font, c[0], y, c[2], ROW, Component.literal("Radius"), withSavedValue(RADII, radius),
				radius, Component::literal, value -> { radius = value; drafts.put(voiceDraftKey("radius"), value); })).visible = contentVisible(y, ROW);
		voiceText = addEdit("Line", "What should they say?", c[1], y, c[2], 280, "director-voice-text");
		y += ROW + GAP;
		addRenderableWidget(primary("Set voice", c[0], y, c[2], ROW, this::setVoice));
		addRenderableWidget(button("Say line", c[1], y, c[2], ROW, false, this::sayLine));
		y += ROW + GAP;
		voiceScript = addEdit("Voice script", "dialogue", c[0], y, c[2], 64, "director-voice-script");
		voiceDelay = addEdit("Pause before line (seconds)", "0", c[1], y, c[2], 8, "director-voice-delay");
		y += ROW + GAP;
		addRenderableWidget(button("Create voice script", c[0], y, c[2], ROW, false, () -> voiceScriptCommand("create")));
		addRenderableWidget(button("Add cue", c[1], y, c[2], ROW, false, () -> requestEditor("append", 0)));
		y += ROW + GAP;
		addRenderableWidget(primary("Play voice script", c[0], y, c[2], ROW, () -> voiceScriptCommand("play")));
		addRenderableWidget(button("Stop voice", c[1], y, c[2], ROW, false, () -> send("codex skit voice script stop " + actorTarget())));
		initLibrary(y + ROW + GAP);
	}

	private String editorKind() { return tab == Tab.SPAWN ? "take" : tab == Tab.ACTIONS ? "motion" : "voice"; }
	private String editorName() { ConsoleEditBox field = editorNameField(); return field == null ? "" : field.getValue().strip(); }
	private DirectorEditorPayload.Snapshot emptyLibrary() {
		return new DirectorEditorPayload.Snapshot(new java.util.UUID(0, 0), true, "", editorKind(), "", "", List.of(), 0, 0, List.of());
	}
	private void initLibrary(int y) {
		int[] c = columns();
		var library = libraries.getOrDefault(editorKind(), emptyLibrary());
		if (!library.names().isEmpty()) {
			String name = library.names().contains(editorName()) ? editorName() : "";
			List<String> choices = new java.util.ArrayList<>(); choices.add(""); choices.addAll(library.names());
			savedScriptSelector = addRenderableWidget(new ConsoleCycleButton<>(font, c[0], y, c[2], ROW, Component.literal("Saved"), choices, name,
					value -> Component.literal(value.isEmpty() ? "Choose a script" : value), this::selectSavedScript));
			savedScriptSelector.visible = contentVisible(y, ROW);
		}
		addRenderableWidget(button("Load / refresh script", c[1], y, c[2], ROW, false, () -> { selectedRow = -1; requestEditor("read", 0); }));
		y += ROW + GAP;
		for (var row : library.rows()) {
			addRenderableWidget(button((row.index() + 1) + ". " + row.label(), c[0], y, c[2] * 2 + GAP, ROW, selectedRow == row.index(), () -> selectRow(row)));
			y += ROW + GAP;
		}
		ConsoleButton save = button("Save selected row", c[0], y, c[2], ROW, false, () -> requestEditor("replace", library.offset()));
		save.active = selectedRow >= 0;
		addRenderableWidget(save);
		ConsoleButton remove = button("Remove selected row", c[1], y, c[2], ROW, false, () -> requestEditor("remove", library.offset()));
		remove.active = selectedRow >= 0;
		addRenderableWidget(remove);
		y += ROW + GAP;
		addRenderableWidget(button("Undo last edit", c[0], y, c[2], ROW, false, () -> requestEditor("undo", library.offset())));
		addRenderableWidget(button(tab == Tab.SPAWN ? "Delete take" : "Delete script", c[1], y, c[2], ROW, false, () -> { String key = editorKind() + ":" + editorName(); if (!deleteConfirmation.equals(key)) { deleteConfirmation = key; feedback = "Click Delete again to permanently delete " + editorName(); feedbackError = true; } else { deleteConfirmation = ""; requestEditor("delete", 0); } }));
		y += ROW + GAP;
		ConsoleButton previous = button("Previous rows", c[0], y, c[2], ROW, false, () -> requestEditor("read", Math.max(0, library.offset() - DirectorEditorPayload.PAGE_SIZE)));
		previous.active = library.offset() > 0;
		addRenderableWidget(previous);
		ConsoleButton next = button("Next rows (" + library.total() + " total)", c[1], y, c[2], ROW, false, () -> requestEditor("read", library.offset() + DirectorEditorPayload.PAGE_SIZE));
		next.active = library.offset() + DirectorEditorPayload.PAGE_SIZE < library.total();
		addRenderableWidget(next);
	}
	private void selectRow(DirectorEditorPayload.Row row) {
		editorNameField().setValue(libraries.get(editorKind()).name());
		selectedRow = row.index();
		if (tab == Tab.SPAWN) {
			selectedActor = row.action(); drafts.put("selected-actor", selectedActor);
			String[] scripts = row.arguments().split("\\n", -1);
			drafts.put("director-take-motion", scripts[0]); drafts.put("director-take-voice", scripts[1]);
		} else if (tab == Tab.ACTIONS) {
			if (!ACTIONS.contains(row.action())) { feedback = "Legacy placement row: remove it or keep it unchanged"; feedbackError = true; return; }
			selectedAction = row.action(); drafts.put("selected-action", selectedAction);
			String[] parts = row.arguments().split(" ", 2);
			boolean timed = !List.of("equip", "jump", "swing").contains(selectedAction);
			if (timed) drafts.put("director-action-duration", Double.toString(Integer.parseInt(parts[0]) / 20.0));
			drafts.put("director-action-args", selectedAction.equals("equip") ? row.arguments() : "");
			if (selectedAction.equals("walk") && parts.length == 2) {
				String[] movement = parts[1].split(" ");
				drafts.put("director-walk-forward", movement[0]); drafts.put("director-walk-strafe", movement[1]);
				walkSprint = Boolean.parseBoolean(movement[2]); drafts.put("walk-sprint", movement[2]);
			} else if (selectedAction.equals("emote") && parts.length == 2) { actionSneak = Boolean.parseBoolean(parts[1]); drafts.put("action-sneak", parts[1]); }
		} else {
			drafts.put("director-voice-text", row.arguments());
			drafts.put("director-voice-delay", Double.toString(Integer.parseInt(row.action()) / 20.0));
		}
		rebuildWidgets();
	}
	private static String ticks(String seconds) {
		double value;
		try { value = Double.parseDouble(seconds); } catch (NumberFormatException exception) { throw new DirectorInputException("Enter a duration in seconds"); }
		if (!Double.isFinite(value) || value < 0 || value > 3600) throw new DirectorInputException("Duration must be between 0 and 3600 seconds");
		return Long.toString(Math.round(value * 20));
	}
	private void selectSavedScript(String value) {
		if (value.isEmpty()) return;
		String previous = editorName();
		runAction(() -> {
			// Commit the draft name and row only after the read is accepted for sending.
			requestEditor("read", 0, value, -1);
			editorNameField().setValue(value);
			selectedRow = -1;
		});
		if (!editorName().equals(value)) {
			var names = libraries.getOrDefault(editorKind(), emptyLibrary()).names();
			savedScriptSelector.setValue(names.contains(previous) ? previous : "");
		}
	}

	private void requestEditor(String operation, int offset) {
		requestEditor(operation, offset, editorName(), selectedRow);
	}

	private void requestEditor(String operation, int offset, String requestName, int requestRow) {
		if (tab == Tab.CAMERA) return;
		if (minecraft == null || minecraft.getConnection() == null) throw new DirectorInputException("Connect to a world to load saved scripts");
		if (pendingEditor != null) throw new DirectorInputException("Wait for the current edit to finish");
		var library = libraries.getOrDefault(editorKind(), emptyLibrary());
		if (!operation.equals("read") && (!library.name().equals(requestName) || library.revision().isEmpty()))
			throw new DirectorInputException("Load the saved script before editing");
		String action = "", args = "";
		if (operation.equals("append") || operation.equals("replace")) {
			if (tab == Tab.SPAWN) {
				action = selectedActor; args = takeMotion.getValue().strip() + "\n" + takeVoice.getValue().strip();
			} else if (tab == Tab.ACTIONS) {
				action = selectedAction;
				String extra = action.equals("walk") ? number(walkForward, "1") + " " + number(walkStrafe, "0") + " " + walkSprint : action.equals("emote") ? Boolean.toString(actionSneak) : number(actionArgs, "");
				args = switch (action) { case "equip" -> extra; case "jump", "swing" -> ""; case "walk", "emote" -> ticks(number(actionDuration, "2")) + " " + extra; default -> ticks(number(actionDuration, "2")); };
			} else { action = ticks(number(voiceDelay, "0")); args = voiceText.getValue(); }
		}
		var request = new DirectorEditorPayload.Request(java.util.UUID.randomUUID(), editorKind(), requestName, operation, library.revision(), requestRow, offset, action, args);
		if (!AgentControlClient.sendDirectorEditor(request)) throw new DirectorInputException("Script editing needs a connected server with editor support");
		pendingEditor = request.requestId();
		editorDeadline = System.nanoTime() + 15_000_000_000L;
		pendingEditorOperation = operation;
		feedback = "Loading script...";
		feedbackError = false;
	}
	public void acceptEditorSnapshot(DirectorEditorPayload.Snapshot snapshot) {
		if (!snapshot.requestId().equals(pendingEditor)) return;
		pendingEditor = null;
		// A conflict must be explicitly refreshed before the operator can overwrite newer work.
		if (snapshot.success()) libraries.put(snapshot.kind(), snapshot);
		else libraries.remove(snapshot.kind());
		if (snapshot.success() && List.of("remove", "delete", "undo", "read").contains(pendingEditorOperation)) selectedRow = -1;
		feedback = snapshot.message();
		feedbackError = !snapshot.success();
		acceptCatalogUpdate();
	}

	private ConsoleEditBox editorNameField() { return tab == Tab.SPAWN ? takeName : tab == Tab.ACTIONS ? scriptName : voiceScript; }
	private void initTake() {
		int[] c = columns(); int y = contentTop() + 8 * (ROW + GAP);
		takeName = addEdit("Take name", "The escape", c[0], y, c[2], 64, "director-take-name");
		addRenderableWidget(primary("Create take", c[1], y, c[2], ROW, () -> send("codex skit take create " + word(takeName.getValue()))));
		y += ROW + GAP;
		takeMotion = addEdit("Actor's action script (optional)", "entrance", c[0], y, c[2], 64, "director-take-motion");
		takeVoice = addEdit("Actor's voice script (optional)", "dialogue", c[1], y, c[2], 64, "director-take-voice");
		y += ROW + GAP;
		addRenderableWidget(button("Save actor + starting mark", c[0], y, c[2] * 2 + GAP, ROW, false, () -> requestEditor("append", 0)));
		y += ROW + GAP;
		addRenderableWidget(primary("Play take", c[0], y, c[2], ROW, () -> send("codex skit take play " + word(takeName.getValue()))));
		addRenderableWidget(button("Stop take", c[1], y, c[2], ROW, false, () -> send("codex skit take stop")));
		initLibrary(y + ROW + GAP);
	}

	private void initCamera() {
		int[] c = columns();
		int y = contentTop();
		addRenderableWidget(primary("Get tripod camera", c[0], y, c[2] * 2 + GAP, ROW, () -> send("codex skit camera kit")));
		y += ROW + GAP;
		cameraPath = addEdit("Shot name", "intro", c[0], y, c[2], 64, "director-camera-path");
		addRenderableWidget(primary("Record at camera", c[1], y, c[2], ROW, () -> cameraStart(false)));
		y += ROW + GAP;
		addRenderableWidget(button("Roll camera", c[0], y, c[2], ROW, false, () -> send(CameraDirectorClient.dollyCommand(false))));
		addRenderableWidget(button("Brake dolly", c[1], y, c[2], ROW, false, () -> send(CameraDirectorClient.dollyCommand(true))));
		y += ROW + GAP;
		addRenderableWidget(button("Save shot", c[0], y, c[2], ROW, false, CameraDirectorClient::stopRecordingFromGui));
		addRenderableWidget(button("Retake shot", c[1], y, c[2], ROW, false, () -> cameraStart(true)));
		y += ROW + GAP;
		addRenderableWidget(button("Preview shot", c[0], y, c[2], ROW, false, () -> cameraPlay(false)));
		addRenderableWidget(button("Exit camera", c[1], y, c[2], ROW, false, CameraDirectorClient::stopPlaybackFromGui));
		y += ROW + GAP;
		var names = CameraDirectorClient.pathNames();
		if (!names.isEmpty()) addRenderableWidget(new ConsoleCycleButton<>(font, c[0], y, c[2], ROW, Component.literal("Saved shot"), names,
				names.contains(cameraPath.getValue()) ? cameraPath.getValue() : names.getFirst(), Component::literal, cameraPath::setValue)).visible = contentVisible(y, ROW);
		addRenderableWidget(button("Assign shot to take", c[1], y, c[2], ROW, false, () -> send("codex skit take camera " + word(drafts.getOrDefault("director-take-name", "")) + " " + word(cameraPath.getValue()) + " " + CameraDirectorClient.pathDuration(cameraPath.getValue()))));
		y += ROW + GAP;
		addRenderableWidget(button("Remove take camera", c[0], y, c[2], ROW, false, () -> send("codex skit take camera " + word(drafts.getOrDefault("director-take-name", "")) + " - 0")));
		addRenderableWidget(button("Stop take", c[1], y, c[2], ROW, false, () -> send("codex skit take stop")));
	}


	private void cameraStart(boolean replace) {
		if (cameraPath == null) return;
		CameraDirectorClient.startDollyRecordingFromGui(cameraPath.getValue().strip(), replace);
	}

	private void cameraPlay(boolean loop) {
		if (cameraPath != null) CameraDirectorClient.playFromGui(cameraPath.getValue().strip(), loop);
	}

	private void summon() {
		String name = actorName == null ? "" : actorName.getValue().strip();
		if (name.isBlank()) {
			feedback = "Enter an actor name first";
			feedbackError = true;
			LOGGER.info("Director spawn rejected locally: actor name is blank");
			return;
		}
		send("codex skit summon " + provider + " " + word(name));
	}

	private void place(String mode) {

		String selector = actorTarget();
		String command = switch (mode) {
			case "here" -> "codex skit place " + selector + " here";
			case "relative" -> "codex skit place " + selector + " relative " + number(right, "0") + " " + number(up, "0") + " " + number(forward, "2");
			default -> "codex skit place " + selector + " look_at " + lookTarget();
		};
		send(command);
	}

	private String lookTarget() {
		if (minecraft == null || minecraft.player == null) return "~ ~ ~";
		Vec3 eye = new Vec3(minecraft.player.getX(), minecraft.player.getEyeY(), minecraft.player.getZ());
		Vec3 target = eye.add(minecraft.player.getViewVector(1.0F).scale(8.0D));
		return String.format(Locale.ROOT, "%.3f %.3f %.3f", target.x, target.y, target.z);
	}

	private void generateScript() {
		if (DirectorClientState.generationPending()) throw new DirectorInputException("Luna is still writing the previous script");
		if (scriptName == null || actionDescription == null || selectedActor.isBlank()) throw new DirectorInputException("Choose an actor and enter a description first");
		var request = new dev.agaminggod.arenaagents.control.DirectorGenerationPayload.Request(java.util.UUID.randomUUID(), java.util.UUID.fromString(selectedActor), scriptName.getValue().strip(), actionDescription.getValue().strip());
		if (!AgentControlClient.sendDirectorGeneration(request)) throw new DirectorInputException("Script generation needs a connected server with Luna support");
		DirectorClientState.beginGeneration(request);
		feedback = "Luna is writing your script..."; feedbackError = false;
		rebuildWidgets();
	}

	public void acceptGenerationResult(dev.agaminggod.arenaagents.control.DirectorGenerationPayload.Result result) {
		feedback = result.message(); feedbackError = !result.success();
		rebuildWidgets();
		if (result.success() && tab == Tab.ACTIONS && scriptName != null && result.scriptName().equals(scriptName.getValue().strip()) && pendingEditor == null) requestEditor("read", 0);
	}

	private void createScript() {
		if (scriptName != null) send("codex skit script create " + word(scriptName.getValue()) + " " + actorTarget());
	}

	private void scriptCommand(String operation) {
		if (scriptName == null && !operation.equals("stop")) return;
		String selector = selectedActor;
		send(scriptCommand(operation, scriptName == null ? "" : scriptName.getValue(), selector));
	}

	private static String scriptCommand(String operation, String script, String selector) {
		String prefix = "codex skit script " + operation + " ";
		if (operation.equals("stop")) return prefix + word(selector);
		return prefix + word(script) + (selector == null || selector.isBlank() ? "" : " " + word(selector));
	}

	private static String lineCommand(String prefix, String text) {
		if (text == null || text.isBlank()) throw new DirectorInputException("Enter a line to speak first");
		return prefix + " " + text;
	}

	private void setVoice() {

		send("codex skit voice profile " + actorTarget() + " " + voice + " " + tone + " " + speed + " " + radius);
	}

	private void sayLine() {
		if (voiceText == null) return;
		send(lineCommand("codex skit voice say_with " + actorTarget() + " " + voice + " " + tone + " " + speed + " " + radius, voiceText.getValue()));
	}

	private void voiceScriptCommand(String operation) {
		if (voiceScript == null) return;
		String name = word(voiceScript.getValue());
		if (operation.equals("create")) send("codex skit voice script create " + name + " " + actorTarget());
		else if (operation.equals("play")) send("codex skit voice script play " + name + " " + actorTarget());
		else if (voiceText != null) send(lineCommand("codex skit voice script add " + name + " " + number(voiceDelay, "0"), voiceText.getValue()));
	}

	private boolean send(String command) {
		if (pendingCommand != null && !command.contains(" stop")) throw new DirectorInputException("Wait for the current command to finish");
		var connection = minecraft == null ? null : minecraft.getConnection();
		if (connection != null) {
			var parsed = connection.getCommands().parse(command, connection.getSuggestionsProvider());
			String error = commandError(parsed);
			String path = parsed.getContext().getNodes().stream()
					.map(node -> node.getNode().getName()).collect(java.util.stream.Collectors.joining("/"));
			if (error != null) {
				feedback = error;
				feedbackError = true;
				LOGGER.warn("Director command rejected locally: path={}, cursor={}, length={}, executable={}",
						path, parsed.getReader().getCursor(), command.length(), parsed.getContext().getCommand() != null);
				return false;
			}
			LOGGER.info("Director command submitted: path={}, length={}", path, command.length());
		}
		var request = new DirectorCommandRequestPayload(java.util.UUID.randomUUID(), command);
		if (AgentControlClient.sendDirectorCommand(request)) {
			pendingCommand = request.requestId();
			commandDeadline = System.nanoTime() + 15_000_000_000L;
			feedback = "Waiting for the server...";
			feedbackError = false;
			return true;
		}
		feedback = "Director controls need a connected server with command feedback support";
		feedbackError = true;
		return false;
	}

	private static <S> String commandError(com.mojang.brigadier.ParseResults<S> parsed) {
		var error = net.minecraft.commands.Commands.getParseException(parsed);
		if (error != null) return "Check required fields and argument values";
		if (parsed.getContext().getCommand() == null) return "Complete the required fields first";
		return null;
	}

	private ConsoleEditBox addEdit(String label, String placeholder, int x, int y, int width, int limit, String identity) {
		ConsoleEditBox edit = retainedEditor;
		if (edit == null || !edit.consoleFocusIdentity().equals("input:" + identity)) {
			edit = new ConsoleEditBox(font, x, y + 10, width, ROW - 10, Component.literal(label), Component.literal(placeholder), identity);
			edit.setMaxLength(limit);
			edit.setValue(drafts.getOrDefault(identity, ""));
			edit.setResponder(value -> drafts.put(identity, value));
		}
		edit.visible = contentVisible(y, ROW);
		addRenderableWidget(edit);
		fieldLabels.put(edit, label);
		return edit;
	}

	private ConsoleButton button(String label, int x, int y, int width, int height, boolean selected, Runnable action) {
		ConsoleButton button = new ConsoleButton(font, x, y, width, height, Component.literal(label), selected, ACCENT, () -> runAction(action));
		button.visible = !buildingContent || contentVisible(y, height);
		return button;
	}

	private ConsoleButton primary(String label, int x, int y, int width, int height, Runnable action) {
		ConsoleButton button = new ConsoleButton(font, x, y, width, height, Component.literal(label), false, ACCENT, ConsoleButton.Tone.PRIMARY, () -> runAction(action));
		button.visible = contentVisible(y, height);
		return button;
	}

	private void runAction(Runnable action) {
		try {
			action.run();
		} catch (IllegalArgumentException exception) {
			feedback = exception.getMessage();
			feedbackError = true;
			LOGGER.info("Director form rejected locally: tab={}, reason={}", tab, feedback);
		}
	}

	@Override public void tick() {
		super.tick();
		DirectorClientState.takeGenerationResult().ifPresent(this::acceptGenerationResult);
		long now = System.nanoTime();
		if (pendingCommand != null && now >= commandDeadline || pendingEditor != null && now >= editorDeadline) {
			pendingCommand = null; pendingEditor = null;
			libraries.clear(); feedback = "No server response. Refresh before retrying; the last change may have saved"; feedbackError = true;
		}
	}

	@Override public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
		if (voiceDropdown != null && voiceDropdown.popupClick(event)) return true;
		return super.mouseClicked(event, doubled);
	}

	@Override public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (voiceDropdown != null && voiceDropdown.isOpen() && voiceDropdown.keyPressed(event)) return true;
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (voiceDropdown != null && voiceDropdown.popupScroll(vertical)) return true;
		if (mouseX >= panelLeft() && mouseX <= panelLeft() + panelWidth()
				&& mouseY >= panelTop() + contentOffset(panelHeight()) - GAP
				&& mouseY < contentBottom() + 4 && vertical != 0) {
			int next = Math.clamp(scrollRows + (vertical > 0 ? -1 : 1), 0, maxScrollRows());
			if (next != scrollRows) {
				scrollRows = next;
				rebuildWidgets();
				return true;
			}
		}
		return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int left = panelLeft();
		int top = panelTop();
		int right = left + panelWidth();
		int bottom = top + panelHeight();
		graphics.fill(0, 0, width, height, BACKDROP);
		graphics.fill(left - 1, top - 1, right + 1, bottom + 1, PANEL_EDGE);
		graphics.fill(left, top, right, bottom, PANEL);
		graphics.text(font, "Skit Director", left + 16, top + (compact() ? 2 : 12), TEXT, false);
		if (!compact()) {
			graphics.text(font, font.plainSubstrByWidth("Cast, actions, voice and camera", panelWidth() - 32), left + 16, top + 25, MUTED, false);
			graphics.text(font, font.plainSubstrByWidth(tab.help, panelWidth() - 32), left + 16, top + 72, ACCENT, false);
		}
		if (!feedback.isBlank()) {
			graphics.text(font, font.plainSubstrByWidth(feedback, panelWidth() - 164), left + 152, bottom - 22,
					pendingCommand != null ? MUTED : feedbackError ? ERROR : SUCCESS, false);
			if (mouseX >= left + 152 && mouseX < right - 12 && mouseY >= bottom - 30 && mouseY <= bottom)
				graphics.setTooltipForNextFrame(font, Component.literal(feedback), mouseX, mouseY);
		}
		if (maxScrollRows() > 0) {
			int trackTop = top + contentOffset(panelHeight());
			int trackHeight = Math.max(12, contentBottom() - trackTop);
			int thumb = Math.max(12, trackHeight / (maxScrollRows() + 1));
			int thumbY = trackTop + (trackHeight - thumb) * scrollRows / maxScrollRows();
			graphics.fill(right - 8, trackTop, right - 6, trackTop + trackHeight, PANEL_EDGE);
			graphics.fill(right - 8, thumbY, right - 6, thumbY + thumb, MUTED);
		}
		fieldLabels.forEach((edit, label) -> { if (edit.visible) graphics.text(font, font.plainSubstrByWidth(label, edit.getWidth()), edit.getX(), edit.getY() - 14, MUTED, false); });
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		if (voiceDropdown != null) voiceDropdown.renderPopup(graphics, mouseX, mouseY);
	}

	@Override
	public void onClose() {
		if (minecraft != null && parent != null) {
			if (parent instanceof AgentControlScreen console) AgentControlClient.snapshot().ifPresent(console::acceptSnapshot);
			minecraft.setScreen(parent);
		}
		else super.onClose();
	}

	private int panelWidth() { return Math.min(760, Math.max(300, width - 20)); }
	private int panelHeight() { return Math.min(460, Math.max(0, height - 20)); }
	private int panelLeft() { return (width - panelWidth()) / 2; }
	private int panelTop() { return (height - panelHeight()) / 2; }
	private boolean compact() { return panelHeight() < 180; }
	private static int contentOffset(int panelHeight) { return panelHeight < 180 ? 42 : 90; }
	private static int contentBottomInset(int panelHeight) { return panelHeight < 180 ? 32 : 42; }
	private int contentBottom() { return panelTop() + panelHeight() - contentBottomInset(panelHeight()); }
	private int contentTop() { return panelTop() + contentOffset(panelHeight()) - scrollRows * (ROW + GAP); }
	private int maxScrollRows() { if (!detailMode) return maxScrollRows(panelHeight(), tab == Tab.SPAWN ? 6 : 5); return maxScrollRows(panelHeight(), tab.rows + ((tab == Tab.SPAWN || tab == Tab.ACTIONS || tab == Tab.VOICE) ? 5 + libraries.getOrDefault(editorKind(), emptyLibrary()).rows().size() : 0)); }
	private static int maxScrollRows(int panelHeight, int rows) {
		int visibleRows = Math.max(1, (panelHeight - contentOffset(panelHeight) - contentBottomInset(panelHeight) + GAP) / (ROW + GAP));
		return Math.max(0, rows - visibleRows);
	}
	private boolean contentVisible(int y, int height) {
		return y >= panelTop() + contentOffset(panelHeight()) && y + height <= contentBottom();
	}
	private int[] columns() {
		int left = panelLeft() + 16;
		int total = panelWidth() - 32;
		int column = (total - GAP) / 2;
		return new int[] {left, left + column + GAP, column};
	}

	private static String word(String value) {
		if (value == null || value.isBlank()) throw new DirectorInputException("Enter the required name or actor selector");
		return StringArgumentType.escapeIfRequired(value.strip());
	}
	private static final class DirectorInputException extends IllegalArgumentException {
		private DirectorInputException(String message) { super(message); }
	}
	private String number(ConsoleEditBox edit, String fallback) {
		String value = edit == null ? "" : edit.getValue().strip();
		return value.isEmpty() ? fallback : value;
	}
	private Component title(String value) { return Component.literal(value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1)); }

	private enum Tab {
		SPAWN("Cast", "Place your cast, then save their scripts and starting marks in a take.", 12),
		ACTIONS("Actions", "Describe an action for Luna. Here means your position when you click Write.", 7),
		VOICE("Voice", "Choose a voice, delivery tone, and line without leaving the world.", 7),
		CAMERA("Camera", "Place a tripod camera on the floor. Right-click it to position and record.", 7);
		private final String label;
		private final String help;
		private final int rows;
		Tab(String label, String help, int rows) { this.label = label; this.help = help; this.rows = rows; }
	}
}
