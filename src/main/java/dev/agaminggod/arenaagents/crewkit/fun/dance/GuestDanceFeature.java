package dev.agaminggod.arenaagents.crewkit.fun.dance;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.crewkit.CrewkitFeature;
import dev.agaminggod.arenaagents.crewkit.cast.CastFeature;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitDispatcher;
import dev.agaminggod.arenaagents.crewkit.fun.music.LobbyMusicFeature;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;

/**
 * Seated guests groove to the lobby music: a head bob on every beat and a slow left-right sway around
 * their seated facing, each guest a little off the beat so it reads human. The outro grooves harder and
 * guests take turns "raising the roof" with a note burst. The mannequin chef nods along while idle.
 * Heads go back to level (or to the plates after completed) when the music stops.
 *
 * Runs after CastFeature in the tick order, so during the outro the dance overrides the plate look.
 */
public final class GuestDanceFeature implements CrewkitFeature {
	private static final String GUEST_TAG = "ck_guest";
	private static final String CHEF_TAG = "ck_chef";
	private static final int SCAN_EVERY = 20;
	private static final int ROOF_EVERY = 40;
	private static final int ROOF_TICKS = 10;
	/** CastFeature tips heads down this far to look at the plates after completed. */
	private static final float PLATE_PITCH = 40f;

	private static final class Dancer {
		final float baseYaw;
		final double offset;
		final double swayOffset;
		int roofUntil;

		Dancer(float baseYaw, Random random) {
			this.baseYaw = baseYaw;
			this.offset = random.nextDouble() * 0.15;
			this.swayOffset = random.nextDouble() * 0.6;
		}
	}

	private static final Map<UUID, Dancer> GUESTS = new HashMap<>();
	private static final List<UUID> CHEFS = new ArrayList<>();
	private static final Random RANDOM = new Random();
	private static int clock;
	private static boolean dancing;
	private static boolean completed;
	private static int nextRoof;

	@Override
	public void onEvent(MinecraftServer server, String event, JsonObject data, long seq) {
		switch (event) {
			case "brief" -> completed = false;
			case "completed" -> completed = true;
			case "reset" -> reset(server);
			default -> {}
		}
	}

	@Override
	public void tick(MinecraftServer server) {
		try {
			clock++;
			ServerLevel level = server.overworld();
			boolean playing = LobbyMusicFeature.isPlaying();
			if (!playing) {
				if (dancing) restore(level);
				dancing = false;
				return;
			}
			if (!dancing || clock % SCAN_EVERY == 0) scan(level);
			dancing = true;
			dance(level);
		} catch (RuntimeException e) {
			dancing = false;
			CrewkitDispatcher.LOGGER.debug("CrewKit dance tick failed", e);
		}
	}

	@Override
	public void reset(MinecraftServer server) {
		try {
			if (dancing) restore(server.overworld());
		} catch (RuntimeException e) {
			CrewkitDispatcher.LOGGER.debug("CrewKit dance reset failed", e);
		}
		GUESTS.clear();
		CHEFS.clear();
		dancing = false;
		completed = false;
	}

	/** Picks up newly seated guests (base yaw stored on first sight) and the mannequin chef. */
	private static void scan(ServerLevel level) {
		CHEFS.clear();
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof Mannequin)) continue;
			if (entity.entityTags().contains(GUEST_TAG)) {
				if (entity.isPassenger() && !GUESTS.containsKey(entity.getUUID())) {
					GUESTS.put(entity.getUUID(), new Dancer(entity.getYRot(), RANDOM));
				}
			} else if (entity.entityTags().contains(CHEF_TAG)) {
				CHEFS.add(entity.getUUID());
			}
		}
	}

	private static void dance(ServerLevel level) {
		boolean outro = LobbyMusicFeature.isOutro();
		double beats = LobbyMusicFeature.beatCount() + LobbyMusicFeature.beatPhase();
		float bobAmp = outro ? 20f : 12f;
		float swayAmp = outro ? 14f : 10f;
		double swayBeats = outro ? 2.0 : 4.0; // full left-right cycle: 2 beats each way, faster in the outro

		boolean roofNow = outro && clock >= nextRoof && !GUESTS.isEmpty();
		if (roofNow) nextRoof = clock + ROOF_EVERY;
		List<UUID> gone = null;
		List<Entity> roofCandidates = roofNow ? new ArrayList<>() : null;

		for (Map.Entry<UUID, Dancer> entry : GUESTS.entrySet()) {
			Entity entity = level.getEntity(entry.getKey());
			if (!(entity instanceof LivingEntity living) || !entity.isPassenger()) {
				if (gone == null) gone = new ArrayList<>();
				gone.add(entry.getKey());
				continue;
			}
			Dancer d = entry.getValue();
			double local = beats + d.offset;
			double p = local - Math.floor(local);
			// Dip at the downbeat, ease back up through the beat.
			double dip = 0.5 * (1 + Math.cos(2 * Math.PI * p));
			float pitch = (float) (bobAmp * dip);
			float sway = (float) (swayAmp * Math.sin(2 * Math.PI * (local / swayBeats + d.swayOffset)));
			if (clock < d.roofUntil) pitch = -30f;
			face(living, d.baseYaw + sway, pitch, d.baseYaw + sway * 0.4f);
			if (roofCandidates != null) roofCandidates.add(entity);
		}
		if (gone != null) for (UUID id : gone) GUESTS.remove(id);

		if (roofCandidates != null && !roofCandidates.isEmpty()) {
			Entity raiser = roofCandidates.get(RANDOM.nextInt(roofCandidates.size()));
			Dancer d = GUESTS.get(raiser.getUUID());
			if (d != null) d.roofUntil = clock + ROOF_TICKS;
			level.sendParticles(ParticleTypes.NOTE, raiser.getX(), raiser.getY() + 2.3, raiser.getZ(), 8, 0.45, 0.25, 0.45, 1.0);
		}

		if (!CastFeature.chefBusy()) {
			for (UUID id : CHEFS) {
				if (!(level.getEntity(id) instanceof Mannequin chef)) continue;
				double p = beats - Math.floor(beats);
				float nod = (float) ((outro ? 10f : 6f) * 0.5 * (1 + Math.cos(2 * Math.PI * p)));
				chef.setXRot(nod);
			}
		}
	}

	/** Heads back to the seated facing: level, or down at the plates once the order is complete. */
	private static void restore(ServerLevel level) {
		float pitch = completed ? PLATE_PITCH : 0f;
		for (Map.Entry<UUID, Dancer> entry : GUESTS.entrySet()) {
			if (level.getEntity(entry.getKey()) instanceof LivingEntity living) {
				float yaw = entry.getValue().baseYaw;
				face(living, yaw, pitch, yaw);
			}
		}
		for (UUID id : CHEFS) {
			if (level.getEntity(id) instanceof Mannequin chef) chef.setXRot(0f);
		}
	}

	private static void face(LivingEntity entity, float headYaw, float pitch, float bodyYaw) {
		entity.setYRot(bodyYaw);
		entity.setXRot(pitch);
		entity.setYHeadRot(headYaw);
		entity.setYBodyRot(bodyYaw);
	}
}
