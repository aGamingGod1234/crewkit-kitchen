package dev.agaminggod.arenaagents.server.voice;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

final class DirectorSpeechVerification {
	static int verify() {
		var first = new CompletableFuture<VoiceReceipt>();
		var second = new CompletableFuture<VoiceReceipt>();
		var spoken = new java.util.ArrayList<String>();
		var run = new DirectorSpeechPlayback(List.of(new VoiceCue(0, "First."), new VoiceCue(2, "Second.")), 0);
		Function<VoiceCue, CompletionStage<VoiceReceipt>> speak = cue -> {
			spoken.add(cue.text()); return spoken.size() == 1 ? first : second;
		};
		check(!run.tick(0, speak), "first cue starts");
		check(!run.tick(100, speak), "slow synthesis does not complete the cue");
		check(spoken.equals(List.of("First.")), "later lines cannot replace pending speech");
		first.complete(new VoiceReceipt(VoiceReceipt.Status.PLAYED, "Done"));
		check(!run.tick(101, speak), "pause starts after audible completion");
		run.tick(102, speak);
		check(spoken.size() == 1, "pause is not shortened by synthesis time");
		run.tick(103, speak);
		check(spoken.equals(List.of("First.", "Second.")), "next cue starts at its completion-relative deadline");
		check(!run.tick(200, speak), "final audible line stays tracked for cancellation");
		second.complete(new VoiceReceipt(VoiceReceipt.Status.PLAYED, "Done"));
		check(run.tick(201, speak), "script completes only after final audio");
		var failed = new DirectorSpeechPlayback(List.of(new VoiceCue(0, "First."), new VoiceCue(0, "Second.")), 0);
		failed.tick(0, cue -> CompletableFuture.completedFuture(VoiceReceipt.degraded("Voice unavailable")));
		check(failed.tick(1, cue -> { throw new AssertionError("failed cue must not advance"); }), "failure ends this run");
		check(failed.result().message().equals("Voice unavailable"), "failure remains visible");
		var timeout = new DirectorSpeechPlayback(List.of(new VoiceCue(0, "First.")), 0);
		timeout.tick(0, cue -> new CompletableFuture<>());
		check(timeout.tick(3600, speak), "hung receipt has a bounded wait");
		check(timeout.result().status() == VoiceReceipt.Status.FAILED, "timeout is a failure");
		return 12 + editing();
	}

	@SuppressWarnings("unchecked")
	private static int editing() {
		var original = new VoiceScript("intro", "Claude", List.of(new VoiceCue(0, "First."), new VoiceCue(20, "Second.")));
		var data = new VoiceDirectorSavedData();
		data.createScript(original);
		try { data.createScript(new VoiceScript("intro", "Claude", List.of())); throw new AssertionError("duplicate creation must fail"); }
		catch (dev.agaminggod.arenaagents.agent.AgentDomainException expected) { }
		check(original.equals(data.script("intro")), "failed create preserves saved content");
		var changed = original.replace(1, new VoiceCue(10, "Corrected.")).remove(0);
		check(changed.cues().getFirst().text().equals("Corrected."), "editing and removing preserve the chosen cue");
		check(original.cues().size() == 2, "editing leaves the previous version intact");
		data.putScript(changed);
		try {
			var field = VoiceDirectorSavedData.class.getDeclaredField("CODEC"); field.setAccessible(true);
			var codec = (com.mojang.serialization.Codec<VoiceDirectorSavedData>) field.get(null);
			var restored = codec.parse(com.mojang.serialization.JsonOps.INSTANCE,
					codec.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, data).getOrThrow()).getOrThrow();
			check(changed.equals(restored.script("intro")), "edited dialogue survives the production codec round trip");
		} catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
		var id = dev.agaminggod.arenaagents.agent.AgentId.random();
		data.putProfile(id, new VoiceProfile("voice.ember.v1")); data.removeProfile(id);
		check(data.profiles().isEmpty(), "removing an actor frees its profile slot");
		check(data.removeScript("intro"), "deleting dialogue frees its library slot");
		var motion = new dev.agaminggod.arenaagents.server.SkitModeSavedData();
		var pose = new dev.agaminggod.arenaagents.server.SkitPlacement("minecraft:overworld", 0, 64, 0, 0, 0);
		var script = new dev.agaminggod.arenaagents.server.SkitScript("intro", "Alex", List.of(new dev.agaminggod.arenaagents.server.SkitStep(0, pose)));
		motion.createScript(script);
		try { motion.createScript(new dev.agaminggod.arenaagents.server.SkitScript("intro", "Alex", List.of())); throw new AssertionError("duplicate creation must fail"); }
		catch (dev.agaminggod.arenaagents.agent.AgentDomainException expected) { }
		check(script.equals(motion.script("intro")), "duplicate creation preserves movement");
		return 9;
	}
	private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
