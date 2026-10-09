package dev.agaminggod.arenaagents.crewkit.fun.music;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import dev.agaminggod.arenaagents.crewkit.CrewkitAnchors;
import dev.agaminggod.arenaagents.crewkit.CrewkitFeature;
import dev.agaminggod.arenaagents.server.GoalControl;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * Quiet bossa-nova lobby music made of vanilla note block sounds, sequenced on the server tick.
 * 16-bar loop at ~109 BPM (11-tick beats, swung eighths 7+4 ticks): Dm9 G13 Cmaj9 A7b9 changes,
 * walking bass, harp comping, flute melody, shaker. Starts on brief, plays a 4-bar outro on completed,
 * stops on failed/expired/reset. {@code /crewkit music on|off|vol <0-1>} toggles it for recording.
 *
 * State is static because CrewkitFeatures.all() may build fresh instances.
 */
public final class LobbyMusicFeature implements CrewkitFeature {
	private static final int BEAT = 11;
	private static final int SWING = 7;
	private static final int BAR = BEAT * 4;
	private static final int LOOP_BARS = 16;
	private static final int OUT_BEAT = 8;
	private static final int OUT_BAR = OUT_BEAT * 4;
	private static final double AUDIBLE_RADIUS = 40.0;
	/** Sounds are placed this close to each listener (see CrewkitSounds) so they stay audible at the camera. */
	private static final double PLACE_DISTANCE = 3.0;

	private enum Inst {
		HARP("harp", 54), BASS("bass", 30), FLUTE("flute", 66), CHIME("chime", 78), BELL("bell", 78),
		PLING("pling", 54), XYLO("iron_xylophone", 54), HAT("hat", 54), KICK("basedrum", 54);

		final int baseMidi;
		final Holder<SoundEvent> sound;

		Inst(String name, int baseMidi) {
			this.baseMidi = baseMidi;
			this.sound = Holder.direct(SoundEvent.createVariableRangeEvent(Identifier.parse("minecraft:block.note_block." + name)));
		}
	}

	private record Note(Inst inst, float pitch, float volume) {}

	private enum Mode { OFF, LOOP, OUTRO }

	private static final List<Note>[] LOOP = build(LOOP_BARS * BAR);
	private static final List<Note>[] OUTRO = build(OUT_BAR * 4 + 40);

	private static boolean enabled = true;
	private static float master = 0.35f;
	private static Mode mode = Mode.OFF;
	private static int step;
	private static float fade;
	private static float fadeDelta;
	private static boolean stopAtSilence;
	private static boolean commandsHooked;

	static {
		composeLoop();
		composeOutro();
	}

	@Override
	public void onEvent(MinecraftServer server, String event, JsonObject data, long seq) {
		switch (event) {
			case "brief" -> { if (enabled) startLoop(); }
			case "completed" -> { if (enabled) startOutro(); }
			case "failed", "expired" -> fadeOut(20);
			case "reset" -> stopNow();
			default -> {}
		}
	}

	@Override
	public void tick(MinecraftServer server) {
		try {
			hookCommands(server);
			if (mode == Mode.OFF) return;
			fade = Math.max(0f, Math.min(1f, fade + fadeDelta));
			if (stopAtSilence && fade <= 0f) {
				stopNow();
				return;
			}
			List<Note>[] track = mode == Mode.LOOP ? LOOP : OUTRO;
			if (step >= track.length) {
				if (mode == Mode.LOOP) {
					step = 0;
				} else {
					stopNow();
					return;
				}
			}
			List<Note> notes = track[step++];
			if (notes != null && fade > 0f) play(server, notes);
		} catch (RuntimeException e) {
			stopNow();
		}
	}

	@Override
	public void reset(MinecraftServer server) {
		stopNow();
	}

	// ---- transport ----

	private static void startLoop() {
		if (mode == Mode.LOOP && !stopAtSilence) return;
		mode = Mode.LOOP;
		step = 0;
		fade = 0f;
		fadeDelta = 1f / 80f;
		stopAtSilence = false;
	}

	private static void startOutro() {
		mode = Mode.OUTRO;
		step = 0;
		fade = 1f;
		fadeDelta = 0f;
		stopAtSilence = false;
	}

	private static void fadeOut(int ticks) {
		if (mode == Mode.OFF) return;
		fadeDelta = -1f / Math.max(1, ticks);
		stopAtSilence = true;
	}

	private static void stopNow() {
		mode = Mode.OFF;
		step = 0;
		fade = 0f;
		fadeDelta = 0f;
		stopAtSilence = false;
	}

	private static void play(MinecraftServer server, List<Note> notes) {
		Vec3 center = CrewkitAnchors.at(new int[] {14, 3, 11});
		long seed = server.overworld().getRandom().nextLong();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.level() != server.overworld()) continue;
			Vec3 ear = player.getEyePosition();
			Vec3 offset = center.subtract(ear);
			double distance = offset.length();
			if (distance > AUDIBLE_RADIUS) continue;
			Vec3 at = distance > PLACE_DISTANCE ? ear.add(offset.scale(PLACE_DISTANCE / distance)) : center;
			for (Note note : notes) {
				float volume = Math.min(1f, note.volume() * master * fade);
				if (volume <= 0.005f) continue;
				player.connection.send(new ClientboundSoundPacket(note.inst().sound, SoundSource.MASTER, at.x, at.y, at.z, volume, note.pitch(), seed));
			}
		}
	}

	// ---- commands ----

	private static void hookCommands(MinecraftServer server) {
		if (commandsHooked) return;
		commandsHooked = true;
		// Features are built on the first tick, after startup command registration, so add the node live
		// and also hook the callback so it survives /reload.
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
		registerCommands(server.getCommands().getDispatcher());
		for (ServerPlayer player : server.getPlayerList().getPlayers()) server.getCommands().sendCommands(player);
	}

	private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("crewkit")
				.requires(GoalControl::mayControl)
				.then(Commands.literal("music")
						.then(Commands.literal("on").executes(context -> {
							enabled = true;
							startLoop();
							context.getSource().sendSuccess(() -> Component.literal("CrewKit music on"), false);
							return 1;
						}))
						.then(Commands.literal("off").executes(context -> {
							enabled = false;
							fadeOut(15);
							context.getSource().sendSuccess(() -> Component.literal("CrewKit music off"), false);
							return 1;
						}))
						.then(Commands.literal("vol")
								.then(Commands.argument("level", FloatArgumentType.floatArg(0f, 1f)).executes(context -> {
									master = FloatArgumentType.getFloat(context, "level");
									float shown = master;
									context.getSource().sendSuccess(() -> Component.literal("CrewKit music volume " + shown), false);
									return 1;
								})))));
	}

	// ---- composition ----

	@SuppressWarnings("unchecked")
	private static List<Note>[] build(int length) {
		return (List<Note>[]) new List[length];
	}

	private static void add(List<Note>[] track, int tick, Inst inst, int midi, float volume) {
		if (tick < 0 || tick >= track.length) return;
		int n = midi - inst.baseMidi;
		while (n < 0) n += 12;
		while (n > 24) n -= 12;
		float pitch = (float) Math.pow(2.0, (n - 12) / 12.0);
		if (track[tick] == null) track[tick] = new ArrayList<>(4);
		track[tick].add(new Note(inst, pitch, volume));
	}

	/** Swung eighth e (0..7) within a bar. */
	private static int swing(int bar, int eighth) {
		return bar * BAR + (eighth / 2) * BEAT + (eighth % 2) * SWING;
	}

	private static void composeLoop() {
		int[] roots = {38, 43, 36, 45, 38, 43, 40, 45, 41, 41, 40, 45, 38, 43, 36, 45};
		boolean[] minor = {true, false, false, false, true, false, true, false, false, true, true, false, true, false, false, false};
		int[][] voicings = {
				{65, 69, 72, 76}, // Dm9
				{65, 71, 76},     // G13
				{64, 67, 71, 74}, // Cmaj9
				{61, 67, 70},     // A7b9
				{65, 69, 72, 76},
				{65, 71, 76},
				{62, 67, 71},     // Em7
				{61, 67, 70},
				{64, 67, 69, 72}, // Fmaj9
				{62, 65, 68, 72}, // Fm6
				{62, 67, 71},
				{61, 67, 70},
				{65, 69, 72, 76},
				{65, 71, 76},
				{64, 67, 71, 74},
				{61, 67, 70},
		};
		// Melody: {eighth, midi} pairs per bar, flute.
		int[][] melody = {
				{0, 81, 3, 77, 4, 76},
				{0, 74, 6, 76},
				{0, 79, 3, 76},
				{0, 73, 2, 76, 4, 79, 6, 82},
				{0, 81, 3, 77, 4, 76},
				{0, 74, 2, 77, 4, 76, 6, 74},
				{0, 71, 4, 74},
				{0, 73, 6, 70},
				{0, 76, 3, 79, 4, 81},
				{0, 80, 4, 77},
				{0, 79, 3, 76, 4, 74},
				{0, 73, 4, 76},
				{0, 77, 2, 76, 4, 74, 6, 72},
				{0, 71, 4, 74},
				{0, 72, 3, 76, 4, 79, 6, 83},
				{0, 82, 4, 79, 6, 76},
		};
		// Bossa comping: alternate two one-bar figures.
		int[][] comp = {{0, 3, 5}, {1, 4, 6}};

		for (int bar = 0; bar < LOOP_BARS; bar++) {
			int root = roots[bar];
			int next = roots[(bar + 1) % LOOP_BARS];
			int third = root + (minor[bar] ? 3 : 4);
			int approach = next + ((bar % 2 == 0) ? -1 : 1);
			int[] walk = {root, third, root + 7, approach};
			for (int beat = 0; beat < 4; beat++) {
				add(LOOP, bar * BAR + beat * BEAT, Inst.BASS, walk[beat], beat == 0 ? 0.95f : 0.8f);
			}
			for (int e : comp[bar % 2]) {
				for (int midi : voicings[bar]) add(LOOP, swing(bar, e), Inst.HARP, midi, 0.38f);
			}
			int[] line = melody[bar];
			for (int i = 0; i + 1 < line.length; i += 2) {
				add(LOOP, swing(bar, line[i]), Inst.FLUTE, line[i + 1], 0.7f);
			}
			if (bar % 4 == 0) add(LOOP, bar * BAR, Inst.CHIME, line[1], 0.25f);
			for (int e = 0; e < 8; e++) {
				add(LOOP, swing(bar, e), Inst.HAT, 76, e % 2 == 1 ? 0.3f : 0.18f);
			}
			add(LOOP, bar * BAR, Inst.KICK, 54, 0.35f);
			add(LOOP, bar * BAR + 2 * BEAT + SWING, Inst.KICK, 54, 0.25f);
		}
	}

	private static void composeOutro() {
		int[] roots = {36, 41, 43};
		int[][] stabs = {{60, 64, 67}, {60, 65, 69}, {59, 62, 67}};
		int[][] arps = {
				{72, 76, 79, 84, 79, 76, 79, 84},
				{72, 77, 81, 84, 81, 77, 81, 84},
				{74, 79, 83, 86, 83, 79, 83, 86},
		};
		for (int bar = 0; bar < 3; bar++) {
			int start = bar * OUT_BAR;
			int root = roots[bar];
			int[] bass = {root, root + 7, root + 12, root + 7};
			for (int beat = 0; beat < 4; beat++) {
				add(OUTRO, start + beat * OUT_BEAT, Inst.BASS, bass[beat], 0.95f);
				add(OUTRO, start + beat * OUT_BEAT, Inst.KICK, 54, 0.4f);
			}
			for (int e = 0; e < 8; e++) {
				int t = start + e * (OUT_BEAT / 2);
				add(OUTRO, t, Inst.FLUTE, arps[bar][e], 0.65f);
				add(OUTRO, t, Inst.HAT, 76, e % 2 == 1 ? 0.35f : 0.2f);
				if (e % 2 == 1) for (int midi : stabs[bar]) add(OUTRO, t, Inst.PLING, midi, 0.35f);
			}
			add(OUTRO, start, Inst.BELL, arps[bar][3], 0.45f);
		}
		// Rising xylophone run into the final hit (bar 4).
		int[] run = {67, 69, 71, 72, 74, 76, 77, 79};
		for (int i = 0; i < run.length; i++) add(OUTRO, 2 * OUT_BAR + 16 + i * 2, Inst.XYLO, run[i], 0.4f);
		int hit = 3 * OUT_BAR;
		for (int midi : new int[] {60, 64, 67, 71, 74}) add(OUTRO, hit, Inst.PLING, midi, 0.5f);
		for (int midi : new int[] {64, 67, 71, 74}) add(OUTRO, hit, Inst.HARP, midi, 0.45f);
		add(OUTRO, hit, Inst.BASS, 36, 1f);
		add(OUTRO, hit, Inst.KICK, 54, 0.5f);
		add(OUTRO, hit, Inst.BELL, 84, 0.55f);
		add(OUTRO, hit, Inst.CHIME, 91, 0.45f);
		add(OUTRO, hit + 6, Inst.CHIME, 88, 0.35f);
		add(OUTRO, hit + 12, Inst.CHIME, 84, 0.3f);
		add(OUTRO, hit + 18, Inst.CHIME, 79, 0.25f);
	}
}
