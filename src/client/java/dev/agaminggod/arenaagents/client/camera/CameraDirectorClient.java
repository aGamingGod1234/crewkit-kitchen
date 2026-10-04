package dev.agaminggod.arenaagents.client.camera;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import com.google.gson.JsonIOException;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.agaminggod.arenaagents.client.mixin.CameraEyeHeightAccessor;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Local cinematic camera director. It never changes server-side agent state. */
public final class CameraDirectorClient {
	private static final Logger LOGGER = LoggerFactory.getLogger(CameraDirectorClient.class);
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int MAX_PATHS = 64;
	private static final int MIN_PLAYBACK_TICKS = 1;
	private static final Map<String, CameraPath> PATHS = new LinkedHashMap<>();
	private static Recording recording;
	private static Playback playback;
	private static Marker cameraAnchor;
	private static Entity previousCamera;
	private static CameraType previousCameraType;
	private static boolean registered;
    private static Entity dolly;
    private static String armedDollyName;
    private static boolean armedDollyReplace;
    private static boolean dollyRecording;
    private static final net.minecraft.client.KeyMapping CAMERA_CONTROLS = new net.minecraft.client.KeyMapping("key.arenaagents.camera_controls", com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, org.lwjgl.glfw.GLFW.GLFW_KEY_K, net.minecraft.client.KeyMapping.Category.MISC);

    /** Arms physical capture. The next camera dolly clicked becomes the recording camera. */
    public static void startDollyRecordingFromGui(String name, boolean replace) {
        try {
            name = validateName(name);
            var client = Minecraft.getInstance();
            if (client.level == null || client.player == null) throw new IllegalArgumentException("Join a world before recording a camera dolly.");
            if (recording != null) throw new IllegalArgumentException("Save the current camera recording first.");
            if (PATHS.containsKey(name) && !replace) throw new IllegalArgumentException("A camera path named " + name + " already exists.");
            if (!PATHS.containsKey(name) && PATHS.size() >= MAX_PATHS) throw new IllegalArgumentException("Camera path library is full.");
            armedDollyName = name; armedDollyReplace = replace;
            if (dolly != null) beginDollyCapture(client);
            if (client.screen != null) client.setScreen(null);
            guiFeedback(dollyRecording ? "Recording camera. Use its Position tab to move; Stop and save finishes the shot." : "Right-click your camera to record. Its own controls can position and save the shot.", false);
        } catch (IllegalArgumentException error) { guiFeedback(error.getMessage(), true); }
    }

    private static void beginDollyCapture(Minecraft client) {
        if (dolly == null || armedDollyName == null || client.level == null || client.player == null) return;
        if (recording != null) {
            armedDollyName = null; guiFeedback("Save the current camera recording first.", true); return;
        }
        // Validation happens again because a path may have been saved while capture was armed.
        if (PATHS.containsKey(armedDollyName) && !armedDollyReplace) {
            armedDollyName = null; guiFeedback("That camera path already exists. Choose another name.", true); return;
        }
        recording = new Recording(armedDollyName, client.level, client.level.getGameTime(), new ArrayList<>(), true);
        recording.frames().add(dollyFrame(client, 0));
        armedDollyName = null; dollyRecording = true;
    }

    private static CameraKeyframe dollyFrame(Minecraft client, int tick) {
        if (dolly instanceof dev.agaminggod.arenaagents.camera.CameraRig rig) {
            var look = net.minecraft.world.phys.Vec3.directionFromRotation(rig.getXRot(), rig.getYRot());
            return new CameraKeyframe(tick, rig.getX() + look.x * 0.7, rig.getY() + rig.lensHeight() + look.y * 0.7, rig.getZ() + look.z * 0.7, rig.getYRot(), rig.getXRot());
        }
        var look = client.player.getLookAngle();
        return new CameraKeyframe(tick, dolly.getX() + look.x * 0.7, dolly.getY() + 1.35, dolly.getZ() + look.z * 0.7, client.player.getYRot(), client.player.getXRot());
    }

    public static boolean isDollyViewfinderActive() { return dolly != null; }

    public static String dollyCommand(boolean stop) {
        String command = "codex skit camera " + (stop ? "stop" : "roll");
        var client = Minecraft.getInstance();
        if (dolly == null || client.player == null) return command;
        return command + " " + dolly.getUUID() + " " + net.minecraft.util.Mth.wrapDegrees(client.player.getYRot());
    }

    private static void enterDolly(Minecraft client, Entity cart) {
        if (client.player == null || client.level == null) return;
        if (dollyRecording && recording != null) {
            guiFeedback("Camera recording is running. Save it before switching cameras.", false);
            return;
        }
        stopPlayback(client);
        previousCamera = client.getCameraEntity(); previousCameraType = client.options.getCameraType();
        dolly = cart;
        beginDollyCapture(client);
        guiFeedback(dollyRecording ? "Recording camera dolly. Mouse pans; sneak saves and exits." : "Camera viewfinder. Mouse pans; sneak exits. Right-click the camera to record a shot.", false);
    }

    public static boolean isRecording() { return recording != null; }
    public static String recordingName() { return recording == null ? "" : recording.name(); }
    public static void viewCamera(dev.agaminggod.arenaagents.camera.CameraRig rig) {
        var client = Minecraft.getInstance();
        if (recording != null && dolly != rig) { guiFeedback("Save the current recording before switching cameras.", true); return; }
        if (dolly != rig) enterDolly(client, rig);
        if (client.player != null) { client.player.setYRot(rig.getYRot()); client.player.setXRot(rig.getXRot()); }
        client.setScreen(null);
    }
    public static void recordCamera(dev.agaminggod.arenaagents.camera.CameraRig rig, String name) {
        if (dolly != rig) enterDolly(Minecraft.getInstance(), rig);
        startDollyRecordingFromGui(name, false);
    }
    public static void rigCommand(dev.agaminggod.arenaagents.camera.CameraRig rig, String operation, float value) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null) connection.sendCommand("codex skit camera rig " + rig.getUUID() + " " + operation + " " + value);
    }
    private static void tickDolly(Minecraft client) {
        if (dolly == null) return;
        if (client.player == null || client.level != dolly.level() || dolly.isRemoved() || client.options.keyShift.isDown()) {
            if (dollyRecording && recording != null) stopRecordingFromGui();
            stopPlayback(client); return;
        }
        if (dolly instanceof dev.agaminggod.arenaagents.camera.CameraRig rig && client.screen == null && client.level.getGameTime() % 4 == 0) {
            rigCommand(rig, "yaw", net.minecraft.util.Mth.wrapDegrees(client.player.getYRot()));
            rigCommand(rig, "pitch", client.player.getXRot());
        }
        var frame = dollyFrame(client, 0);
        apply(client, new CameraPose(frame.x(), frame.y(), frame.z(), frame.yaw(), frame.pitch()));
        if (dollyRecording && recording != null) {
            long elapsed = client.level.getGameTime() - recording.startedAt();
            if (elapsed > CameraPath.MAX_DURATION_TICKS) {
                dollyRecording = false;
                stopRecordingFromGui();
                return;
            }
            if (elapsed > recording.frames().getLast().tick() && elapsed % 4 == 0) {
                recording.frames().add(dollyFrame(client, (int) elapsed));
                if (recording.frames().size() >= CameraPath.MAX_KEYFRAMES || elapsed >= CameraPath.MAX_DURATION_TICKS) {
                    dollyRecording = false;
                    stopRecordingFromGui();
                    if (recording == null) guiFeedback("Camera path saved at the recording limit. Viewfinder is still open.", false);
                }
            }
        }
    }

	private static dev.agaminggod.arenaagents.control.DirectorTakePlaybackPayload scheduledTake;
	private static net.minecraft.client.multiplayer.ClientLevel takeLevel;
	private static boolean takeCamera;
	public static List<String> pathNames() { return List.copyOf(PATHS.keySet()); }
	public static int pathDuration(String name) { var path = PATHS.get(name); if (path == null || path.durationTicks() < MIN_PLAYBACK_TICKS) throw new IllegalArgumentException("Choose a saved camera path with at least two keyframes"); return Math.toIntExact(path.durationTicks()); }
	public static void acceptTake(dev.agaminggod.arenaagents.control.DirectorTakePlaybackPayload value) {
		Minecraft client = Minecraft.getInstance();
		if (value.startGameTime() < 0) {
			scheduledTake = null; takeLevel = null;
			if (takeCamera) stopPlayback(client);
			takeCamera = false;
			guiFeedback(value.message(), false);
			return;
		}
		if (client.level == null || client.player == null) return;
		if (!value.camera().isEmpty()) {
			try { pathDuration(value.camera()); }
			catch (IllegalArgumentException error) {
				if (client.getConnection() != null) client.getConnection().sendCommand("codex skit take stop");
				guiFeedback("Take cancelled: this client does not have the saved camera path", true);
				return;
			}
		}
		scheduledTake = value; takeLevel = client.level;
		if (client.screen != null) client.setScreen(null);
		guiFeedback(value.message(), false);
	}

	private CameraDirectorClient() {
	}

	public static synchronized void register() {
		if (registered) return;
		load(Minecraft.getInstance());
        net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(CAMERA_CONTROLS);
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.agaminggod.arenaagents.camera.CameraDolly.RIG, CameraRigRenderer::new);
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!level.isClientSide() || !dev.agaminggod.arenaagents.camera.CameraDolly.isCamera(entity)) return net.minecraft.world.InteractionResult.PASS;
            if (player.isShiftKeyDown()) return net.minecraft.world.InteractionResult.SUCCESS;
            if (entity instanceof dev.agaminggod.arenaagents.camera.CameraRig rig) {
                if (armedDollyName != null) enterDolly(Minecraft.getInstance(), rig);
                Minecraft.getInstance().setScreen(new CameraRigScreen(rig));
            }
            else enterDolly(Minecraft.getInstance(), entity);
            return net.minecraft.world.InteractionResult.SUCCESS;
        });
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ignored) -> dispatcher.register(commands()));
		ClientTickEvents.END_CLIENT_TICK.register(CameraDirectorClient::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			scheduledTake = null; takeLevel = null; takeCamera = false; armedDollyName = null;
            if (recording != null && recording.physical()) stopRecordingFromGui();
            else recording = null;
			stopPlayback(client);
		});
		registered = true;
	}

	/** GUI-safe camera controls used by the in-game Skit Director screen. */
	public static void startRecordingFromGui(String name, boolean replace) {
		Minecraft client = Minecraft.getInstance();
		try {
			name = beginRecording(client, name, replace);
			guiFeedback("Recording camera path '" + name + "'. Capture keyframes as you move, then save recording.", false);
		} catch (IllegalArgumentException exception) {
			guiFeedback(exception.getMessage(), true);
		}
	}

	public static void recordKeyframeFromGui() {
		Minecraft client = Minecraft.getInstance();
		if (recording == null) { guiFeedback("No camera path is recording.", true); return; }
		if (!recordingInCurrentLevel(client)) {
			recording = null;
			guiFeedback("Recording cancelled because you left its world.", true);
			return;
		}
		long elapsed = Math.max(0L, client.level.getGameTime() - recording.startedAt());
		if (elapsed > CameraPath.MAX_DURATION_TICKS) { guiFeedback("This camera path has reached its one-hour limit.", true); return; }
		if (elapsed < recording.frames().getLast().tick()) { guiFeedback("The world clock moved backwards. Wait before capturing another keyframe.", true); return; }
		CameraKeyframe frame = new CameraKeyframe((int) elapsed, client.player.getX(), client.player.getEyeY(), client.player.getZ(), client.player.getYRot(), client.player.getXRot());
		if (recording.frames().size() >= CameraPath.MAX_KEYFRAMES && recording.frames().stream().noneMatch(existing -> existing.tick() == frame.tick())) {
			guiFeedback("This camera path has reached its keyframe limit.", true);
			return;
		}
		if (!recording.frames().isEmpty() && recording.frames().getLast().tick() == frame.tick()) recording.frames().set(recording.frames().size() - 1, frame);
		else recording.frames().add(frame);
		guiFeedback("Captured keyframe at " + frame.tick() + " ticks.", false);
	}

	public static void stopRecordingFromGui() {
		Minecraft client = Minecraft.getInstance();
		if (recording == null) { guiFeedback("No camera path is recording.", true); return; }
		try {
			CameraPath saved = finishRecording(client);
            dollyRecording = false;
			guiFeedback("Saved camera path '" + saved.name() + "' (" + saved.keyframes().size() + " keyframes).", false);
		} catch (IllegalArgumentException exception) {
			guiFeedback(exception.getMessage(), true);
		} catch (IOException exception) {
			guiFeedback("Could not save camera path. Recording kept; try saving again.", true);
		}
	}

	public static void playFromGui(String name, boolean loop) {
		CameraPath path = PATHS.get(name == null ? "" : name.strip());
		Minecraft client = Minecraft.getInstance();
		if (path == null) { guiFeedback("No camera path named '" + name + "'.", true); return; }
		if (client.level == null || client.player == null) { guiFeedback("You must be in a world to play a camera path.", true); return; }
		if (path.durationTicks() < MIN_PLAYBACK_TICKS) { guiFeedback("Add a second keyframe so the camera has a duration to play.", true); return; }
		stopPlayback(client);
		previousCamera = client.getCameraEntity();
		previousCameraType = client.options.getCameraType();
		playback = new Playback(path, client.level, client.player, client.level.getGameTime(), loop);
		apply(client, path.sample(0.0D));
		if (client.screen != null) client.setScreen(null);
		guiFeedback("Playing camera path '" + path.name() + "'" + (loop ? " on loop." : "."), false);
	}

	public static void stopPlaybackFromGui() {
		if (playback == null && dolly == null) { guiFeedback("No camera path is playing.", true); return; }
		stopPlayback(Minecraft.getInstance());
		guiFeedback("Camera path stopped; camera returned to the player.", false);
	}

	private static void guiFeedback(String message, boolean error) {
		Minecraft client = Minecraft.getInstance();
		if (client.screen instanceof dev.agaminggod.arenaagents.client.gui.SkitDirectorScreen screen) screen.acceptLocalFeedback(message, error);
        if (client.screen instanceof CameraRigScreen screen) screen.feedback(message);
		if (client.gui != null) client.gui.setOverlayMessage(Component.literal(message), true);
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> commands() {
		var path = ClientCommands.literal("path");
		path.then(ClientCommands.literal("start")
				.then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.word())
						.executes(context -> startRecording(context.getSource(), StringArgumentType.getString(context, "name")))));
		var keyframe = ClientCommands.literal("keyframe").executes(context -> recordKeyframe(context.getSource()));
		path.then(keyframe);
		path.then(ClientCommands.literal("frame").executes(context -> recordKeyframe(context.getSource())));
		path.then(ClientCommands.literal("stop").executes(context -> stopRecording(context.getSource())));
		var play = ClientCommands.literal("play").then(
				com.mojang.brigadier.builder.RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(PATHS.keySet(), builder))
						.executes(context -> play(context.getSource(), StringArgumentType.getString(context, "name"), false))
						.then(ClientCommands.argument("loop", BoolArgumentType.bool())
								.executes(context -> play(context.getSource(), StringArgumentType.getString(context, "name"), BoolArgumentType.getBool(context, "loop")))));
		path.then(play);
		path.then(ClientCommands.literal("stop-playback").executes(context -> stopPlaybackCommand(context.getSource())));
		path.then(ClientCommands.literal("list").executes(context -> list(context.getSource())));
		path.then(ClientCommands.literal("info")
				.then(ClientCommands.argument("name", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(PATHS.keySet(), builder))
						.executes(context -> info(context.getSource(), StringArgumentType.getString(context, "name")))));
		path.then(ClientCommands.literal("delete")
				.then(ClientCommands.argument("name", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(PATHS.keySet(), builder))
						.executes(context -> delete(context.getSource(), StringArgumentType.getString(context, "name")))));
		path.then(ClientCommands.literal("clear").executes(context -> clear(context.getSource())));
		return ClientCommands.literal("camera").then(path);
	}

	private static int startRecording(FabricClientCommandSource source, String name) {
		try {
			name = beginRecording(source.getClient(), name, false);
			source.sendFeedback(Component.literal("Recording camera path '" + name + "'. Move the player/camera, then use /camera path keyframe. Stop saves it."));
			return 1;
		} catch (IllegalArgumentException exception) {
			return error(source, exception.getMessage());
		}
	}

	private static int recordKeyframe(FabricClientCommandSource source) {
		if (recording == null) return error(source, "No camera path is recording. Start one with /camera path start <name>.");
		Minecraft client = source.getClient();
		if (!recordingInCurrentLevel(client)) {
			recording = null;
			return error(source, "Recording cancelled because you left its world.");
		}
		long elapsed = Math.max(0L, client.level.getGameTime() - recording.startedAt());
		if (elapsed > CameraPath.MAX_DURATION_TICKS) return error(source, "This camera path has reached its one-hour limit.");
		if (elapsed < recording.frames().getLast().tick()) return error(source, "The world clock moved backwards. Wait before capturing another keyframe.");
		CameraKeyframe frame = new CameraKeyframe((int) elapsed, client.player.getX(), client.player.getEyeY(), client.player.getZ(), client.player.getYRot(), client.player.getXRot());
		if (recording.frames().size() >= CameraPath.MAX_KEYFRAMES && recording.frames().stream().noneMatch(existing -> existing.tick() == frame.tick())) {
			return error(source, "This camera path has reached its keyframe limit (" + CameraPath.MAX_KEYFRAMES + ").");
		}
		if (!recording.frames().isEmpty() && recording.frames().getLast().tick() == frame.tick()) recording.frames().set(recording.frames().size() - 1, frame);
		else recording.frames().add(frame);
		source.sendFeedback(Component.literal("Captured camera keyframe at " + frame.tick() + " ticks."));
		return 1;
	}

	private static int stopRecording(FabricClientCommandSource source) {
		if (recording == null) return error(source, "No camera path is recording.");
		try {
			CameraPath saved = finishRecording(source.getClient());
			source.sendFeedback(Component.literal("Saved camera path '" + saved.name() + "' (" + saved.keyframes().size() + " keyframes, " + saved.durationTicks() + " ticks)."));
			return 1;
		} catch (IllegalArgumentException exception) {
			return error(source, exception.getMessage());
		} catch (IOException exception) {
			return error(source, "Could not save camera path. Recording kept; try saving again.");
		}
	}

	private static String beginRecording(Minecraft client, String name, boolean replace) {
		name = validateName(name);
		if (recording != null) throw new IllegalArgumentException("A camera path is already recording. Save it before starting another.");
		boolean exists = PATHS.containsKey(name);
		if (exists && !replace) throw new IllegalArgumentException("A camera path named " + name + " already exists.");
		if (!exists && PATHS.size() >= MAX_PATHS) throw new IllegalArgumentException("Camera path limit reached (" + MAX_PATHS + ").");
		if (client.level == null || client.player == null) throw new IllegalArgumentException("You must be in a world to record a camera path.");
		CameraKeyframe first = new CameraKeyframe(0, client.player.getX(), client.player.getEyeY(), client.player.getZ(), client.player.getYRot(), client.player.getXRot());
		stopPlayback(client);
		recording = new Recording(name, client.level, client.level.getGameTime(), new ArrayList<>(List.of(first)), false);
		return name;
	}

	private static boolean recordingInCurrentLevel(Minecraft client) {
		return recording != null && client.level != null && client.player != null && recording.level() == client.level;
	}

	private static CameraPath finishRecording(Minecraft client) throws IOException {
        if (recording == null) throw new IllegalArgumentException("No camera path is recording.");
		if (!recording.physical() && !recordingInCurrentLevel(client)) {
			recording = null;
			throw new IllegalArgumentException("Recording cancelled because you left its world.");
		}
		if (dollyRecording && dolly != null && recordingInCurrentLevel(client) && recording.frames().size() < CameraPath.MAX_KEYFRAMES) {
            long elapsed = client.level.getGameTime() - recording.startedAt();
            if (elapsed > recording.frames().getLast().tick() && elapsed <= CameraPath.MAX_DURATION_TICKS)
                recording.frames().add(dollyFrame(client, (int) elapsed));
        }
        // Finalization owns the last sample. Freeze it even if persistence fails, so retry
        // retains the same valid take rather than sampling beyond the keyframe limit.
        dollyRecording = false;
        CameraPath saved = new CameraPath(recording.name(), recording.frames());
		Map<String, CameraPath> next = new LinkedHashMap<>(PATHS);
		next.put(saved.name(), saved);
		save(client, next);
		recording = null;
		return saved;
	}

	private static int play(FabricClientCommandSource source, String name, boolean loop) {
		CameraPath path = PATHS.get(name);
		if (path == null) return error(source, "No camera path named '" + name + "'. Use /camera path list.");
		Minecraft client = source.getClient();
		if (client.level == null || client.player == null) return error(source, "You must be in a world to play a camera path.");
		if (path.durationTicks() < MIN_PLAYBACK_TICKS) return error(source, "Add a second keyframe so the camera has a duration to play.");
		stopPlayback(client);
		previousCamera = client.getCameraEntity();
		previousCameraType = client.options.getCameraType();
		playback = new Playback(path, client.level, client.player, client.level.getGameTime(), loop);
		apply(client, path.sample(0.0D));
		source.sendFeedback(Component.literal("Playing camera path '" + name + "'" + (loop ? " on loop" : "") + ". Use /camera path stop-playback to return."));
		return 1;
	}

	private static int stopPlaybackCommand(FabricClientCommandSource source) {
		if (playback == null) return error(source, "No camera path is playing.");
		stopPlayback(source.getClient());
		source.sendFeedback(Component.literal("Camera path stopped; camera returned to the player."));
		return 1;
	}

	private static int list(FabricClientCommandSource source) {
		if (PATHS.isEmpty()) return error(source, "No saved camera paths. Start with /camera path start <name>.");
		PATHS.values().forEach(path -> source.sendFeedback(Component.literal(path.name() + " - " + path.keyframes().size() + " keyframes, " + path.durationTicks() + " ticks (" + (path.durationTicks() / 20.0D) + "s)")));
		return PATHS.size();
	}

	private static int info(FabricClientCommandSource source, String name) {
		CameraPath path = PATHS.get(name);
		if (path == null) return error(source, "No camera path named '" + name + "'.");
		source.sendFeedback(Component.literal("Camera path '" + name + "': " + path.keyframes().size() + " keyframes, " + path.durationTicks() + " ticks. Playback uses smooth position interpolation and shortest-turn rotation."));
		return 1;
	}

	private static int delete(FabricClientCommandSource source, String name) {
		Map<String, CameraPath> next = new LinkedHashMap<>(PATHS);
		if (next.remove(name) == null) return error(source, "No camera path named '" + name + "'.");
		try {
			save(source.getClient(), next);
			source.sendFeedback(Component.literal("Deleted camera path '" + name + "'."));
			return 1;
		} catch (IOException exception) {
			return error(source, "Could not delete camera path. The saved library was kept.");
		}
	}

	private static int clear(FabricClientCommandSource source) {
		if (PATHS.isEmpty()) return error(source, "There are no saved camera paths.");
		int count = PATHS.size();
		try {
			save(source.getClient(), Map.of());
			source.sendFeedback(Component.literal("Deleted " + count + " camera paths."));
			return count;
		} catch (IOException exception) {
			return error(source, "Could not clear camera paths. The saved library was kept.");
		}
	}

	private static void tick(Minecraft client) {
        while (CAMERA_CONTROLS.consumeClick()) if (dolly instanceof dev.agaminggod.arenaagents.camera.CameraRig rig && client.screen == null) client.setScreen(new CameraRigScreen(rig));
        tickDolly(client);
		if (scheduledTake != null) {
			if (client.level != takeLevel || client.player == null) { scheduledTake = null; takeLevel = null; }
			else if (client.level.getGameTime() >= scheduledTake.startGameTime()) {
				var take = scheduledTake; scheduledTake = null; takeLevel = null;
				if (!take.camera().isEmpty()) {
					playFromGui(take.camera(), false);
					if (playback != null) { playback = new Playback(playback.path(), playback.level(), playback.player(), take.startGameTime(), false); takeCamera = true; }
				}
			}
		}
		if (recording != null && !recording.physical() && !recordingInCurrentLevel(client)) recording = null;
		if (playback == null) return;
		if (client.level == null || client.player == null || playback.level() != client.level || playback.player() != client.player) {
			stopPlayback(client);
			return;
		}
		long elapsed = client.level.getGameTime() - playback.startedAt();
		if (elapsed >= playback.path().durationTicks()) {
			if (!playback.loop()) {
				apply(client, playback.path().sample(playback.path().durationTicks()));
				stopPlayback(client);
				return;
			}
			long duration = Math.max(1L, playback.path().durationTicks());
			elapsed %= duration;
			playback = new Playback(playback.path(), playback.level(), playback.player(), client.level.getGameTime() - elapsed, true);
		}
		apply(client, playback.path().sample(elapsed));
	}

	private static void apply(Minecraft client, CameraPose pose) {
		if (client.level == null) return;
		boolean created = cameraAnchor == null || cameraAnchor.level() != client.level || cameraAnchor.isRemoved();
		if (created) {
			if (cameraAnchor != null) cameraAnchor.remove(Entity.RemovalReason.DISCARDED);
			cameraAnchor = EntityType.MARKER.create(client.level, EntitySpawnReason.COMMAND);
			if (cameraAnchor == null) return;
		}
		if (!created) cameraAnchor.setOldPosAndRot();
		cameraAnchor.setPos(pose.x(), pose.y(), pose.z());
		cameraAnchor.setYRot(pose.yaw());
		cameraAnchor.setXRot(pose.pitch());
		client.options.setCameraType(CameraType.FIRST_PERSON);
		if (created) {
			cameraAnchor.setOldPosAndRot();
			client.level.addFreshEntity(cameraAnchor);
			setCamera(client, cameraAnchor);
		}
	}

	private static void setCamera(Minecraft client, Entity entity) {
		client.setCameraEntity(entity);
		CameraEyeHeightAccessor camera = (CameraEyeHeightAccessor) client.gameRenderer.getMainCamera();
		float height = entity == null ? 0.0F : entity.getEyeHeight();
		camera.arenaagents$setEyeHeight(height);
		camera.arenaagents$setEyeHeightOld(height);
	}

	private static void stopPlayback(Minecraft client) {
		if (playback == null && dolly == null && cameraAnchor == null && previousCamera == null && previousCameraType == null) return;
        dolly = null; dollyRecording = false;
		playback = null;
		if (cameraAnchor != null) {
			cameraAnchor.remove(Entity.RemovalReason.DISCARDED);
			cameraAnchor = null;
		}
		Entity restore = previousCamera;
		previousCamera = null;
		if (restore == null || restore.isRemoved() || restore.level() != client.level
				|| (restore instanceof LocalPlayer && restore != client.player)) restore = client.player;
		if (client.level == null || (restore != null && (restore.isRemoved() || restore.level() != client.level))) restore = null;
		setCamera(client, restore);
		if (previousCameraType != null) {
			client.options.setCameraType(previousCameraType);
			previousCameraType = null;
		}
	}

	private static void load(Minecraft client) {
		PATHS.clear();
		Path file = storageFile(client);
		if (!Files.isRegularFile(file)) return;
		try (Reader reader = Files.newBufferedReader(file)) {
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
			JsonArray paths = root.getAsJsonArray("paths");
			if (paths == null) return;
			for (JsonElement pathElement : paths) {
				if (PATHS.size() >= MAX_PATHS) break;
				JsonObject pathObject = pathElement.getAsJsonObject();
				ArrayList<CameraKeyframe> frames = new ArrayList<>();
				for (JsonElement frameElement : pathObject.getAsJsonArray("keyframes")) {
					JsonObject frame = frameElement.getAsJsonObject();
					frames.add(new CameraKeyframe(frame.get("tick").getAsInt(), frame.get("x").getAsDouble(), frame.get("y").getAsDouble(), frame.get("z").getAsDouble(), frame.get("yaw").getAsFloat(), frame.get("pitch").getAsFloat()));
				}
				CameraPath path = new CameraPath(pathObject.get("name").getAsString(), frames);
				PATHS.put(path.name(), path);
			}
		} catch (IOException | JsonParseException | IllegalArgumentException | NullPointerException
				| IllegalStateException | ClassCastException | UnsupportedOperationException exception) {
			LOGGER.warn("Could not load Arena Agents camera paths; starting with an empty library", exception);
			PATHS.clear();
		}
	}

	private static void save(Minecraft client, Map<String, CameraPath> library) throws IOException {
		Path file = storageFile(client);
		Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			Files.createDirectories(file.getParent());
			JsonObject root = new JsonObject();
			JsonArray paths = new JsonArray();
			for (CameraPath path : library.values()) {
				JsonObject pathObject = new JsonObject();
				pathObject.addProperty("name", path.name());
				JsonArray frames = new JsonArray();
				for (CameraKeyframe frame : path.keyframes()) {
					JsonObject frameObject = new JsonObject();
					frameObject.addProperty("tick", frame.tick());
					frameObject.addProperty("x", frame.x());
					frameObject.addProperty("y", frame.y());
					frameObject.addProperty("z", frame.z());
					frameObject.addProperty("yaw", frame.yaw());
					frameObject.addProperty("pitch", frame.pitch());
					frames.add(frameObject);
				}
				pathObject.add("keyframes", frames);
				paths.add(pathObject);
			}
			root.add("paths", paths);
			try (Writer writer = Files.newBufferedWriter(temporary)) {
				writeJson(root, writer);
			}
			try {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException exception) {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
			}
			PATHS.clear();
			PATHS.putAll(library);
		} catch (IOException exception) {
			LOGGER.warn("Could not save Arena Agents camera paths", exception);
			throw exception;
		}
	}

	private static void writeJson(JsonObject root, Writer writer) throws IOException {
		try {
			GSON.toJson(root, writer);
		} catch (JsonIOException exception) {
			if (exception.getCause() instanceof IOException cause) throw cause;
			throw exception;
		}
	}

	private static Path storageFile(Minecraft client) {
		return client.gameDirectory.toPath().resolve("config").resolve("arenaagents").resolve("camera-paths.json");
	}

	private static String validateName(String name) {
		if (name == null) throw new IllegalArgumentException("A camera path needs a name.");
		return new CameraPath(name, List.of(new CameraKeyframe(0, 0.0D, 0.0D, 0.0D, 0.0F, 0.0F))).name();
	}

	private static int error(FabricClientCommandSource source, String message) {
		source.sendError(Component.literal(message == null || message.isBlank() ? "Camera command failed." : message));
		return 0;
	}

	private record Recording(String name, ClientLevel level, long startedAt, ArrayList<CameraKeyframe> frames, boolean physical) {
	}

	private record Playback(CameraPath path, ClientLevel level, LocalPlayer player, long startedAt, boolean loop) {
	}
}
