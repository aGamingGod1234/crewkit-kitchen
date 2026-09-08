package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.control.DirectorEditorPayload.*;
import dev.agaminggod.arenaagents.control.DirectorEditorPayload;
import dev.agaminggod.arenaagents.server.voice.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Edits immutable scripts with a revision check and one undo per operator. */
public final class DirectorScriptEditor {
	private record Undo(String kind, String name, Object before, Object after) { }
	private static final Map<MinecraftServer, Map<UUID, Undo>> UNDO = new HashMap<>();
	private DirectorScriptEditor() { }

	public static Snapshot execute(ServerPlayer player, Request request) {
		MinecraftServer server = player.level().getServer();
		if (!GoalControl.mayControl(player.createCommandSourceStack())) return empty(request, "You do not have permission to edit skits");
		try {
			if (!request.operation().equals("read")) {
				SkitModeRuntime.requireEnabled(server);
				Object before = script(server, request.kind(), request.name());
				if (before == null) throw new IllegalArgumentException("Select a saved script first");
				requireRevision(before, request.revision());
				Object after;
				if (request.operation().equals("undo")) {
					Undo undo = Optional.ofNullable(UNDO.get(server)).map(values -> values.get(player.getUUID())).orElse(null);
					if (undo == null || !undo.kind().equals(request.kind()) || !undo.name().equals(request.name()) || !before.equals(undo.after()))
						throw new IllegalArgumentException("No unchanged edit to undo for this script");
					after = undo.before();
					UNDO.get(server).remove(player.getUUID());
				} else {
					SkitPlacement mark = SkitPlacement.fromPlayer(player);
					if (request.kind().equals("take") && (request.operation().equals("append") || request.operation().equals("replace"))) {
						var actor = SkitActors.resolve(server, request.action());
						String[] refs = request.arguments().split("\\n", -1);
						if (refs.length != 2 || !refs[0].isEmpty() && SkitModeSavedData.get(server).script(refs[0]) == null || !refs[1].isEmpty() && VoiceDirectorSavedData.get(server).script(refs[1]) == null) throw new IllegalArgumentException("Choose existing action and voice scripts; leave unused fields empty");
						mark = SkitPlacement.fromPlayer(SkitActors.find(server, actor.agentId()).filter(ServerPlayer::isAlive).orElseThrow(() -> new IllegalArgumentException("Respawn the actor before saving its starting mark")));
					}
					after = mutate(before, request, mark);
					if (after != null) UNDO.computeIfAbsent(server, ignored -> new HashMap<>()).put(player.getUUID(), new Undo(request.kind(), request.name(), before, after));
				}
				if (request.kind().equals("take")) {
					if (after == null) SkitModeSavedData.get(server).removeTake(request.name());
					else SkitModeSavedData.get(server).putTake((DirectorTake) after);
				} else if (request.kind().equals("motion")) {
					if (after == null) SkitModeSavedData.get(server).removeScript(request.name());
					else SkitModeSavedData.get(server).putScript((SkitScript) after);
				} else {
					if (after == null) VoiceDirectorSavedData.get(server).removeScript(request.name());
					else VoiceDirectorSavedData.get(server).putScript((VoiceScript) after);
				}
			}
			return snapshot(server, request, true, request.operation().equals("read") ? "" : "Saved");
		} catch (IllegalArgumentException | IndexOutOfBoundsException exception) {
			return snapshot(server, request, false, exception.getMessage() == null ? "That row no longer exists. Refresh the script" : exception.getMessage());
		}
	}

	static Object mutate(Object before, Request request, SkitPlacement destination) {
		if (request.operation().equals("delete")) return null;
		if (before instanceof DirectorTake take) {
			if (request.operation().equals("remove")) return take.remove(request.index());
			String[] scripts = request.arguments().split("\\n", -1);
			if (scripts.length != 2) throw new IllegalArgumentException("Select an action script and/or voice script");
			var track = new DirectorTake.Track(dev.agaminggod.arenaagents.agent.AgentId.parse(request.action()), scripts[0], scripts[1], destination);
			if (request.operation().equals("replace")) {
				var previous = take.tracks().get(request.index());
				if (!previous.actor().equals(track.actor())) throw new IllegalArgumentException("Select the same actor to update this track");
				track = new DirectorTake.Track(track.actor(),track.motion(),track.voice(),previous.start());
			}
			return take.assign(track);
		}
		if (before instanceof SkitScript script) {
			if (request.operation().equals("remove")) return script.remove(request.index());
			SkitAction action = CodexAgentCommands.parseSkitAction(request.action(), request.arguments());
			if (action.type() == SkitAction.Type.EQUIP && !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(net.minecraft.resources.Identifier.parse(action.itemId())))
				throw new IllegalArgumentException("Unknown item: " + action.itemId());
			SkitStep step = new SkitStep(0, destination, List.of(action), false);
			if (request.operation().equals("append")) return script.append(step);
			SkitStep previous = script.steps().get(request.index());
			if (previous.actions().size() > 1) throw new IllegalArgumentException("This legacy row contains several actions. Remove it and add individual rows to edit it");
			return script.replace(request.index(), new SkitStep(previous.delayTicks(),
					previous.actions().isEmpty() || previous.actions().getFirst().type() != action.type() ? destination : previous.placement(), List.of(action), previous.placeBeforeActions()));
		}
		VoiceScript script = (VoiceScript) before;
		if (request.operation().equals("remove")) return script.remove(request.index());
		int delay;
		try { delay = Integer.parseInt(request.action()); } catch (NumberFormatException exception) { throw new IllegalArgumentException("Pause must be a whole number of ticks"); }
		VoiceCue cue = new VoiceCue(delay, request.arguments());
		if (request.operation().equals("append")) return script.append(cue);
		VoiceCue previous = script.cues().get(request.index());
		return script.replace(request.index(), new VoiceCue(delay, request.arguments(), previous.profileId(), previous.tone(), previous.speed(), previous.radius()));
	}

	static void requireRevision(Object script, String expected) {
		if (!revision(script).equals(expected)) throw new IllegalArgumentException("This script changed since you opened it. Refresh before editing");
	}
	static String revision(Object script) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(script.toString().getBytes(StandardCharsets.UTF_8))); }
		catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
	}
	private static Object script(MinecraftServer server, String kind, String name) {
		return kind.equals("take") ? SkitModeSavedData.get(server).take(name) : kind.equals("motion") ? SkitModeSavedData.get(server).script(name) : VoiceDirectorSavedData.get(server).script(name);
	}
	private static Snapshot snapshot(MinecraftServer server, Request request, boolean success, String message) {
		List<String> names = request.kind().equals("take") ? SkitModeSavedData.get(server).takes().stream().map(DirectorTake::name).toList() : request.kind().equals("motion") ? SkitModeSavedData.get(server).scripts().stream().map(SkitScript::name).toList()
				: VoiceDirectorSavedData.get(server).scripts().stream().map(VoiceScript::name).toList();
		Object script = script(server, request.kind(), request.name());
		List<Row> rows = new ArrayList<>();
		int total = script instanceof DirectorTake t ? t.tracks().size() : script instanceof SkitScript s ? s.steps().size() : script instanceof VoiceScript s ? s.cues().size() : 0;
		int offset = Math.min(request.offset(), Math.max(0, total - 1) / DirectorEditorPayload.PAGE_SIZE * DirectorEditorPayload.PAGE_SIZE);
		for (int i=offset; i<Math.min(total,offset+DirectorEditorPayload.PAGE_SIZE); i++) {
			if (script instanceof DirectorTake take) {
				var track = take.tracks().get(i);
				String actor = SkitActors.records(server).stream().filter(a -> a.agentId().equals(track.actor())).map(SkitActor::name).findFirst().orElse("Missing actor");
				rows.add(new Row(i, actor + " | Actions: " + track.motion() + " | Voice: " + track.voice(),track.actor().toString(),track.motion()+"\n"+track.voice()));
			} else if (script instanceof SkitScript s) {
				SkitStep step = s.steps().get(i);
				if (step.actions().size() != 1) { rows.add(new Row(i, step.actions().isEmpty() ? "Place at saved position" : "Legacy sequence · " + step.actions().size() + " actions", "sequence", "")); continue; }
				SkitAction action = step.actions().getFirst();
				String args = arguments(action);
				rows.add(new Row(i, action.type().name().toLowerCase(Locale.ROOT) + " · " + action.durationTicks()/20.0 + " sec"
						+ (action.type()==SkitAction.Type.MOVE ? " to " + Math.round(step.placement().x()) + ", " + Math.round(step.placement().y()) + ", " + Math.round(step.placement().z()) : ""), action.type().name().toLowerCase(Locale.ROOT), args));
			} else {
				VoiceCue cue = ((VoiceScript)script).cues().get(i);
				String label = cue.delayTicks()/20.0 + " sec pause · " + cue.text();
				rows.add(new Row(i, label.substring(0,Math.min(256,label.length())), Integer.toString(cue.delayTicks()), cue.text()));
			}
		}
		if (success && script instanceof DirectorTake take) message = "Take: " + take.name() + " | Camera: " + (take.cameraPath().isEmpty() ? "none" : take.cameraPath());
		return new Snapshot(request.requestId(),success,message,request.kind(),request.name(),script==null?"":revision(script),names,total,offset,rows);
	}
	private static String arguments(SkitAction action) {
		return switch(action.type()) {
			case EQUIP -> action.itemId();
			case JUMP, SWING -> "";
			case WALK -> action.durationTicks()+" "+action.forward()+" "+action.strafe()+" "+action.sprint();
			case EMOTE -> action.durationTicks()+" "+action.sneak();
			default -> Integer.toString(action.durationTicks());
		};
	}
	private static Snapshot empty(Request request,String message) {
		return new Snapshot(request.requestId(),false,message,request.kind(),request.name(),"",List.of(),0,0,List.of());
	}
	public static void release(MinecraftServer server) { UNDO.remove(server); }
}
