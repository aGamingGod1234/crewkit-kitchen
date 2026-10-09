package dev.agaminggod.arenaagents.crewkit.core;

import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * One-call sound helper for kitchen motions (ids from docs/crewkit/sounds.md).
 *
 * Vanilla positional sounds fade to silence 16 blocks out, and the demo camera sits ~22 blocks from the
 * back-wall boards. So each player gets the sound placed on the line from them to the source, at most
 * {@link #MAX_AUDIBLE_DISTANCE} blocks away: it keeps its direction but stays audible at the camera.
 */
public final class CrewkitSounds {
	private static final double MAX_AUDIBLE_DISTANCE = 8.0;

	private CrewkitSounds() {}

	public static void play(MinecraftServer server, Vec3 pos, String soundId, float volume, float pitch) {
		if (server == null || pos == null || soundId == null) return;
		Identifier id = Identifier.tryParse(soundId.contains(":") ? soundId : "minecraft:" + soundId);
		if (id == null) return;
		Holder<SoundEvent> sound = Holder.direct(SoundEvent.createVariableRangeEvent(id));
		long seed = server.overworld().getRandom().nextLong();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.level() != server.overworld()) continue;
			Vec3 ear = player.getEyePosition();
			Vec3 offset = pos.subtract(ear);
			double distance = offset.length();
			Vec3 at = distance > MAX_AUDIBLE_DISTANCE ? ear.add(offset.scale(MAX_AUDIBLE_DISTANCE / distance)) : pos;
			player.connection.send(new ClientboundSoundPacket(sound, SoundSource.MASTER, at.x, at.y, at.z, volume, clampPitch(pitch), seed));
		}
	}

	private static float clampPitch(float pitch) {
		return Math.max(0.5f, Math.min(2.0f, pitch));
	}
}
