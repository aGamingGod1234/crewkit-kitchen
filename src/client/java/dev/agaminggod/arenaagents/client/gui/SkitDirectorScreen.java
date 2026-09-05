package dev.agaminggod.arenaagents.client.gui;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.client.camera.CameraDirectorClient;
import dev.agaminggod.arenaagents.client.control.AgentControlClient;
import dev.agaminggod.arenaagents.client.gui.widget.ConsoleButton;
import dev.agaminggod.arenaagents.client.gui.widget.ConsoleCycleButton;
import dev.agaminggod.arenaagents.client.gui.widget.ConsoleEditBox;
import dev.agaminggod.arenaagents.control.AgentControlCatalog;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/** Point-and-click controls for skit actors, actions, voices, and cinematic camera paths. */
public final class SkitDirectorScreen extends Screen {
	private static final int PANEL = ConsoleTheme.PANEL;
	private static final int PANEL_EDGE = ConsoleTheme.BORDER;
	private static final int BACKDROP = ConsoleTheme.BACKDROP;
	private static final int TEXT = ConsoleTheme.TEXT;
	private static final int MUTED = ConsoleTheme.MUTED;
	private static final int ACCENT = ConsoleTheme.ACCENT;
	private static final int SUCCESS = ConsoleTheme.SUCCESS;
	private static final int ERROR = ConsoleTheme.ERROR;
	private static final int ROW = 24;
	private static final int GAP = 6;
	private static final List<String> PROVIDERS = List.of("codex", "claude", "gemini", "kimi", "cursor");
	private static final List<String> ACTIONS = List.of("move", "wait", "jump", "equip", "use", "swing", "emote");
	private static final List<String> TONES = List.of("neutral", "warm", "excited", "serious", "dramatic", "whisper", "robotic", "angry");
	private static final List<String> SPEEDS = List.of("0.75", "1.0", "1.25", "1.5");
	private static final List<String> RADII = List.of("16", "32", "48", "64", "96");
	private static final List<String> VOICES = List.of(
			"voice.auto.v1", "voice.moss.v1", "voice.flint.v1", "voice.ember.v1", "voice.wren.v1",
			"voice.cedar.v1", "voice.sable.v1", "voice.quill.v1", "voice.rook.v1", "voice.juniper.v1",
			"voice.vale.v1", "voice.kestrel.v1", "voice.sol.v1", "voice.reed.v1", "voice.nova.v1",
			"voice.ash.v1", "voice.piper.v1");

	private final Screen parent;
	private Tab tab = Tab.SPAWN;
	private String provider = "codex";
	private String model = AgentControlCatalog.defaultModel("codex");
	private String selectedAction = "move";
	private String voice = VOICES.getFirst();
	private String tone = "neutral";
	private String speed = "1.0";
	private String radius = "48";
	private String feedback = "";
	private boolean feedbackError;
	private ConsoleEditBox actorName;
	private ConsoleEditBox actorSelector;
	private ConsoleEditBox scriptName;
	private ConsoleEditBox actionArgs;
	private ConsoleEditBox right;
	private ConsoleEditBox up;
	private ConsoleEditBox forward;
	private ConsoleEditBox voiceText;
	private ConsoleEditBox voiceScript;
	private ConsoleEditBox voiceDelay;
	private ConsoleEditBox cameraPath;

	public SkitDirectorScreen(Screen parent) {
		super(Minecraft.getInstance(), ConsoleFont.create(Minecraft.getInstance()),
				Component.literal("Skit Director"));
		this.parent = parent;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		clearFields();
		int left = panelLeft();
		int top = panelTop();
		int width = panelWidth();
		int tabWidth = (width - GAP * 3) / 4;
		for (int index = 0; index < Tab.values().length; index++) {
			Tab value = Tab.values()[index];
			int x = left + index * (tabWidth + GAP);
			addRenderableWidget(button(value.label, x, top + 42,
					index == 3 ? left + width - x : tabWidth, ROW, value == tab,
					() -> { tab = value; rebuildWidgets(); }));
		}
		switch (tab) {
			case SPAWN -> initSpawn();
			case ACTIONS -> initActions();
			case VOICE -> initVoice();
			case CAMERA -> initCamera();
		}
		addRenderableWidget(button("Back to console", left, panelTop() + panelHeight() - 30, 140, ROW, false, this::onClose));
	}

	private void clearFields() {
		actorName = actorSelector = scriptName = actionArgs = right = up = forward = null;
		voiceText = voiceScript = voiceDelay = cameraPath = null;
	}

	private void initSpawn() {
		int[] c = columns();
		int y = contentTop();
		addRenderableWidget(new ConsoleCycleButton<>(font, c[0], y, c[2], ROW, Component.literal("Provider"),
				PROVIDERS, provider, this::title, value -> { provider = value; model = models(value).getFirst(); rebuildWidgets(); }));
		addRenderableWidget(new ConsoleCycleButton<>(font, c[1], y, c[2], ROW, Component.literal("Model"),
				models(provider), model, value -> Component.literal(displayModel(provider, value)), value -> model = value));
		y += ROW + GAP;
		actorName = addEdit("Actor name", "GPT 5.6-Sol", c[0], y, c[2], AgentConstants.MAX_USER_NAME_LENGTH, "director-actor-name");
		actorSelector = addEdit("Agent selector", "agent name or @e", c[1], y, c[2], 64, "director-actor-selector");
		y += ROW + GAP;
		ConsoleButton mode = button("Enable skit mode", c[0], y, c[2], ROW, false, () -> send("codex skit on", "Skit mode enabled"));
		addRenderableWidget(mode);
		addRenderableWidget(primary("Spawn at my position", c[1], y, c[2], ROW, this::summon));
		y += ROW + GAP;
		right = addEdit("Right", "0", c[0], y, (c[2] - GAP) / 2, 24, "director-right");
		up = addEdit("Up", "0", c[0] + (c[2] + GAP) / 2, y, (c[2] - GAP) / 2, 24, "director-up");
		y += ROW + GAP;
		forward = addEdit("Forward", "2", c[0], y, c[2], 24, "director-forward");
		addRenderableWidget(button("Place here", c[1], y, c[2], ROW, false, () -> place("here")));
		y += ROW + GAP;
		addRenderableWidget(button("Place relative to me", c[0], y, c[2], ROW, false, () -> place("relative")));
		addRenderableWidget(button("Face where I look", c[1], y, c[2], ROW, false, () -> place("look_at")));
	}

	private void initActions() {
		int[] c = columns();
		int y = contentTop();
		actorSelector = addEdit("Agent selector", "agent name or @e", c[0], y, c[2], 64, "director-action-selector");
		scriptName = addEdit("Script name", "intro", c[1], y, c[2], 64, "director-script-name");
		y += ROW + GAP;
		addRenderableWidget(new ConsoleCycleButton<>(font, c[0], y, c[2], ROW, Component.literal("Action"), ACTIONS,
				selectedAction, Component::literal, value -> selectedAction = value));
		actionArgs = addEdit("Action args", "move: 40 1 0 true", c[1], y, c[2], 96, "director-action-args");
		y += ROW + GAP;
		addRenderableWidget(primary("Create script", c[0], y, c[2], ROW, this::createScript));
		addRenderableWidget(button("Add action", c[1], y, c[2], ROW, false, this::addAction));
		y += ROW + GAP;
		addRenderableWidget(button("Play script", c[0], y, c[2], ROW, false, () -> scriptCommand("play")));
		addRenderableWidget(button("Stop actor", c[1], y, c[2], ROW, false, () -> scriptCommand("stop")));
	}

	private void initVoice() {
		int[] c = columns();
		int y = contentTop();
		actorSelector = addEdit("Agent selector", "agent name or @e", c[0], y, c[2], 64, "director-voice-selector");
		addRenderableWidget(new ConsoleCycleButton<>(font, c[1], y, c[2], ROW, Component.literal("Voice"), VOICES,
				voice, Component::literal, value -> voice = value));
		y += ROW + GAP;
		addRenderableWidget(new ConsoleCycleButton<>(font, c[0], y, c[2], ROW, Component.literal("Tone"), TONES,
				tone, Component::literal, value -> tone = value));
		addRenderableWidget(new ConsoleCycleButton<>(font, c[1], y, c[2], ROW, Component.literal("Speed"), SPEEDS,
				speed, Component::literal, value -> speed = value));
		y += ROW + GAP;
		addRenderableWidget(new ConsoleCycleButton<>(font, c[0], y, c[2], ROW, Component.literal("Radius"), RADII,
				radius, Component::literal, value -> radius = value));
		voiceText = addEdit("Line", "What should they say?", c[1], y, c[2], 280, "director-voice-text");
		y += ROW + GAP;
		addRenderableWidget(primary("Set voice", c[0], y, c[2], ROW, this::setVoice));
		addRenderableWidget(button("Say line", c[1], y, c[2], ROW, false, this::sayLine));
		y += ROW + GAP;
		voiceScript = addEdit("Voice script", "dialogue", c[0], y, c[2], 64, "director-voice-script");
		voiceDelay = addEdit("Delay ticks", "0", c[1], y, c[2], 8, "director-voice-delay");
		y += ROW + GAP;
		addRenderableWidget(button("Create voice script", c[0], y, c[2], ROW, false, () -> voiceScriptCommand("create")));
		addRenderableWidget(button("Add cue", c[1], y, c[2], ROW, false, () -> voiceScriptCommand("add")));
		y += ROW + GAP;
		addRenderableWidget(primary("Play voice script", c[0], y, c[2], ROW, () -> voiceScriptCommand("play")));
		addRenderableWidget(button("Stop voice", c[1], y, c[2], ROW, false, () -> send("codex skit voice script stop " + word(actorSelector.getValue()), "Voice stopped")));
	}

	private void initCamera() {
		int[] c = columns();
		int y = contentTop();
		cameraPath = addEdit("Path name", "intro", c[0], y, c[2], 64, "director-camera-path");
		addRenderableWidget(button("Start recording", c[1], y, c[2], ROW, false, () -> cameraStart(false)));
		y += ROW + GAP;
		addRenderableWidget(primary("Capture keyframe", c[0], y, c[2], ROW, CameraDirectorClient::recordKeyframeFromGui));
		addRenderableWidget(button("Save recording", c[1], y, c[2], ROW, false, CameraDirectorClient::stopRecordingFromGui));
		y += ROW + GAP;
		addRenderableWidget(button("Play once", c[0], y, c[2], ROW, false, () -> cameraPlay(false)));
		addRenderableWidget(button("Play loop", c[1], y, c[2], ROW, false, () -> cameraPlay(true)));
		y += ROW + GAP;
		addRenderableWidget(button("Stop camera", c[0], y, c[2], ROW, false, CameraDirectorClient::stopPlaybackFromGui));
		addRenderableWidget(button("Start a new take", c[1], y, c[2], ROW, false, () -> cameraStart(true)));
	}

	private void cameraStart(boolean replace) {
		if (cameraPath == null) return;
		CameraDirectorClient.startRecordingFromGui(cameraPath.getValue().strip(), replace);
	}

	private void cameraPlay(boolean loop) {
		if (cameraPath != null) CameraDirectorClient.playFromGui(cameraPath.getValue().strip(), loop);
	}

	private void summon() {
		String name = actorName == null ? "" : actorName.getValue().strip();
		send("codex skit summon " + provider + " model " + word(model) + " " + word(name), "Spawning " + name);
	}

	private void place(String mode) {
		if (actorSelector == null) return;
		String selector = word(actorSelector.getValue().strip());
		String command = switch (mode) {
			case "here" -> "codex skit place " + selector + " here";
			case "relative" -> "codex skit place " + selector + " relative " + number(right, "0") + " " + number(up, "0") + " " + number(forward, "2");
			default -> "codex skit place " + selector + " look_at " + lookTarget();
		};
		send(command, "Placement sent");
	}

	private String lookTarget() {
		if (minecraft == null || minecraft.player == null) return "~ ~ ~";
		Vec3 eye = new Vec3(minecraft.player.getX(), minecraft.player.getEyeY(), minecraft.player.getZ());
		Vec3 target = eye.add(minecraft.player.getViewVector(1.0F).scale(8.0D));
		return String.format(Locale.ROOT, "%.3f %.3f %.3f", target.x, target.y, target.z);
	}

	private void createScript() {
		if (scriptName != null && actorSelector != null) send("codex skit script create " + word(scriptName.getValue()) + " " + word(actorSelector.getValue()), "Script created");
	}

	private void addAction() {
		if (scriptName == null) return;
		String args = actionArgs == null ? "" : actionArgs.getValue().strip();
		if (args.startsWith(selectedAction + ":")) args = args.substring(selectedAction.length() + 1).strip();
		send("codex skit script action " + word(scriptName.getValue()) + " " + selectedAction + (args.isBlank() ? "" : " " + args), "Action added");
	}

	private void scriptCommand(String operation) {
		if (scriptName == null) return;
		String selector = actorSelector == null ? "" : actorSelector.getValue().strip();
		send("codex skit script " + operation + " " + word(scriptName.getValue()) + (operation.equals("stop") ? " " + word(selector) : ""), "Script " + operation + " sent");
	}

	private void setVoice() {
		if (actorSelector == null) return;
		send("codex skit voice profile " + word(actorSelector.getValue()) + " " + voice + " " + tone + " " + speed + " " + radius, "Voice profile set");
	}

	private void sayLine() {
		if (actorSelector == null || voiceText == null) return;
		send("codex skit voice say " + word(actorSelector.getValue()) + " " + StringArgumentType.escapeIfRequired(voiceText.getValue().strip()), "Line sent");
	}

	private void voiceScriptCommand(String operation) {
		if (voiceScript == null || actorSelector == null) return;
		String name = word(voiceScript.getValue());
		if (operation.equals("create")) send("codex skit voice script create " + name + " " + word(actorSelector.getValue()), "Voice script created");
		else if (operation.equals("play")) send("codex skit voice script play " + name + " " + word(actorSelector.getValue()), "Voice script playing");
		else if (voiceText != null) send("codex skit voice script add " + name + " " + number(voiceDelay, "0") + " " + StringArgumentType.escapeIfRequired(voiceText.getValue().strip()), "Cue added");
	}

	private boolean send(String command, String message) {
		if (AgentControlClient.sendCommand(command)) {
			feedback = message;
			feedbackError = false;
			return true;
		}
		feedback = "Not connected to a compatible server";
		feedbackError = true;
		return false;
	}

	private ConsoleEditBox addEdit(String label, String placeholder, int x, int y, int width, int limit, String identity) {
		ConsoleEditBox edit = new ConsoleEditBox(font, x, y, width, ROW, Component.literal(label), Component.literal(placeholder), identity);
		edit.setMaxLength(limit);
		addRenderableWidget(edit);
		return edit;
	}

	private ConsoleButton button(String label, int x, int y, int width, int height, boolean selected, Runnable action) {
		return new ConsoleButton(font, x, y, width, height, Component.literal(label), selected, ACCENT, action);
	}

	private ConsoleButton primary(String label, int x, int y, int width, int height, Runnable action) {
		return new ConsoleButton(font, x, y, width, height, Component.literal(label), false, ACCENT, ConsoleButton.Tone.PRIMARY, action);
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
		graphics.text(font, "Skit Director", left + 16, top + 12, TEXT, false);
		graphics.text(font, "Place actors, compose motion, direct voices, and record camera takes", left + 16, top + 25, MUTED, false);
		graphics.text(font, tab.help, left + 16, contentTop() - 15, ACCENT, false);
		if (!feedback.isBlank()) graphics.text(font, feedback, left + 16, bottom - 15, feedbackError ? ERROR : SUCCESS, false);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public void onClose() {
		if (minecraft != null && parent != null) minecraft.setScreen(parent);
		else super.onClose();
	}

	private int panelWidth() { return Math.min(760, Math.max(300, width - 20)); }
	private int panelHeight() { return Math.min(390, Math.max(250, height - 20)); }
	private int panelLeft() { return (width - panelWidth()) / 2; }
	private int panelTop() { return (height - panelHeight()) / 2; }
	private int contentTop() { return panelTop() + 78; }
	private int[] columns() {
		int left = panelLeft() + 16;
		int total = panelWidth() - 32;
		int column = (total - GAP) / 2;
		return new int[] {left, left + column + GAP, column};
	}

	private String word(String value) { return StringArgumentType.escapeIfRequired(value == null ? "" : value.strip()); }
	private String number(ConsoleEditBox edit, String fallback) {
		String value = edit == null ? "" : edit.getValue().strip();
		return value.matches("[-+]?\\d+(\\.\\d+)?") ? value : fallback;
	}
	private List<String> models(String selectedProvider) {
		String catalogProvider = selectedProvider.equals("claude") ? "gemini" : selectedProvider;
		try { return AgentControlCatalog.models(catalogProvider); }
		catch (IllegalArgumentException ignored) { return List.of("gpt-5.6-luna"); }
	}
	private String displayModel(String selectedProvider, String selectedModel) {
		String catalogProvider = selectedProvider.equals("claude") ? "gemini" : selectedProvider;
		return AgentControlCatalog.displayName(catalogProvider, selectedModel);
	}
	private Component title(String value) { return Component.literal(value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1)); }

	private enum Tab {
		SPAWN("Spawn", "Summon a branded actor and place it where you are staging the shot."),
		ACTIONS("Actions", "Build a reusable movement script one action at a time."),
		VOICE("Voice", "Choose a voice, delivery tone, and line without leaving the world."),
		CAMERA("Camera", "Record smooth keyframes, then play the take once or on a loop.");
		private final String label;
		private final String help;
		Tab(String label, String help) { this.label = label; this.help = help; }
	}
}
