package dev.agaminggod.arenaagents.client.pov;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agaminggod.arenaagents.agent.AgentIdentity;
import dev.agaminggod.arenaagents.client.camera.CameraDirectorClient;
import dev.agaminggod.arenaagents.client.control.AgentControlClient;
import dev.agaminggod.arenaagents.client.mixin.CameraEyeHeightAccessor;
import dev.agaminggod.arenaagents.client.pov.screen.PovScreens;
import dev.agaminggod.arenaagents.pov.AgentPovMenuPayload;
import dev.agaminggod.arenaagents.pov.AgentPovPosePayload;
import dev.agaminggod.arenaagents.pov.AgentPovStatePayload;
import dev.agaminggod.arenaagents.pov.PovDeath;
import dev.agaminggod.arenaagents.pov.PovIdentity;
import dev.agaminggod.arenaagents.pov.PovStopPayload;
import dev.agaminggod.arenaagents.pov.PovVitals;
import java.util.Optional;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;

/** Client side of /spectate and /takeover: session state, camera binding, hand source and the exit key. */
public final class PovClient {
	// Category is a record, so an equal id joins AgentControlClient's registered "Arena Agents" group.
	private static final KeyMapping EXIT_VIEW = new KeyMapping(
			"key.arenaagents.exit_agent_view",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_UNKNOWN,
			new KeyMapping.Category(Identifier.fromNamespaceAndPath("arenaagents", "controls"))
	);
	// Poses arrive every server tick; this long without any payload means the server stopped streaming.
	private static final int SILENT_SERVER_TICKS = 40;
	private static final PovSessionTracker TRACKER = new PovSessionTracker();
	private static final PovBodyMonitor BODY = new PovBodyMonitor();
	private static final PovHands HANDS = new PovHands();
	private static boolean registered;
	private static AgentPovStatePayload latestState;
	private static AgentPovMenuPayload latestMenu;
	private static float attackStrength = 1.0F;
	private static int poseFlags;
	private static int silentTicks;
	private static ClientLevel sessionLevel;
	private static boolean cameraCaptured;
	private static Entity previousCamera;
	private static CameraType previousCameraType;
	private static Marker signalAnchor;
	private static boolean hasLastPosition;
	private static double lastX;
	private static double lastEyeY;
	private static double lastZ;

	private PovClient() {
	}

	public static synchronized void register() {
		if (registered) return;
		KeyMappingHelper.registerKeyMapping(EXIT_VIEW);
		if (!ClientPlayNetworking.registerGlobalReceiver(AgentPovStatePayload.TYPE,
				(payload, context) -> context.client().execute(() -> acceptState(context.client(), payload))))
			throw new IllegalStateException("Agent POV state receiver is already registered");
		if (!ClientPlayNetworking.registerGlobalReceiver(AgentPovPosePayload.TYPE,
				(payload, context) -> context.client().execute(() -> acceptPose(payload))))
			throw new IllegalStateException("Agent POV pose receiver is already registered");
		if (!ClientPlayNetworking.registerGlobalReceiver(AgentPovMenuPayload.TYPE,
				(payload, context) -> context.client().execute(() -> acceptMenu(payload))))
			throw new IllegalStateException("Agent POV menu receiver is already registered");
		if (!ClientPlayNetworking.registerGlobalReceiver(PovStopPayload.TYPE,
				(payload, context) -> context.client().execute(() -> acceptStop(context.client(), payload))))
			throw new IllegalStateException("Agent POV stop receiver is already registered");
		ClientTickEvents.END_CLIENT_TICK.register(PovClient::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> endLocal(client)));
		ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> {
			if (TRACKER.session().isPresent() && sessionLevel != null && level != sessionLevel) endLocal(client);
		});
		registered = true;
	}

	public static Optional<PovClientSession> session() {
		return TRACKER.session();
	}

	public static boolean isTakeover() {
		return TRACKER.session().map(PovClientSession::takeover).orElse(false);
	}

	public static boolean signalLost() {
		return TRACKER.phase() == PovSessionTracker.Phase.SIGNAL_LOST;
	}

	/** Asks the server to end the view; permission checks and the actual exit stay server-side. */
	public static void requestExit() {
		Minecraft client = Minecraft.getInstance();
		PovClientSession current = TRACKER.session().orElse(null);
		if (current == null) return;
		if (client.getConnection() != null) client.getConnection().sendCommand(current.takeover() ? "takeover exit" : "spectate exit");
		// With the stream already silent no stop payload will come, so the exit is local only.
		if (client.getConnection() == null || silentTicks >= SILENT_SERVER_TICKS) endLocal(client);
	}

	public static Optional<PovVitals> vitals() {
		return Optional.ofNullable(latestState).map(AgentPovStatePayload::vitals);
	}

	public static Optional<PovDeath> death() {
		return Optional.ofNullable(latestState).flatMap(AgentPovStatePayload::death);
	}

	public static Optional<AgentPovStatePayload> latestState() {
		return Optional.ofNullable(latestState);
	}

	public static Optional<AgentPovMenuPayload> latestMenu() {
		return Optional.ofNullable(latestMenu);
	}

	/** The last server look reset that {@link PovLook} has adopted; echo it with takeover input. */
	public static int lookResetSeq() {
		return TRACKER.lookResetSeq();
	}

	public static float attackStrength() {
		return attackStrength;
	}

	public static int poseFlags() {
		return poseFlags;
	}

	public static boolean isActive() {
		return TRACKER.session().isPresent();
	}

	/**
	 * The agent's in-level client entity while it is what the camera shows. Null without a session,
	 * while the signal is lost or while the agent is dead, so first-person hands draw nothing then.
	 */
	public static AbstractClientPlayer agentPlayer() {
		if (TRACKER.phase() != PovSessionTracker.Phase.ACTIVE) return null;
		if (latestState != null && latestState.death().isPresent()) return null;
		Minecraft client = Minecraft.getInstance();
		return client.getCameraEntity() instanceof AbstractClientPlayer player && player != client.player
				&& PovView.isTarget(player) && !player.isRemoved() && !player.isDeadOrDying()
				? player : null;
	}

	/**
	 * Binds that happen outside the tick loop (state payload, respawn) can be followed by frames before
	 * the next client tick; seeding the bob there keeps the hands level with the agent's look at once.
	 */
	private static void seedHands(Player agent) {
		if (agent != null && !HANDS.seeded()) HANDS.tick(agent.getViewXRot(1.0F), agent.getViewYRot(1.0F));
	}

	/** Smoothed agent look for the first-person hands; advanced once per client tick. */
	public static PovHands hands() {
		return HANDS;
	}

	/** Called after vanilla rebuilt the local player; keeps the view in the same level, ends it otherwise. */
	public static void afterRespawn(Minecraft client) {
		PovClientSession current = TRACKER.session().orElse(null);
		if (current == null) return;
		if (client.level == null || client.player == null || (sessionLevel != null && client.level != sessionLevel)) {
			endLocal(client);
			return;
		}
		// Vanilla pointed the camera at the new local player; that is now what exit restores.
		if (cameraCaptured) previousCamera = client.player;
		seedHands(bindCamera(client, current));
	}

	private static void acceptState(Minecraft client, AgentPovStatePayload payload) {
		PovIdentity identity = payload.identity();
		PovClientSession next = new PovClientSession(identity.sessionId(), identity.mode(), identity.agentUuid(), identity.agentName());
		PovSessionTracker.StateResult result = TRACKER.acceptState(next, identity.revision(), identity.lookResetSeq());
		if (result == PovSessionTracker.StateResult.STALE) return;
		silentTicks = 0;
		if (result == PovSessionTracker.StateResult.SWITCHED) PovScreens.closeAll();
		if (result != PovSessionTracker.StateResult.UPDATED) begin(client, next);
		latestState = payload;
		PovHudProxy.update(client, next, payload);
		PovScreens.onState(next, payload);
		if (result != PovSessionTracker.StateResult.UPDATED && client.level != null && client.player != null) {
			seedHands(bindCamera(client, next));
		}
	}

	private static void acceptPose(AgentPovPosePayload payload) {
		PovSessionTracker.PoseResult result = TRACKER.acceptPose(payload.sessionId());
		if (result == PovSessionTracker.PoseResult.IGNORED) return;
		silentTicks = 0;
		PovView.acceptPose(payload.yaw(), payload.pitch());
		attackStrength = payload.attackStrength();
		poseFlags = payload.flags();
		if (result == PovSessionTracker.PoseResult.LOOK_RESET) PovLook.reset(payload.yaw(), payload.pitch());
	}

	private static void acceptMenu(AgentPovMenuPayload payload) {
		PovClientSession current = TRACKER.session().orElse(null);
		if (current == null || !TRACKER.matches(payload.sessionId())) return;
		silentTicks = 0;
		latestMenu = payload;
		PovScreens.onMenu(current, payload);
	}

	private static void acceptStop(Minecraft client, PovStopPayload payload) {
		if (!TRACKER.acceptStop(payload.sessionId())) return;
		endLocal(client);
		if (client.gui != null && payload.reason() != null && !payload.reason().isBlank())
			client.gui.setOverlayMessage(Component.literal(payload.reason()), false);
	}

	// Entry for a new session or a switch to another agent; camera ownership is captured only once.
	private static void begin(Minecraft client, PovClientSession next) {
		if (!cameraCaptured) {
			CameraDirectorClient.stopForExternalCamera(client);
			previousCamera = client.getCameraEntity();
			previousCameraType = client.options.getCameraType();
			cameraCaptured = true;
		}
		if (sessionLevel == null) sessionLevel = client.level;
		latestMenu = null;
		attackStrength = 1.0F;
		poseFlags = 0;
		hasLastPosition = false;
		discardAnchor();
		PovView.reset();
		BODY.reset();
		HANDS.reset();
		Player agent = client.level == null ? null : resolveAgent(client, next);
		// A provisional look until the first pose payload seeds the exact one.
		PovLook.reset(agent == null ? 0.0F : agent.getYHeadRot(), agent == null ? 0.0F : agent.getXRot());
	}

	private static void tick(Minecraft client) {
		boolean exit = false;
		while (EXIT_VIEW.consumeClick()) exit = true;
		if (exit) requestExit();
		PovClientSession current = TRACKER.session().orElse(null);
		if (current == null) return;
		if (client.level == null || client.player == null) return;
		if (sessionLevel == null) sessionLevel = client.level;
		else if (client.level != sessionLevel) {
			endLocal(client);
			return;
		}
		silentTicks++;
		PovView.tick();
		Player agent = bindCamera(client, current);
		PovHudProxy.tick(client, agent);
		// The view rotation is what the camera shows, so the hands chase exactly that; a lost agent forgets
		// the bob so the hands snap to its look when it reappears instead of swinging in from a stale one.
		if (agent != null) HANDS.tick(agent.getViewXRot(1.0F), agent.getViewYRot(1.0F));
		else HANDS.reset();
		// The body stays in the world in both modes; only a takeover can end on its damage, so only then is it announced.
		if (BODY.observe(client.player.getHealth() + client.player.getAbsorptionAmount()) && current.takeover())
			overlay(client, "Your body took damage");
	}

	private static void overlay(Minecraft client, String message) {
		if (client.gui != null) client.gui.setOverlayMessage(Component.literal(message), false);
	}

	private static Player bindCamera(Minecraft client, PovClientSession current) {
		Player agent = resolveAgent(client, current);
		Entity target;
		if (agent != null) {
			TRACKER.entityFound();
			discardAnchor();
			hasLastPosition = true;
			lastX = agent.getX();
			lastEyeY = agent.getEyeY();
			lastZ = agent.getZ();
			target = agent;
		} else {
			// A far-away agent is paired only after its chunk arrives, so the first bind of a view is a wait, not a loss.
			if (TRACKER.entityMissing()) overlay(client, (hasLastPosition ? "Signal lost - waiting for " : "Waiting for ")
					+ (current.agentName().isBlank() ? "the agent" : current.agentName()));
			target = anchor(client);
		}
		PovView.bind(target, current.takeover());
		if (target != null && client.getCameraEntity() != target) setCamera(client, target);
		if (target == null) {
			// Nothing known to look at yet: never leave the camera on a removed entity.
			Entity camera = client.getCameraEntity();
			if (camera == null || camera.isRemoved() || camera.level() != client.level) setCamera(client, client.player);
		}
		if (!client.options.getCameraType().isFirstPerson()) client.options.setCameraType(CameraType.FIRST_PERSON);
		return agent;
	}

	// Re-resolved every tick by UUID so respawns and re-tracking rebind automatically.
	private static Player resolveAgent(Minecraft client, PovClientSession current) {
		if (client.level == null) return null;
		Player candidate = client.level.getPlayerByUUID(current.agentUuid());
		if (candidate == null || candidate == client.player || candidate.isRemoved()) return null;
		String name = candidate.getGameProfile().name();
		boolean agent = AgentIdentity.offlinePlayerUuid(name).equals(candidate.getUUID())
				|| AgentControlClient.isAgentPlayer(name)
				|| name.equalsIgnoreCase(current.agentName());
		return agent ? candidate : null;
	}

	// Signal lost: hold the view at the agent's last eye position, same marker technique as camera paths.
	private static Entity anchor(Minecraft client) {
		if (!hasLastPosition || client.level == null) return null;
		if (signalAnchor != null && !signalAnchor.isRemoved() && signalAnchor.level() == client.level) return signalAnchor;
		discardAnchor();
		Marker created = EntityType.MARKER.create(client.level, EntitySpawnReason.COMMAND);
		if (created == null) return null;
		created.snapTo(lastX, lastEyeY, lastZ, PovView.currentYaw(), PovView.currentPitch());
		client.level.addFreshEntity(created);
		signalAnchor = created;
		return created;
	}

	private static void discardAnchor() {
		if (signalAnchor == null) return;
		signalAnchor.remove(Entity.RemovalReason.DISCARDED);
		signalAnchor = null;
	}

	private static void setCamera(Minecraft client, Entity entity) {
		client.setCameraEntity(entity);
		CameraEyeHeightAccessor camera = (CameraEyeHeightAccessor) client.gameRenderer.getMainCamera();
		float height = entity == null ? 0.0F : entity.getEyeHeight();
		camera.arenaagents$setEyeHeight(height);
		camera.arenaagents$setEyeHeightOld(height);
	}

	// Server stop, disconnect, level change or a silent server: restore everything the view changed.
	private static void endLocal(Minecraft client) {
		boolean active = TRACKER.clear();
		latestState = null;
		latestMenu = null;
		attackStrength = 1.0F;
		poseFlags = 0;
		silentTicks = 0;
		hasLastPosition = false;
		BODY.reset();
		HANDS.reset();
		PovView.reset();
		PovLook.reset(0.0F, 0.0F);
		PovHudProxy.clear();
		discardAnchor();
		if (active) PovScreens.closeAll();
		restoreCamera(client);
		// LocalPlayer stops chasing its own bob while it is not the camera, so without this the operator's
		// hands would swing in from where they looked before the view started.
		LocalPlayer operator = client.player;
		if (operator != null) {
			operator.xBob = operator.xBobO = operator.getXRot();
			operator.yBob = operator.yBobO = operator.getYRot();
		}
		sessionLevel = null;
	}

	private static void restoreCamera(Minecraft client) {
		if (!cameraCaptured) return;
		Entity restore = previousCamera;
		previousCamera = null;
		cameraCaptured = false;
		if (restore == null || restore.isRemoved() || restore.level() != client.level
				|| (restore instanceof LocalPlayer && restore != client.player)) restore = client.player;
		if (client.level == null || (restore != null && (restore.isRemoved() || restore.level() != client.level))) restore = null;
		setCamera(client, restore);
		if (previousCameraType != null) {
			client.options.setCameraType(previousCameraType);
			previousCameraType = null;
		}
	}
}
