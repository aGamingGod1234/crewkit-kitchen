package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.control.DirectorEditorPayload.*;
import dev.agaminggod.arenaagents.control.DirectorTakePlaybackPayload;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.voice.*;
import java.util.*;

public final class DirectorEditorVerification {
	public static int verify() {
		var pose = new SkitPlacement("minecraft:overworld", 1,64,3,20,0);
		var target = new SkitPlacement("minecraft:overworld", 10,64,9,40,0);
		var original = new SkitScript("intro","Alex",List.of(new SkitStep(5,pose,List.of(SkitAction.move(40)),false)));
		var changed = (SkitScript) DirectorScriptEditor.mutate(original,request("motion","replace",0,"move","60"),target);
		check(changed.steps().getFirst().placement().equals(pose),"editing a duration must preserve its recorded destination");
		check(changed.steps().getFirst().delayTicks()==5 && changed.steps().getFirst().actions().getFirst().durationTicks()==60,"editing preserves delay and changes duration");
		var appended = (SkitScript) DirectorScriptEditor.mutate(changed,request("motion","append",-1,"wait","20"),target);
		check(!appended.steps().getLast().placeBeforeActions(),"new wait does not snap the actor back to an old placement");
		check(original.steps().getFirst().actions().getFirst().durationTicks()==40,"edits leave active script snapshots unchanged");
		DirectorScriptEditor.requireRevision(original,DirectorScriptEditor.revision(original));
		reject(() -> DirectorScriptEditor.requireRevision(changed,DirectorScriptEditor.revision(original)),"stale revision cannot overwrite another edit");
		reject(() -> DirectorScriptEditor.mutate(original,request("motion","replace",9,"wait","20"),target),"missing row is rejected");
		reject(() -> DirectorScriptEditor.mutate(original,request("motion","append",-1,"equip","minecraft:no_such_item"),target),"bad item is rejected before it enters saved work");
		var voice = new VoiceScript("intro","Alex",List.of(new VoiceCue(0,"Old","voice.ember.v1","warm",.75,64)));
		var editedVoice = (VoiceScript) DirectorScriptEditor.mutate(voice,request("voice","replace",0,"10","He said \"Go!\""),target);
		check(editedVoice.cues().getFirst().tone().equals("warm") && editedVoice.cues().getFirst().speed()==.75,"line edits retain per-cue delivery overrides");
		check(editedVoice.cues().getFirst().text().equals("He said \"Go!\""),"quotes remain literal speech");
		check(CodexAgentCommands.parseSkitAction("move","40").type()==SkitAction.Type.MOVE,"single duration move retains glide behavior");
		var walk=CodexAgentCommands.parseSkitAction("move","40 -1 .5 true");
		check(walk.type()==SkitAction.Type.WALK && walk.forward()==-1 && walk.strafe()==.5 && walk.sprint(),"explicit movement inputs select physical walking");
		reject(() -> CodexAgentCommands.parseSkitAction("jump","anything"),"unused action arguments are not silently ignored");
		var actor = AgentId.random();
		var take = new DirectorTake("The escape",List.of(),"",0);
		var assigned = (DirectorTake)DirectorScriptEditor.mutate(take,request("take","append",-1,actor.toString(),"intro\ndialogue"),pose);
		var reassigned = (DirectorTake)DirectorScriptEditor.mutate(assigned,request("take","replace",0,actor.toString(),"intro\ncorrected"),target);
		check(reassigned.tracks().size()==1 && reassigned.tracks().getFirst().start().equals(pose),"editing scripts retains the actor's recorded starting mark");
		var remarked = (DirectorTake)DirectorScriptEditor.mutate(reassigned,request("take","append",-1,actor.toString(),"intro\ncorrected"),target);
		check(remarked.tracks().size()==1 && remarked.tracks().getFirst().start().equals(target),"saving actor and starting mark replaces its track without duplicate actors");
		reject(() -> new DirectorTake("bad",List.of(assigned.tracks().getFirst(),assigned.tracks().getFirst()),"",0),"duplicate cast assignments are rejected");
		reject(() -> new DirectorTake("bad",List.of(),"camera",0),"camera duration must be playable");
		check(assigned.remove(0).tracks().isEmpty() && assigned.tracks().size()==1,"track removal preserves the original for undo");
		var cameraTake = reassigned.camera("doorway",80);
		var json = DirectorTake.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE,cameraTake).getOrThrow();
		check(cameraTake.equals(DirectorTake.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,json).getOrThrow()),"take and marks survive persistence");
		try {
			var f=SkitModeSavedData.class.getDeclaredField("CODEC");f.setAccessible(true);
			@SuppressWarnings("unchecked") var codec=(com.mojang.serialization.Codec<SkitModeSavedData>)f.get(null);
			var old=codec.parse(com.mojang.serialization.JsonOps.INSTANCE,com.google.gson.JsonParser.parseString("{\"enabled\":true}")).getOrThrow();
			check(old.takes().isEmpty(),"old worlds open with an empty take library");
			old.putTake(cameraTake);old.putScript(appended);
			var restored=codec.parse(com.mojang.serialization.JsonOps.INSTANCE,codec.encodeStart(com.mojang.serialization.JsonOps.INSTANCE,old).getOrThrow()).getOrThrow();
			check(restored.take(cameraTake.name()).equals(cameraTake) && !restored.script("intro").steps().getLast().placeBeforeActions(),"world codec saves take and non-teleport actions together");
		} catch(ReflectiveOperationException e) { throw new AssertionError(e); }
		return 20 + packets();
	}
	private static int packets() {
		var b=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),net.minecraft.core.RegistryAccess.EMPTY);
		try {
			var request=request("take","append",-1,AgentId.random().toString(),"intro\ndialogue");
			Request.CODEC.encode(b,request);check(request.equals(Request.CODEC.decode(b)),"take assignment request preserves stable actor id and scripts");
			var snapshot=new Snapshot(request.requestId(),true,"Saved","voice","intro","a".repeat(64),List.of("intro"),1,0,List.of(new Row(0,"Line","10","He said \"Go!\"")));
			Snapshot.CODEC.encode(b,snapshot);check(snapshot.equals(Snapshot.CODEC.decode(b)),"editor page retains revision and literal speech");
			var playback=new DirectorTakePlaybackPayload("The escape","doorway",123,"Starting in 3 seconds");
			DirectorTakePlaybackPayload.CODEC.encode(b,playback);check(playback.equals(DirectorTakePlaybackPayload.CODEC.decode(b)),"camera receives the shared world deadline");
		} finally {b.release();}
		reject(() -> new Request(UUID.randomUUID(),"voice","intro","append","",0,512,"0","Line"),"page offsets are bounded");
		reject(() -> new Snapshot(UUID.randomUUID(),true,"","voice","intro","",List.of(),9,0,Collections.nCopies(9,new Row(0,"","",""))),"oversized pages cannot be constructed");
		return 5;
	}
	private static Request request(String kind,String operation,int index,String action,String args) { return new Request(UUID.randomUUID(),kind,"intro",operation,"",index,0,action,args); }
	private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
	private static void reject(Runnable action,String message) { try {action.run();throw new AssertionError(message);}catch(IllegalArgumentException|IndexOutOfBoundsException expected){} }
}
