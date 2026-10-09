package dev.agaminggod.arenaagents.crewkit.core;

import java.util.Locale;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

/**
 * Spawners for CrewKit display entities. Fixed billboard, yaw 0 (faces south, toward the camera at
 * ck_player), full brightness so boards read on a projector. {@code extraSnbt} holds extra compound
 * entries without braces, e.g. {@code "background:0,shadow:1b"}, or "" for none.
 *
 * <pre>
 * CrewkitDisplay n = CrewkitDisplays.text(server, pos, "ck_boards", CrewkitText.of("S$150.00", CrewkitText.GREEN, true), 4f, "");
 * n.text(CrewkitText.of("S$120.00", CrewkitText.AMBER, true));
 * CrewkitDisplay bar = CrewkitDisplays.block(server, pos, "ck_boards", "minecraft:lime_concrete", 7f, 0.3f, 0.05f, "");
 * bar.transform(3.5f, 0.3f, 0.05f, 0, 0, 0, 0, 10); // shrink width over 10 ticks, left edge fixed
 * CrewkitSounds.play(server, pos, "minecraft:ui.button.click", 0.4f, 1.6f);
 * </pre>
 */
public final class CrewkitDisplays {
	public static final String TAG = "crewkit";
	private static final int FULL_BRIGHT = 15;

	private CrewkitDisplays() {}

	/** Text display with uniform scale. The text is centred on {@code pos} and grows upward from it. */
	public static CrewkitDisplay text(MinecraftServer server, Vec3 pos, String featureTag, String componentSnbt, float scale, String extraSnbt) {
		String nbt = "text:" + componentSnbt + ",background:0,line_width:400,alignment:\"center\",shadow:1b,"
				+ "transformation:" + transformation(scale, scale, scale, 0, 0, 0, 0) + join(extraSnbt);
		return spawn(server, "minecraft:text_display", pos, featureTag, nbt);
	}

	/** Item display; {@code itemId} like "minecraft:paper", {@code displayMode} like "fixed", "gui", "ground". */
	public static CrewkitDisplay item(MinecraftServer server, Vec3 pos, String featureTag, String itemId, float scale, String displayMode, String extraSnbt) {
		String nbt = "item:{id:\"" + CrewkitText.escape(itemId) + "\",count:1},item_display:\"" + CrewkitText.escape(displayMode) + "\","
				+ "transformation:" + transformation(scale, scale, scale, 0, 0, 0, 0) + join(extraSnbt);
		return spawn(server, "minecraft:item_display", pos, featureTag, nbt);
	}

	/**
	 * Block display with per-axis scale. The model grows from the min corner (+x, +y, +z) of {@code pos},
	 * so a bar whose x-scale shrinks keeps its left (west) edge fixed.
	 */
	public static CrewkitDisplay block(MinecraftServer server, Vec3 pos, String featureTag, String blockId, float sx, float sy, float sz, String extraSnbt) {
		String nbt = "block_state:{Name:\"" + CrewkitText.escape(blockId) + "\"},"
				+ "transformation:" + transformation(sx, sy, sz, 0, 0, 0, 0) + join(extraSnbt);
		return spawn(server, "minecraft:block_display", pos, featureTag, nbt);
	}

	/** Kill every CrewKit entity carrying {@code featureTag}. */
	public static void killTag(MinecraftServer server, String featureTag) {
		run(server, "kill @e[tag=" + TAG + ",tag=" + featureTag + "]");
	}

	public static String transformation(float sx, float sy, float sz, float tx, float ty, float tz, float rollRadians) {
		double half = rollRadians / 2.0;
		return String.format(Locale.ROOT,
				"{left_rotation:[0f,0f,%.5ff,%.5ff],right_rotation:[0f,0f,0f,1f],translation:[%.4ff,%.4ff,%.4ff],scale:[%.4ff,%.4ff,%.4ff]}",
				Math.sin(half), Math.cos(half), tx, ty, tz, sx, sy, sz);
	}

	private static CrewkitDisplay spawn(MinecraftServer server, String type, Vec3 pos, String featureTag, String nbt) {
		UUID uuid = UUID.randomUUID();
		String tags = "Tags:[\"" + TAG + "\",\"" + CrewkitText.escape(featureTag) + "\"]";
		String common = "UUID:" + uuidArray(uuid) + "," + tags + ",billboard:\"fixed\",Rotation:[0f,0f],"
				+ "brightness:{sky:" + FULL_BRIGHT + ",block:" + FULL_BRIGHT + "},view_range:4f,teleport_duration:3";
		run(server, String.format(Locale.ROOT, "summon %s %.4f %.4f %.4f {%s,%s}", type, pos.x, pos.y, pos.z, common, nbt));
		CrewkitDisplay display = new CrewkitDisplay(server, uuid, type, pos);
		if (!display.alive()) CrewkitDispatcher.LOGGER.warn("CrewKit: {} did not spawn at {} (chunk not loaded?)", type, pos);
		return display;
	}

	/** Run a command as the server in the overworld, silently. Failures go to the debug log. */
	public static void run(MinecraftServer server, String command) {
		CommandSourceStack source = server.createCommandSourceStack()
				.withLevel(server.overworld())
				.withSuppressedOutput()
				.withCallback((success, result) -> {
					if (!success) CrewkitDispatcher.LOGGER.debug("CrewKit command failed: {}", command);
				});
		server.getCommands().performPrefixedCommand(source, command);
	}

	private static String uuidArray(UUID uuid) {
		long most = uuid.getMostSignificantBits();
		long least = uuid.getLeastSignificantBits();
		return "[I;" + (int) (most >> 32) + "," + (int) most + "," + (int) (least >> 32) + "," + (int) least + "]";
	}

	private static String join(String extra) {
		return extra == null || extra.isBlank() ? "" : "," + extra;
	}
}
