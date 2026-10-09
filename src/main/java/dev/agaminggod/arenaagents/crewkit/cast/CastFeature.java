package dev.agaminggod.arenaagents.crewkit.cast;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentVisualIdentity;
import dev.agaminggod.arenaagents.crewkit.CrewkitAnchors;
import dev.agaminggod.arenaagents.crewkit.CrewkitFeature;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * The kitchen's people: named guests who walk in and sit on brief, and the chef's walking choreography.
 *
 * Guests are vanilla mannequins rather than Carpet fake players: a mannequin carries a name tag and a static skin
 * texture (one of vanilla's nine defaults) with no player-list entry, no Mojang profile lookup and no effect on the
 * Agent Arena roster. Movement is a server-side position step every tick, which clients interpolate (and animate
 * legs from), so nothing snaps.
 *
 * State is static because CrewkitFeatures.all() may build fresh feature instances and the chef API is static.
 */
public final class CastFeature implements CrewkitFeature {
	public static final String TAG = "crewkit";
	public static final String CAST_TAG = "ck_cast";
	/** Scoreboard team whose members render with the chef skin on every client (see AgentPlayerSkins). */
	public static final String CHEF_TEAM = AgentVisualIdentity.CREWKIT_CHEF_SKIN;

	/** Seat height above the set origin: stairs sit on the floor layer (y+1), their top is y+1.5. */
	public static final double SEAT_Y = 1.5;
	public static final double FLOOR_Y = 1.0;
	private static final double GUEST_SPEED = 0.22;
	private static final double CHEF_SPEED = 0.18;
	private static final int GUEST_STAGGER_TICKS = 12;
	private static final float TURN_PER_TICK = 24f;

	/** Vanilla default skins in fixed order, {name, model}. Guests get these by brief index unless the brief names one. */
	static final String[][] DEFAULT_SKINS = {
		{"steve", "wide"}, {"alex", "slim"}, {"ari", "wide"}, {"efe", "slim"}, {"kai", "wide"},
		{"makena", "slim"}, {"noor", "slim"}, {"sunny", "wide"}, {"zuri", "wide"},
	};

	// Kitchen positions, relative to the set origin (see docs/crewkit/kitchen-layout.html).
	private static final double[] DOOR_OUTSIDE = {28.6, 5.5};
	private static final double[] DOOR_INSIDE = {24.9, 5.5};
	/** Guests walk in along x=25.5, west of the delivery barrels at x=26. */
	private static final double ENTRY_X = 25.5;
	private static final double[] PASS = {9.5, 7.3};
	private static final double PANTRY_Z = 5.7;
	private static final double PANTRY_MIN_X = 3.5;
	private static final double PANTRY_MAX_X = 13.5;
	private static final double FRONT_AISLE_Z = 10.2;
	private static final double BACK_AISLE_Z = 15.5;
	private static final double[] SIDE_AISLES_X = {2.5, 14.0, 25.5};

	private static final List<Guest> GUESTS = new ArrayList<>();
	private static final Deque<Leg> CHEF_LEGS = new ArrayDeque<>();
	private static Leg chefLeg;
	private static String chefName;
	private static UUID chefNpc;
	/** From brief until the run ends, an agent chef's own inputs are held so they never fight the choreography. */
	private static boolean runActive;
	private static boolean releaseWhenIdle;
	private static Vec3 holdPos;
	private static boolean eastDoorsOpen;
	private static int itemsWalked;
	private static long clock;

	// ---------------------------------------------------------------- public chef API (for flow/items tracks)

	/** Inside the delivery door, where the chef meets the bag. */
	public static Vec3 doorPos() { return rel(DOOR_INSIDE[0], FLOOR_Y, DOOR_INSIDE[1]); }

	/** Behind the pass, facing the room. */
	public static Vec3 passPos() { return rel(PASS[0], FLOOR_Y, PASS[1]); }

	/** A spot in front of the counter line; slot cycles west to east so repeated trips walk the line. */
	public static Vec3 pantryPos(int slot) {
		int slots = 6;
		double t = Math.floorMod(slot, slots) / (double) (slots - 1);
		return rel(Mth.lerp(t, PANTRY_MIN_X, PANTRY_MAX_X), FLOOR_Y, PANTRY_Z);
	}

	/** The default pantry counter spot (west end of the line). */
	public static Vec3 pantryPos() { return pantryPos(0); }

	/** Interrupts any chef walk and glides the chef to {@code target} over {@code ticks} ticks. */
	public static void chefWalkTo(MinecraftServer server, Vec3 target, int ticks) {
		CHEF_LEGS.clear();
		chefLeg = null;
		CHEF_LEGS.add(new Leg(target, Math.max(1, ticks), Float.NaN));
	}

	/** Queues a walk after the chef's current one; ticks <= 0 picks a natural walking speed. */
	public static void chefQueueWalk(Vec3 target, int ticks, float endYaw) {
		CHEF_LEGS.add(new Leg(target, ticks, endYaw));
	}

	/** Queues the chef standing still for {@code ticks}. */
	public static void chefQueuePause(int ticks) {
		CHEF_LEGS.add(Leg.pause(ticks));
	}

	/** Drops queued and in-progress chef walks and releases the run hold, so a fresh placement is not undone. */
	public static void clearChefWalks() {
		CHEF_LEGS.clear();
		chefLeg = null;
		holdPos = null;
		runActive = false;
		releaseWhenIdle = false;
	}

	public static boolean chefBusy() { return chefLeg != null || !CHEF_LEGS.isEmpty(); }

	/** Assigns the chef skin to a player (agent or human) by putting it on the chef team; clears the previous chef. */
	public static void assignChef(MinecraftServer server, String playerName) {
		Scoreboard scoreboard = server.getScoreboard();
		PlayerTeam team = scoreboard.getPlayerTeam(CHEF_TEAM);
		if (team == null) team = scoreboard.addPlayerTeam(CHEF_TEAM);
		for (String member : List.copyOf(team.getPlayers())) scoreboard.removePlayerFromTeam(member, team);
		if (playerName != null) scoreboard.addPlayerToTeam(playerName, team);
		chefName = playerName;
		if (playerName != null) removeChefNpc(server);
	}

	/** Current chef player name, from memory or the persisted team. */
	public static String chefName(MinecraftServer server) {
		if (chefName != null) return chefName;
		PlayerTeam team = server.getScoreboard().getPlayerTeam(CHEF_TEAM);
		if (team == null || team.getPlayers().isEmpty()) return null;
		chefName = team.getPlayers().iterator().next();
		return chefName;
	}

	// ---------------------------------------------------------------- feature

	@Override
	public void onEvent(MinecraftServer server, String event, JsonObject data, long seq) {
		switch (event) {
			case "brief" -> {
				runActive = true;
				releaseWhenIdle = false;
				holdPos = null;
				brief(server, data);
			}
			case "item_added" -> {
				ensureChef(server);
				chefQueueWalk(pantryPos(itemsWalked++), 0, 180f);
				chefQueuePause(10);
				chefQueueWalk(passPos(), 0, 0f);
			}
			case "completed" -> {
				ensureChef(server);
				chefQueueWalk(doorPos(), 0, -90f);
				chefQueuePause(60);
				chefQueueWalk(CrewkitAnchors.at(CrewkitAnchors.AGENT), 0, ChefReady.FACING_YAW);
				for (int i = 0; i < GUESTS.size(); i++) GUESTS.get(i).lookAtPlateAt = clock + 20 + i * 4L;
				releaseWhenIdle = true;
			}
			case "failed", "expired" -> runActive = false;
			case "reset" -> reset(server);
			default -> { }
		}
	}

	@Override
	public void tick(MinecraftServer server) {
		clock++;
		ServerLevel level = server.overworld();
		for (Guest guest : GUESTS) tickGuest(level, guest);
		if (eastDoorsOpen && GUESTS.stream().allMatch(guest -> guest.seated)) setEastDoors(level, false);
		tickChef(server);
	}

	@Override
	public void reset(MinecraftServer server) {
		for (ServerLevel level : server.getAllLevels()) {
			List<Entity> doomed = new ArrayList<>();
			for (Entity entity : level.getAllEntities()) {
				if (entity.entityTags().contains(CAST_TAG)) doomed.add(entity);
			}
			for (Entity entity : doomed) {
				entity.stopRiding();
				entity.discard();
			}
		}
		GUESTS.clear();
		setEastDoors(server.overworld(), false);
		CHEF_LEGS.clear();
		chefLeg = null;
		chefNpc = null;
		itemsWalked = 0;
		runActive = false;
		releaseWhenIdle = false;
		holdPos = null;
		// The chef team (skin) stays: it belongs to the agent, not to a run.
	}

	// ---------------------------------------------------------------- guests

	private void brief(MinecraftServer server, JsonObject data) {
		resetGuests(server);
		ensureChef(server);
		JsonArray guests = data != null && data.has("guests") && data.get("guests").isJsonArray()
				? data.getAsJsonArray("guests") : new JsonArray();
		int count = Math.min(guests.size(), CrewkitAnchors.SEATS.length);
		for (int i = 0; i < count; i++) {
			JsonElement element = guests.get(i);
			String name = "Guest" + (i + 1);
			String skin = null;
			if (element.isJsonObject()) {
				JsonObject g = element.getAsJsonObject();
				if (g.has("name") && g.get("name").isJsonPrimitive()) name = g.get("name").getAsString();
				if (g.has("skin") && g.get("skin").isJsonPrimitive()) skin = g.get("skin").getAsString();
			} else if (element.isJsonPrimitive()) {
				name = element.getAsString();
			}
			Guest guest = new Guest(name, skinFor(skin, i), i);
			guest.spawnAt = clock + 1 + (long) i * GUEST_STAGGER_TICKS;
			GUESTS.add(guest);
		}
	}

	/** Brief-named vanilla skin when valid, otherwise the brief-order default (James=Steve, John=Alex, ...). */
	static String[] skinFor(String requested, int index) {
		if (requested != null) {
			String key = requested.trim().toLowerCase(Locale.ROOT);
			for (String[] skin : DEFAULT_SKINS) if (skin[0].equals(key)) return skin;
		}
		return DEFAULT_SKINS[Math.floorMod(index, DEFAULT_SKINS.length)];
	}

	private static void resetGuests(MinecraftServer server) {
		ServerLevel level = server.overworld();
		for (Guest guest : GUESTS) {
			discard(level, guest.body);
			discard(level, guest.seat);
		}
		GUESTS.clear();
	}

	private static void tickGuest(ServerLevel level, Guest guest) {
		if (guest.body == null) {
			if (clock < guest.spawnAt) return;
			spawnGuest(level, guest);
			return;
		}
		Entity body = level.getEntity(guest.body);
		if (!(body instanceof LivingEntity living)) return;
		if (!guest.seated) {
			if (guest.leg == null) guest.leg = guest.path.poll();
			if (guest.leg == null) {
				sit(level, guest, living);
				return;
			}
			if (stepLeg(living, guest.leg, false)) guest.leg = null;
			return;
		}
		if (guest.lookAtPlateAt > 0 && clock >= guest.lookAtPlateAt && guest.pitch < 40f) {
			guest.pitch = Math.min(40f, guest.pitch + 4f);
			face(living, guest.seatYaw, guest.pitch);
		}
	}

	private static void spawnGuest(ServerLevel level, Guest guest) {
		double[] seat = CrewkitAnchors.SEATS[guest.seatIndex];
		guest.seatYaw = seat[2] == 0 ? 0f : 180f;
		Vec3 seatPos = rel(seat[0], SEAT_Y, seat[1]);
		guest.seat = summon(level, "item_display", seatPos, 0f,
				"Tags:[\"" + TAG + "\",\"" + CAST_TAG + "\",\"ck_seat\"]");
		Vec3 start = rel(DOOR_OUTSIDE[0], FLOOR_Y, DOOR_OUTSIDE[1]);
		String[] skin = guest.skin;
		String nbt = "profile:{texture:\"minecraft:entity/player/" + skin[1] + "/" + skin[0] + "\",model:\"" + skin[1] + "\"}"
				+ ",CustomName:" + snbtString(guest.name) + ",CustomNameVisible:1b,hide_description:1b,immovable:1b"
				+ ",Invulnerable:1b,NoGravity:1b,Silent:1b"
				+ ",Tags:[\"" + TAG + "\",\"" + CAST_TAG + "\",\"ck_guest\"]";
		guest.body = summon(level, "mannequin", start, -90f, nbt);
		if (guest.body == null) {
			guest.seated = true; // could not spawn; stop retrying every tick
			return;
		}
		guest.path.addAll(routeToSeat(seat));
		setEastDoors(level, true);
	}

	/** Opens or shuts the east entrance doors at (27, 1..2, 5..6) so guests never walk through closed doors. */
	private static void setEastDoors(ServerLevel level, boolean open) {
		if (eastDoorsOpen == open) return;
		eastDoorsOpen = open;
		for (int y = 1; y <= 2; y++) {
			for (int z = 5; z <= 6; z++) {
				net.minecraft.core.BlockPos pos = CrewkitAnchors.origin.offset(27, y, z);
				var state = level.getBlockState(pos);
				if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)) {
					level.setBlock(pos, state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN, open), 2);
				}
			}
		}
	}

	/** Door, down the east side, along the front aisle, and round the tables through the nearest side aisle if needed. */
	private static List<Leg> routeToSeat(double[] seat) {
		List<Leg> legs = new ArrayList<>();
		legs.add(Leg.walk(rel(ENTRY_X, FLOOR_Y, DOOR_OUTSIDE[1]), GUEST_SPEED));
		legs.add(Leg.walk(rel(ENTRY_X, FLOOR_Y, FRONT_AISLE_Z), GUEST_SPEED));
		double seatX = seat[0];
		double seatZ = seat[1];
		if (seatZ < 12) {
			legs.add(Leg.walk(rel(seatX, FLOOR_Y, FRONT_AISLE_Z), GUEST_SPEED));
		} else {
			double aisle = SIDE_AISLES_X[0];
			for (double x : SIDE_AISLES_X) if (Math.abs(x - seatX) < Math.abs(aisle - seatX)) aisle = x;
			legs.add(Leg.walk(rel(aisle, FLOOR_Y, FRONT_AISLE_Z), GUEST_SPEED));
			legs.add(Leg.walk(rel(aisle, FLOOR_Y, BACK_AISLE_Z), GUEST_SPEED));
			legs.add(Leg.walk(rel(seatX, FLOOR_Y, BACK_AISLE_Z), GUEST_SPEED));
		}
		legs.add(Leg.walk(rel(seatX, FLOOR_Y, seatZ), GUEST_SPEED));
		return legs;
	}

	private static void sit(ServerLevel level, Guest guest, LivingEntity body) {
		guest.seated = true;
		Entity seat = guest.seat == null ? null : level.getEntity(guest.seat);
		boolean mounted = seat != null && body.startRiding(seat, true, true);
		if (!mounted) place(body, body.position(), guest.seatYaw, 0f); // stand at the seat rather than vanish
		face(body, guest.seatYaw, 0f);
		play(level, body.position(), SoundEvents.WOOL_PLACE, 0.6f, 1.2f);
	}

	// ---------------------------------------------------------------- chef

	private static void ensureChef(MinecraftServer server) {
		if (resolveChef(server) != null) return;
		// No assigned agent online: a chef-skinned mannequin keeps the kitchen staffed for the demo.
		ServerLevel level = server.overworld();
		String nbt = "profile:{texture:\"arenaagents:entity/crewkit_chef\",model:\"wide\"}"
				+ ",CustomName:\"Chef\",CustomNameVisible:1b,hide_description:1b,immovable:1b"
				+ ",Invulnerable:1b,NoGravity:1b,Silent:1b"
				+ ",Tags:[\"" + TAG + "\",\"" + CAST_TAG + "\",\"ck_chef\"]";
		chefNpc = summon(level, "mannequin", passPos(), 0f, nbt);
	}

	private static Entity resolveChef(MinecraftServer server) {
		String name = chefName(server);
		if (name != null) {
			ServerPlayer player = server.getPlayerList().getPlayerByName(name);
			if (player != null) {
				if (chefNpc != null) removeChefNpc(server); // the real chef is back: drop the stand-in
				return player;
			}
		}
		if (chefNpc != null) {
			Entity npc = server.overworld().getEntity(chefNpc);
			if (npc != null && npc.isAlive()) return npc;
			chefNpc = null;
		}
		return null;
	}

	private static void removeChefNpc(MinecraftServer server) {
		if (chefNpc == null) return;
		discard(server.overworld(), chefNpc);
		chefNpc = null;
	}

	private static void tickChef(MinecraftServer server) {
		if (runActive && releaseWhenIdle && !chefBusy()) {
			runActive = false;
			releaseWhenIdle = false;
		}
		if (runActive) holdAgentChef(server);
		if (chefLeg == null) {
			chefLeg = CHEF_LEGS.poll();
			if (chefLeg == null) return;
		}
		Entity chef = resolveChef(server);
		if (chef == null) {
			chefLeg = null;
			CHEF_LEGS.clear();
			return;
		}
		if (stepLeg(chef, chefLeg, true)) {
			chefLeg = null;
			holdPos = chef.position();
		}
	}

	/** Keeps an agent chef still between choreography legs while a run is on: its own movement never wins. */
	private static void holdAgentChef(MinecraftServer server) {
		Entity chef = resolveChef(server);
		if (!(chef instanceof carpet.patches.EntityPlayerMPFake fake)) return;
		dev.agaminggod.arenaagents.server.OfflineAgentPlayers.stop(fake);
		if (chefBusy()) return;
		if (holdPos == null) holdPos = fake.position();
		else if (fake.position().distanceToSqr(holdPos) > 1.0E-4) place(fake, holdPos, fake.getYRot(), 0f);
	}

	// ---------------------------------------------------------------- movement

	/** Advances one leg by a tick; returns true when done. Chef legs ease in/out, guest legs keep a steady pace. */
	private static boolean stepLeg(Entity entity, Leg leg, boolean ease) {
		if (leg.from == null) {
			leg.from = entity.position();
			if (leg.to == null) leg.to = leg.from;
			if (leg.ticks <= 0) {
				double speed = leg.speed > 0 ? leg.speed : CHEF_SPEED;
				leg.ticks = Math.max(1, (int) Math.ceil(leg.from.distanceTo(leg.to) / speed));
			}
			if (entity instanceof ServerPlayer player && player instanceof carpet.patches.EntityPlayerMPFake) {
				// Stop the agent's own held inputs so they do not fight the choreography.
				dev.agaminggod.arenaagents.server.OfflineAgentPlayers.stop(player);
			}
		}
		leg.elapsed++;
		double t = Math.min(1.0, leg.elapsed / (double) leg.ticks);
		double k = ease ? t * t * (3 - 2 * t) : t;
		Vec3 pos = leg.from.lerp(leg.to, k);
		Vec3 delta = leg.to.subtract(leg.from);
		float yaw = entity.getYRot();
		boolean done = t >= 1.0;
		if (delta.horizontalDistanceSqr() > 1.0E-4 && !done) {
			float travelYaw = (float) (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG) - 90f;
			yaw = Mth.approachDegrees(yaw, travelYaw, TURN_PER_TICK);
		} else if (!Float.isNaN(leg.endYaw)) {
			yaw = Mth.approachDegrees(yaw, leg.endYaw, TURN_PER_TICK);
			// Hold the leg open until the final turn finishes so the chef does not spin on the next walk.
			if (done && Math.abs(Mth.wrapDegrees(yaw - leg.endYaw)) > 0.5f) done = false;
		}
		place(entity, pos, yaw, 0f);
		return done;
	}

	private static void place(Entity entity, Vec3 pos, float yaw, float pitch) {
		if (entity instanceof ServerPlayer player && !(player instanceof carpet.patches.EntityPlayerMPFake)) {
			// A human chef (testing) needs a real teleport so their own client moves.
			player.teleportTo(player.level(), pos.x, pos.y, pos.z, Set.<Relative>of(), yaw, pitch, false);
		} else {
			entity.snapTo(pos.x, pos.y, pos.z, yaw, pitch);
			entity.setDeltaMovement(Vec3.ZERO);
		}
		entity.setYHeadRot(yaw);
		if (entity instanceof LivingEntity living) living.setYBodyRot(yaw);
	}

	private static void face(Entity entity, float yaw, float pitch) {
		entity.setYRot(yaw);
		entity.setXRot(pitch);
		entity.setYHeadRot(yaw);
		if (entity instanceof LivingEntity living) living.setYBodyRot(yaw);
	}

	// ---------------------------------------------------------------- helpers

	private static Vec3 rel(double x, double y, double z) {
		var o = CrewkitAnchors.origin;
		return new Vec3(o.getX() + x, o.getY() + y, o.getZ() + z);
	}

	/** Spawns through /summon (the documented NBT path for mannequin profiles) and returns the new entity's UUID. */
	private static UUID summon(ServerLevel level, String type, Vec3 pos, float yaw, String extraNbt) {
		String marker = "ck_spawn_" + UUID.randomUUID().toString().replace("-", "");
		String nbt = "{" + extraNbt.replace("Tags:[", "Tags:[\"" + marker + "\",") + ",Rotation:[" + yaw + "f,0f]}";
		String command = String.format(Locale.ROOT, "summon minecraft:%s %.4f %.4f %.4f %s", type, pos.x, pos.y, pos.z, nbt);
		var source = level.getServer().createCommandSourceStack().withLevel(level).withSuppressedOutput();
		level.getServer().getCommands().performPrefixedCommand(source, command);
		for (Entity entity : level.getAllEntities()) {
			if (entity.entityTags().contains(marker)) {
				entity.removeTag(marker);
				return entity.getUUID();
			}
		}
		return null;
	}

	private static void discard(ServerLevel level, UUID id) {
		if (id == null) return;
		Entity entity = level.getEntity(id);
		if (entity == null) return;
		entity.ejectPassengers();
		entity.stopRiding();
		entity.discard();
	}

	private static void play(ServerLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
		level.playSound(null, at.x, at.y, at.z, sound, SoundSource.MASTER, volume, pitch);
	}

	private static String snbtString(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private static final class Guest {
		final String name;
		final String[] skin;
		final int seatIndex;
		final Deque<Leg> path = new ArrayDeque<>();
		long spawnAt;
		long lookAtPlateAt;
		UUID body;
		UUID seat;
		Leg leg;
		boolean seated;
		float seatYaw;
		float pitch;

		Guest(String name, String[] skin, int seatIndex) {
			this.name = name;
			this.skin = skin;
			this.seatIndex = seatIndex;
		}
	}

	private static final class Leg {
		Vec3 from;
		Vec3 to;
		int ticks;
		int elapsed;
		double speed;
		final float endYaw;

		Leg(Vec3 to, int ticks, float endYaw) {
			this.to = to;
			this.ticks = ticks;
			this.endYaw = endYaw;
		}

		static Leg walk(Vec3 to, double speed) {
			Leg leg = new Leg(to, 0, Float.NaN);
			leg.speed = speed;
			return leg;
		}

		static Leg pause(int ticks) {
			return new Leg(null, Math.max(1, ticks), Float.NaN);
		}
	}
}
