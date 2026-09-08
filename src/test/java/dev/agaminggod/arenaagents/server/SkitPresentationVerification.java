package dev.agaminggod.arenaagents.server;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.agaminggod.arenaagents.agent.AgentId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.client.resources.WaypointStyle;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.waypoints.Waypoint;

/** Exercises the real cast presentation and vanilla waypoint asset resolver without a renderer. */
public final class SkitPresentationVerification {
	private SkitPresentationVerification() { }
	public static int verify() {
		try {
			var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			unsafeField.setAccessible(true);
			var player = (PresentationPlayer) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(PresentationPlayer.class);
			player.icon = new Waypoint.Icon();
			int checks = 0;
			for (String appearance : List.of("codex", "claude", "cursor", "gemini", "kimi")) {
				var actor = new SkitActor(AgentId.random(), "GPT 6-Astra_v2", appearance, false);
				SkitActors.applyPresentation(player, actor);
				check(player.label.getString().equals(actor.name()) && player.visible, "cast body has exact visible label");
				check(player.icon.color.orElseThrow() == 0xFFFFFFFF, "brand logo is not tinted by UUID color");
				String stylePath = "assets/arenaagents/waypoint_style/" + player.icon.style.identifier().getPath() + ".json";
				var style = WaypointStyle.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(new String(resource(stylePath), StandardCharsets.UTF_8))).getOrThrow();
				var sprite = style.sprite(0);
				String pngPath = "assets/" + sprite.getNamespace() + "/textures/gui/sprites/" + sprite.getPath() + ".png";
				var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(resource(pngPath)));
				check(image != null && image.getWidth() == 16 && image.getHeight() == 16, "vanilla resolves a real 16px locator logo");
				var colors = new java.util.HashSet<Integer>();
				for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) colors.add(image.getRGB(x, y));
				check(colors.size() > 2, "locator icon contains logo detail rather than a solid missing-texture square");
				checks += 4;
			}
			return checks;
		} catch (Exception error) { throw new AssertionError("Cast presentation verification failed", error); }
	}
	private static byte[] resource(String path) throws java.io.IOException {
		try (var stream = SkitPresentationVerification.class.getClassLoader().getResourceAsStream(path)) {
			if (stream == null) throw new AssertionError("Missing locator asset: " + path);
			return stream.readAllBytes();
		}
	}
	private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
	private static final class PresentationPlayer extends ServerPlayer {
		private Component label;
		private boolean visible;
		private Waypoint.Icon icon;
		private PresentationPlayer() { super(null, null, null, null); }
		@Override public Component getCustomName() { return label; }
		@Override public void setCustomName(Component value) { label = value; }
		@Override public void setCustomNameVisible(boolean value) { visible = value; }
		@Override public Waypoint.Icon waypointIcon() { return icon; }
	}
}
