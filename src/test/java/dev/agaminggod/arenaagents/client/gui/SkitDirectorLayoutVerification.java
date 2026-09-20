package dev.agaminggod.arenaagents.client.gui;

import dev.agaminggod.arenaagents.client.gui.widget.ConsoleButton;
import dev.agaminggod.arenaagents.client.gui.widget.ConsoleEditBox;
import dev.agaminggod.arenaagents.client.control.DirectorClientState;
import dev.agaminggod.arenaagents.control.AgentControlAgent;
import dev.agaminggod.arenaagents.control.DirectorCommandResultPayload;
import dev.agaminggod.arenaagents.control.AgentControlCatalog;
import dev.agaminggod.arenaagents.control.AgentControlModelOption;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.InputType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Exercises real director widgets without creating a window, renderer, or game loop. */
public final class SkitDirectorLayoutVerification {
	private SkitDirectorLayoutVerification() {}

	public static int verify() throws Exception {
		List<AgentControlModelOption> previous = AgentControlCatalog.currentOptions();
		try {
			AgentControlCatalog.resetRuntimeCatalog();
			SkitDirectorScreen screen = fixture();
			for (String blank : List.of("", "   ")) {
				edit(screen, "actorName").setValue(blank);
				var summon = SkitDirectorScreen.class.getDeclaredMethod("summon");
				summon.setAccessible(true);
				summon.invoke(screen);
				check(get(screen, "feedback").equals("Enter an actor name first"), "blank actor name gives actionable feedback without network access");
			}
			((ConsoleButton) screen.children().get(2)).onClick(null, false);
			check(screen.children().stream().anyMatch(child -> child.getClass().getSimpleName().equals("ConsoleDropdown")), "voice picker opens a list rather than cycling voices");
			((ConsoleButton) screen.children().getFirst()).onClick(null, false);
			int assertions = 1 + verifySimpleViews() + verifyDropdownAndGeneration() + verifySnapshotEditing() + verifyCommandResults() + verifyEditorResponses();
			for (var child : screen.children()) {
				if (child instanceof ConsoleButton button && button.getMessage().getString().equals("Place here")) {
					button.onClick(null, false);
					check(get(screen, "feedback").equals("Enter the required name or actor selector"), "empty middle selector is rejected before Brigadier can parse it");
					assertions++;
				}
			}
			for (int tab = 0; tab < 4; tab++) {
				((ConsoleButton) screen.children().get(tab)).onClick(null, false);
				int expected = screen.children().size() - 6;
				Set<String> reached = new HashSet<>();
				do {
					AbstractWidget tabButton = (AbstractWidget) screen.children().get(tab);
					AbstractWidget back = (AbstractWidget) screen.children().getLast();
					for (int index = 4; index < screen.children().size() - 2; index++) {
						AbstractWidget widget = (AbstractWidget) screen.children().get(index);
						if (!widget.visible) continue;
						check(widget.getY() >= tabButton.getBottom() + 6 && widget.getBottom() <= back.getY() - 6,
								"short-window content stays between tabs and footer");
						check(!widget.active || widget.isMouseOver(widget.getX() + 1, widget.getY() + 1),
								"visible short-window widgets remain hit-testable");
						reached.add(((ConsoleFocusTarget) widget).consoleFocusIdentity());
						assertions += 2;
					}
				} while (screen.mouseScrolled(160, 65, 0, -1));
				check(reached.size() == expected, "scrolling reaches every content control on tab " + tab);
				assertions++;
			}

			((ConsoleButton) screen.children().getFirst()).onClick(null, false);
			edit(screen, "actorName").setValue("Draft actor");
			set(screen, SkitDirectorScreen.class, "selectedActor", "Alex");
			((ConsoleButton) screen.children().get(2)).onClick(null, false);
			check(get(screen, "selectedActor").equals(""), "missing actor selection clears against the authoritative empty cast");
			edit(screen, "voiceText").setValue("A drafted line");
			screen.acceptCatalogUpdate();
			check(edit(screen, "voiceText").getValue().equals("A drafted line"), "catalog rebuild preserves voice drafts");
			((ConsoleButton) screen.children().getFirst()).onClick(null, false);
			set(screen, SkitDirectorScreen.class, "provider", "claude");
			screen.acceptCatalogUpdate();
			AgentControlCatalog.installRuntimeCatalog(List.of(new AgentControlModelOption(
					"cursor", "replacement-model", "Replacement model", List.of("high"), List.of("priority"))));
			screen.acceptCatalogUpdate();
			check(get(screen, "provider").equals("claude"), "actor appearance is independent of AI catalog changes");
			check(edit(screen, "actorName").getValue().equals("Draft actor"), "catalog replacement preserves actor drafts");
			check(screen.children().stream().noneMatch(child -> child instanceof AbstractWidget widget && widget.getMessage().getString().startsWith("Model:")), "cast creation has no AI model selector");
			return assertions + 7;
		} finally {
			AgentControlCatalog.installRuntimeCatalog(previous);
		}
	}

	private static int verifySimpleViews() throws Exception {
		DirectorClientState.clear();
		var screen = fixture();
		set(screen, SkitDirectorScreen.class, "detailMode", false);
		screen.resize(800, 500);
		int checks = 0;
		for (int tab = 0; tab < 4; tab++) {
			((ConsoleButton) screen.children().get(tab)).onClick(null, false);
			long buttons = screen.children().stream().filter(child -> child instanceof ConsoleButton button && button.visible).count();
			check(buttons <= 11, "simple tabs limit visible commands including navigation");
			check(screen.children().stream().noneMatch(child -> child instanceof AbstractWidget widget && widget.getMessage().getString().startsWith("Tone:")), "technical settings stay out of the simple view");
			checks += 2;
		}
		((ConsoleButton) screen.children().get(1)).onClick(null, false);
		edit(screen, "actionDescription").setValue("Fly here and wave");
		var open = screen.children().stream().filter(child -> child instanceof ConsoleButton button && button.getMessage().getString().equals("Saved scripts and editing")).map(ConsoleButton.class::cast).findFirst().orElseThrow();
		open.onClick(null, false);
		check(get(screen, "detailMode").equals(true) && edit(screen, "actionDescription").getValue().equals("Fly here and wave"), "opening editing preserves the natural-language draft");
		var back = screen.children().stream().filter(child -> child instanceof ConsoleButton button && button.getMessage().getString().equals("Simple view")).map(ConsoleButton.class::cast).findFirst().orElseThrow();
		back.onClick(null, false);
		check(get(screen, "detailMode").equals(false) && edit(screen, "actionDescription").getValue().equals("Fly here and wave"), "returning to simple view preserves the draft");
		return checks + 2;
	}

	private static int verifyDropdownAndGeneration() throws Exception {
		DirectorClientState.clear();
		SkitDirectorScreen screen = fixture();
		screen.resize(380, 240);
		((ConsoleButton) screen.children().get(2)).onClick(null, false);
		var dropdown = (dev.agaminggod.arenaagents.client.gui.widget.ConsoleDropdown<?>) screen.children().stream()
				.filter(child -> child instanceof dev.agaminggod.arenaagents.client.gui.widget.ConsoleDropdown).findFirst().orElseThrow();
		dropdown.onClick(null, false);
		check(dropdown.isOpen(), "click opens all voice choices without changing selection");
		check(get(screen, "voice").equals("voice.auto.v1"), "opening voices keeps character default");
		dropdown.keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, 0, 0));
		dropdown.keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0));
		check(!dropdown.isOpen() && get(screen, "voice").equals("voice.laura.v1"), "keyboard selection commits the named voice");
		dropdown.onClick(null, false);
		screen.keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0));
		check(!dropdown.isOpen() && get(screen, "voice").equals("voice.laura.v1"), "Escape dismisses voices without leaving the form or changing voice");
		dropdown.onClick(null, false);
		screen.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(0, 0, new net.minecraft.client.input.MouseButtonInfo(0, 0)), false);
		check(!dropdown.isOpen(), "outside click is consumed while dismissing the list");
		dropdown.onClick(null, false);
		check(screen.mouseScrolled(0, 0, 0, -1) && dropdown.isOpen(), "scrolling an open dropdown does not scroll or rebuild the form");
		dropdown.close();
		var id = java.util.UUID.randomUUID();
		DirectorClientState.beginGeneration(new dev.agaminggod.arenaagents.control.DirectorGenerationPayload.Request(id, java.util.UUID.randomUUID(), "intro", "Fly here"));
		check(!DirectorClientState.acceptGeneration(new dev.agaminggod.arenaagents.control.DirectorGenerationPayload.Result(java.util.UUID.randomUUID(), true, "Old result", "other")), "stale generator response cannot finish a new request");
		check(DirectorClientState.generationPending(), "pending generation survives form recreation");
		check(DirectorClientState.acceptGeneration(new dev.agaminggod.arenaagents.control.DirectorGenerationPayload.Result(id, true, "Ready", "intro")), "matching generation completes");
		check(DirectorClientState.takeGenerationResult().orElseThrow().message().equals("Ready") && DirectorClientState.takeGenerationResult().isEmpty(), "completion can be consumed once after returning to Director");
		DirectorClientState.clear();
		return 10;
	}

	private static int verifySnapshotEditing() throws Exception {
		DirectorClientState.clear();
		try {
			check(DirectorClientState.setImportCandidates(List.of(candidate("Builder", "IDLE"))), "a new import candidate updates the picker");
			check(!DirectorClientState.setImportCandidates(List.of(candidate("Builder", "RUNNING"))), "ordinary agent activity does not rebuild Director forms");
			check(DirectorClientState.setImportCandidates(List.of(candidate("Renamed builder", "RUNNING"))), "renaming an import candidate updates the picker");
			check(DirectorClientState.setImportCandidates(List.of()), "removing an import candidate updates the picker");
			int assertions = 4;
			for (InputType input : List.of(InputType.MOUSE, InputType.KEYBOARD_TAB)) {
				SkitDirectorScreen screen = fixture(input);
				screen.resize(320, 480);
				for (String field : List.of("actorName", "up", "forward")) {
					ConsoleEditBox editor = edit(screen, field);
					editor.setEditable(false); // Avoid native IME focus hooks in this headless fixture.
					editor.setValue("Draft actor");
					editor.setCursorPosition(6);
					editor.setHighlightPos(11);
					screen.setFocused(editor);
					screen.acceptCatalogUpdate();
					check(screen.getFocused() == editor && edit(screen, field) == editor, "snapshot rebuild keeps " + field + " focused for " + input);
					check(editor.getCursorPosition() == 6 && editor.getHighlighted().equals("actor"), "snapshot rebuild preserves caret and selection for " + input);
					editor.insertText("role");
					screen.acceptCatalogUpdate();
					check(edit(screen, field).getValue().equals("Draft role"), "replacement text updates the retained selection and draft after a snapshot update");
					assertions += 3;
				}
				((ConsoleButton) screen.children().get(2)).onClick(null, false);
				for (String identity : List.of("dropdown:Voice", "cycle:Tone", "cycle:Speed")) {
					AbstractWidget cycle = screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
							.filter(widget -> ConsoleFocusIdentity.of(widget).equals(identity)).findFirst().orElseThrow();
					screen.setFocused(cycle);
					screen.acceptCatalogUpdate();
					check(screen.getFocused() instanceof AbstractWidget focused && ConsoleFocusIdentity.of(focused).equals(identity), "snapshot rebuild preserves the distinct " + identity + " focus target");
					assertions++;
				}
			}
			return assertions;
		} finally {
			DirectorClientState.clear();
		}
	}

	@SuppressWarnings("unchecked")
	private static int verifyEditorResponses() throws Exception {
		SkitDirectorScreen screen = fixture();
		((ConsoleButton) screen.children().get(2)).onClick(null, false);
		edit(screen,"voiceScript").setValue("intro");
		edit(screen,"voiceText").setValue("Unsaved correction");
		var id=java.util.UUID.randomUUID();
		set(screen,SkitDirectorScreen.class,"pendingEditor",id);
		set(screen,SkitDirectorScreen.class,"pendingEditorOperation","read");
		var rows=java.util.stream.IntStream.range(0,8).mapToObj(n -> new dev.agaminggod.arenaagents.control.DirectorEditorPayload.Row(n,"Line "+n,"10","Saved line "+n)).toList();
		var snapshot=new dev.agaminggod.arenaagents.control.DirectorEditorPayload.Snapshot(id,true,"","voice","intro","a".repeat(64),List.of("intro"),9,0,rows);
		screen.acceptEditorSnapshot(snapshot);
		check(edit(screen,"voiceText").getValue().equals("Unsaved correction"),"loading a page cannot overwrite unsaved dialogue");
		var select=SkitDirectorScreen.class.getDeclaredMethod("selectRow",dev.agaminggod.arenaagents.control.DirectorEditorPayload.Row.class);select.setAccessible(true);select.invoke(screen,rows.get(2));
		check(edit(screen,"voiceText").getValue().equals("Saved line 2") && edit(screen,"voiceDelay").getValue().equals("0.5"),"selecting a saved cue loads literal text and converts its pause to seconds");
		Set<String> reached = new HashSet<>();
		do { for(var child:screen.children()) if(child instanceof AbstractWidget widget && widget.visible) reached.add(widget.getMessage().getString()); }
		while(screen.mouseScrolled(160,65,0,-1));
		check(rows.stream().allMatch(row -> reached.contains((row.index()+1)+". "+row.label())) && reached.contains("Next rows (9 total)"),"every row and pagination control is reachable in a short window");
		edit(screen,"voiceText").setValue("Keep this draft");
		var conflictId=java.util.UUID.randomUUID();set(screen,SkitDirectorScreen.class,"pendingEditor",conflictId);set(screen,SkitDirectorScreen.class,"pendingEditorOperation","replace");
		screen.acceptEditorSnapshot(snapshot);
		check(get(screen,"pendingEditor").equals(conflictId),"an old response cannot complete a newer edit");
		screen.acceptEditorSnapshot(new dev.agaminggod.arenaagents.control.DirectorEditorPayload.Snapshot(conflictId,false,"Refresh before editing","voice","intro","b".repeat(64),List.of("intro"),9,0,rows));
		check(((java.util.Map<?,?>)get(screen,"libraries")).isEmpty() && edit(screen,"voiceText").getValue().equals("Keep this draft"),"a conflict keeps the draft but requires an explicit refresh before another write");
		return 5;
	}

	private static int verifyCommandResults() throws Exception {
		SkitDirectorScreen screen = fixture();
		var pending = java.util.UUID.randomUUID();
		set(screen, SkitDirectorScreen.class, "pendingCommand", pending);
		screen.acceptCommandResult(new DirectorCommandResultPayload(java.util.UUID.randomUUID(), true, "Old result"));
		check(get(screen, "feedback").equals(""), "an unrelated response cannot overwrite pending Director feedback");
		screen.acceptCommandResult(new DirectorCommandResultPayload(pending, false, "ACTOR_NAME_TAKEN: Select the existing actor to respawn it"));
		check(get(screen, "feedbackError").equals(true) && get(screen, "feedback").toString().startsWith("ACTOR_NAME_TAKEN"), "the matching server failure is visible in the Director form");
		check(get(screen, "pendingCommand") == null, "a matching result completes the pending command");
		screen.acceptCommandResult(new DirectorCommandResultPayload(pending, true, "Late duplicate"));
		check(get(screen, "feedbackError").equals(true), "a duplicate result cannot replace an already handled failure");
		return 4;
	}

	private static AgentControlAgent candidate(String name, String state) {
		return new AgentControlAgent("12345678-1234-1234-1234-123456789abc", "12345678", name, "codex",
				"gpt-5.6-sol", "high", "SolCyan_12345678", 0, state, "", 0, "", "", true, true);
	}

	private static SkitDirectorScreen fixture() throws Exception {
		return fixture(InputType.MOUSE);
	}

	private static SkitDirectorScreen fixture(InputType input) throws Exception {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		Minecraft client = allocate(Minecraft.class);
		set(client, Minecraft.class, "lastInputType", input);
		SkitDirectorScreen screen = allocate(SkitDirectorScreen.class);
		set(screen, Screen.class, "minecraft", client);
		set(screen, Screen.class, "font", new HeadlessFont());
		set(screen, Screen.class, "title", Component.literal("Skit Director"));
		for (String field : List.of("children", "renderables", "narratables")) {
			set(screen, Screen.class, field, new ArrayList<>());
		}
		set(screen, SkitDirectorScreen.class, "drafts", new HashMap<String, String>());
		set(screen, SkitDirectorScreen.class, "fieldLabels", new HashMap<>());
		set(screen, SkitDirectorScreen.class, "libraries", new HashMap<>());
		set(screen, SkitDirectorScreen.class, "selectedRow", -1);
		set(screen, SkitDirectorScreen.class, "detailMode", true);
		set(screen, SkitDirectorScreen.class, "deleteConfirmation", "");
		Field tab = SkitDirectorScreen.class.getDeclaredField("tab");
		tab.setAccessible(true);
		tab.set(screen, tab.getType().getEnumConstants()[0]);
		for (String[] entry : new String[][] {
				{"provider", "codex"}, {"selectedActor", ""}, {"legacyAgent", ""}, {"selectedAction", "move"}, {"voice", "voice.auto.v1"},
				{"tone", "neutral"}, {"speed", "1.0"}, {"radius", "48"}, {"feedback", ""}}) {
			set(screen, SkitDirectorScreen.class, entry[0], entry[1]);
		}
		screen.width = 320;
		screen.height = 120;
		screen.init();
		return screen;
	}

	private static ConsoleEditBox edit(SkitDirectorScreen screen, String name) throws Exception {
		return (ConsoleEditBox) get(screen, name);
	}

	private static Object get(SkitDirectorScreen screen, String name) throws Exception {
		Field field = SkitDirectorScreen.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(screen);
	}

	private static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static <T> T allocate(Class<T> type) throws Exception {
		Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static final class HeadlessFont extends Font {
		private HeadlessFont() { super(null); }
		@Override public int width(String text) { return text.length() * 6; }
		@Override public String plainSubstrByWidth(String text, int width) {
			return text.substring(0, Math.min(text.length(), Math.max(0, width / 6)));
		}
		@Override public String plainSubstrByWidth(String text, int width, boolean reverse) {
			int count = Math.min(text.length(), Math.max(0, width / 6));
			return reverse ? text.substring(text.length() - count) : text.substring(0, count);
		}
	}
}
