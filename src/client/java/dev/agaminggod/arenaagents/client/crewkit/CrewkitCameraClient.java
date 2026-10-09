package dev.agaminggod.arenaagents.client.crewkit;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.agaminggod.arenaagents.client.mixin.CameraEyeHeightAccessor;
import dev.agaminggod.arenaagents.client.pov.PovClient;
import dev.agaminggod.arenaagents.crewkit.CrewkitAnchors;
import dev.agaminggod.arenaagents.crewkit.director.CrewkitCameraPayload;
import dev.agaminggod.arenaagents.crewkit.director.CrewkitMarks;
import dev.agaminggod.arenaagents.crewkit.director.DirectorFeature;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import org.lwjgl.glfw.GLFW;

/**
 * CrewKit film camera. While live (/ckcam on or the step keys), the view rides a client-side marker
 * that glides between CrewkitMarks with ease-in-out (routing around props via mid-room waypoints), then
 * drifts in slowly while the shot holds. The HUD is hidden for recording.
 * Server DirectorFeature sends a mark per contract event; ] and [ step marks by hand; \ releases.
 */
public final class CrewkitCameraClient {
	private CrewkitCameraClient() {}

	private static final KeyMapping NEXT = new KeyMapping("key.arenaagents.crewkit_next", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, KeyMapping.Category.MISC);
	private static final KeyMapping PREV = new KeyMapping("key.arenaagents.crewkit_prev", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET, KeyMapping.Category.MISC);
	private static final KeyMapping RELEASE = new KeyMapping("key.arenaagents.crewkit_release", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_BACKSLASH, KeyMapping.Category.MISC);

	private static boolean live;
	private static boolean hideHud = true;
	private static int manualMoveTicks = DirectorFeature.MOVE_TICKS;
	private static int originX = Integer.MIN_VALUE, originY, originZ;
	private static String markId;

	private static Marker anchor;
	private static Entity previousCamera;
	private static CameraType previousCameraType;
	private static Boolean previousHideGui;

	/** Current pose and glide state: from -> to over [start, start + duration] client ticks, then to -> rest over the hold. */
	private static double[] from, to, rest, pose;
	/** Glide positions: from, any waypoints, to. Cumulative lengths for constant-speed travel along them. */
	private static double[][] path;
	private static double[] pathLength;
	private static long ticks, moveStart;
	private static int moveDuration, holdDuration;

	public static void register() {
		CrewkitCameraPayload.register();
		KeyMappingHelper.registerKeyMapping(NEXT);
		KeyMappingHelper.registerKeyMapping(PREV);
		KeyMappingHelper.registerKeyMapping(RELEASE);
		ClientPlayNetworking.registerGlobalReceiver(CrewkitCameraPayload.TYPE, (payload, context) -> context.client().execute(() -> accept(payload)));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ignored) -> dispatcher.register(commands()));
		ClientTickEvents.END_CLIENT_TICK.register(CrewkitCameraClient::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> release(client));
	}

	private static void accept(CrewkitCameraPayload payload) {
		originX = payload.originX(); originY = payload.originY(); originZ = payload.originZ();
		if (live) go(Minecraft.getInstance(), payload.mark(), payload.moveTicks(), payload.holdTicks());
		else markId = payload.mark(); // stepping resumes from the director's latest beat
	}

	private static int[] origin() {
		if (originX == Integer.MIN_VALUE) {
			// Singleplayer shares CrewkitAnchors with the integrated server; good until the first payload arrives.
			var o = CrewkitAnchors.origin;
			return new int[] {o.getX(), o.getY(), o.getZ()};
		}
		return new int[] {originX, originY, originZ};
	}

	private static boolean go(Minecraft client, String id, int moveTicks) {
		return go(client, id, moveTicks, 100);
	}

	private static boolean go(Minecraft client, String id, int moveTicks, int holdTicks) {
		var mark = CrewkitMarks.byId(id);
		if (mark.isEmpty() || client.level == null || client.player == null) return false;
		if (PovClient.session().isPresent()) return false;
		int[] o = origin();
		double[] target = mark.get().startPose(o[0], o[1], o[2]);
		double[] end = mark.get().pose(o[0], o[1], o[2]);
		markId = mark.get().id();
		if (!live || pose == null) {
			live = true;
			pose = target.clone();
			from = target.clone();
		} else {
			from = pose.clone();
			// Shortest turn: keep yaw continuous so the marker's interpolation never spins the long way.
			target[3] = from[3] + wrap(target[3] - from[3]);
		}
		end[3] = target[3] + wrap(end[3] - target[3]);
		to = target;
		rest = end;
		// Route around the pass, chef stack and tables when the straight line would clip them.
		double[] a = {from[0] - o[0], from[1] - o[1], from[2] - o[2]}, b = {to[0] - o[0], to[1] - o[1], to[2] - o[2]};
		var via = CrewkitMarks.route(a, b);
		int n = via == null ? 0 : via.size();
		path = new double[n + 2][];
		path[0] = new double[] {from[0], from[1], from[2]};
		for (int i = 0; i < n; i++) path[i + 1] = new double[] {via.get(i)[0] + o[0], via.get(i)[1] + o[1], via.get(i)[2] + o[2]};
		path[n + 1] = new double[] {to[0], to[1], to[2]};
		pathLength = new double[path.length];
		for (int i = 1; i < path.length; i++) pathLength[i] = pathLength[i - 1] + dist(path[i - 1], path[i]);
		moveStart = ticks;
		// Detours are longer, so give them a little more time.
		moveDuration = Math.max(1, n == 0 ? moveTicks : Math.min(48, moveTicks + 8 * n));
		holdDuration = Math.max(1, holdTicks);
		apply(client);
		return true;
	}

	private static double dist(double[] p, double[] q) {
		double dx = p[0] - q[0], dy = p[1] - q[1], dz = p[2] - q[2];
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** Position at eased fraction e of the glide path. */
	private static void along(double e) {
		double total = pathLength[pathLength.length - 1];
		if (total < 1e-6) { System.arraycopy(path[path.length - 1], 0, pose, 0, 3); return; }
		double d = e * total;
		int i = 1;
		while (i < path.length - 1 && pathLength[i] < d) i++;
		double seg = pathLength[i] - pathLength[i - 1];
		double u = seg < 1e-6 ? 1 : (d - pathLength[i - 1]) / seg;
		for (int k = 0; k < 3; k++) pose[k] = path[i - 1][k] + (path[i][k] - path[i - 1][k]) * u;
	}

	private static void step(Minecraft client, int delta) {
		int index = markId == null ? (delta > 0 ? -1 : 0) : CrewkitMarks.indexOf(markId);
		int size = CrewkitMarks.ORDER.size();
		int next = Math.floorMod(index + delta, size);
		go(client, CrewkitMarks.ORDER.get(next).id(), manualMoveTicks);
	}

	private static void tick(Minecraft client) {
		ticks++;
		while (NEXT.consumeClick()) if (client.screen == null) step(client, 1);
		while (PREV.consumeClick()) if (client.screen == null) step(client, -1);
		while (RELEASE.consumeClick()) if (client.screen == null) release(client);
		if (!live) return;
		if (client.level == null || client.player == null || PovClient.session().isPresent()) { release(client); return; }
		long elapsed = ticks - moveStart;
		if (elapsed < moveDuration) {
			double t = elapsed / (double) moveDuration;
			double e = t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2; // ease-in-out cubic
			along(e);
			for (int i = 3; i < 5; i++) pose[i] = from[i] + (to[i] - from[i]) * e;
		} else {
			// Hold: a slow smoothstep dolly from the landing pose to the fitted pose (DRIFT blocks) keeps the shot alive.
			double t = Math.min(1.0, (elapsed - moveDuration) / (double) holdDuration);
			double e = t * t * (3 - 2 * t);
			for (int i = 0; i < 5; i++) pose[i] = to[i] + (rest[i] - to[i]) * e;
		}
		apply(client);
	}

	private static void apply(Minecraft client) {
		boolean created = anchor == null || anchor.level() != client.level || anchor.isRemoved();
		if (created) {
			if (anchor != null) anchor.remove(Entity.RemovalReason.DISCARDED);
			anchor = EntityType.MARKER.create(client.level, EntitySpawnReason.COMMAND);
			if (anchor == null) { live = false; return; }
			previousCamera = client.getCameraEntity();
			previousCameraType = client.options.getCameraType();
			if (previousHideGui == null) previousHideGui = client.options.hideGui;
		} else {
			anchor.setOldPosAndRot();
		}
		anchor.setPos(pose[0], pose[1], pose[2]);
		anchor.setYRot((float) pose[3]);
		anchor.setXRot((float) Math.max(-90, Math.min(90, pose[4])));
		client.options.setCameraType(CameraType.FIRST_PERSON);
		client.options.hideGui = hideHud;
		if (created) {
			anchor.setOldPosAndRot();
			client.level.addFreshEntity(anchor);
			setCamera(client, anchor);
		}
	}

	private static void setCamera(Minecraft client, Entity entity) {
		client.setCameraEntity(entity);
		CameraEyeHeightAccessor camera = (CameraEyeHeightAccessor) client.gameRenderer.getMainCamera();
		float height = entity == null ? 0.0F : entity.getEyeHeight();
		camera.arenaagents$setEyeHeight(height);
		camera.arenaagents$setEyeHeightOld(height);
	}

	public static void release(Minecraft client) {
		boolean wasLive = live;
		live = false; pose = null; from = null; to = null; rest = null; path = null;
		if (anchor != null) { anchor.remove(Entity.RemovalReason.DISCARDED); anchor = null; }
		if (!wasLive && previousCamera == null && previousHideGui == null) return;
		Entity restore = previousCamera;
		previousCamera = null;
		if (restore == null || restore.isRemoved() || restore.level() != client.level) restore = client.player;
		if (client.level != null) setCamera(client, restore);
		if (previousCameraType != null) { client.options.setCameraType(previousCameraType); previousCameraType = null; }
		if (previousHideGui != null) { client.options.hideGui = previousHideGui; previousHideGui = null; }
	}

	private static double wrap(double degrees) {
		double d = degrees % 360.0;
		if (d >= 180.0) d -= 360.0;
		if (d < -180.0) d += 360.0;
		return d;
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> commands() {
		var root = ClientCommands.literal("ckcam");
		root.then(ClientCommands.literal("on").executes(c -> {
			String start = markId == null ? CrewkitMarks.WIDE.id() : markId;
			if (!go(c.getSource().getClient(), start, 1)) return error(c.getSource(), "Join the world (and leave agent view) first.");
			c.getSource().sendFeedback(Component.literal("CrewKit camera live on '" + start + "'. It follows the run; ] next, [ previous, \\ release."));
			return 1;
		}));
		root.then(ClientCommands.literal("off").executes(c -> { release(c.getSource().getClient()); return 1; }));
		root.then(ClientCommands.literal("next").executes(c -> { step(c.getSource().getClient(), 1); return 1; }));
		root.then(ClientCommands.literal("prev").executes(c -> { step(c.getSource().getClient(), -1); return 1; }));
		root.then(ClientCommands.literal("go").then(ClientCommands.argument("mark", StringArgumentType.word())
				.suggests((c, b) -> SharedSuggestionProvider.suggest(CrewkitMarks.ORDER.stream().map(CrewkitMarks.Mark::id), b))
				.executes(c -> go(c.getSource().getClient(), StringArgumentType.getString(c, "mark"), manualMoveTicks) ? 1
						: error(c.getSource(), "Unknown mark. Try: " + String.join(", ", CrewkitMarks.ORDER.stream().map(CrewkitMarks.Mark::id).toList())))));
		root.then(ClientCommands.literal("hud").then(ClientCommands.argument("hidden", BoolArgumentType.bool()).executes(c -> {
			hideHud = BoolArgumentType.getBool(c, "hidden");
			if (live) c.getSource().getClient().options.hideGui = hideHud;
			return 1;
		})));
		root.then(ClientCommands.literal("speed").then(ClientCommands.argument("ticks", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 200)).executes(c -> {
			manualMoveTicks = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "ticks");
			return 1;
		})));
		root.then(ClientCommands.literal("list").executes(c -> {
			CrewkitMarks.ORDER.forEach(m -> c.getSource().sendFeedback(Component.literal(m.id() + " - " + m.label())));
			return CrewkitMarks.ORDER.size();
		}));
		return root;
	}

	private static int error(FabricClientCommandSource source, String message) {
		source.sendError(Component.literal(message));
		return 0;
	}
}
