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
			player.world = (PresentationLevel) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(PresentationLevel.class);
			player.world.manager = new PresentationWaypoints();
			int checks = 0;
			var pack = new net.minecraft.server.packs.PathPackResources(new net.minecraft.server.packs.PackLocationInfo("locator-test", Component.literal("Locator test"), net.minecraft.server.packs.repository.PackSource.BUILT_IN, java.util.Optional.empty()), java.nio.file.Path.of("src/main/resources"));
			try (var resources = new net.minecraft.server.packs.resources.MultiPackResourceManager(net.minecraft.server.packs.PackType.CLIENT_RESOURCES, List.of(pack))) {
				var manager = new net.minecraft.client.resources.WaypointStyleManager();
				var loaded = new java.util.HashMap<net.minecraft.resources.Identifier, WaypointStyle>();
				net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener.scanDirectory(resources, net.minecraft.resources.FileToIdConverter.json("waypoint_style"), JsonOps.INSTANCE, WaypointStyle.CODEC, loaded);
				var apply = net.minecraft.client.resources.WaypointStyleManager.class.getDeclaredMethod("apply", java.util.Map.class, net.minecraft.server.packs.resources.ResourceManager.class, net.minecraft.util.profiling.ProfilerFiller.class);
				apply.setAccessible(true); apply.invoke(manager, loaded, resources, net.minecraft.util.profiling.InactiveProfiler.INSTANCE);
				var key = net.minecraft.resources.ResourceKey.create(net.minecraft.world.waypoints.WaypointStyleAssets.ROOT_ID, net.minecraft.resources.Identifier.parse("arenaagents:agent/c00"));
				check(manager.get(key).sprite(0).equals(net.minecraft.resources.Identifier.parse("arenaagents:hud/locator_bar_dot/agent/c00")), "resource reload manager resolves agent face rather than missing-texture style");
				checks++;
				var vanillaRoot = java.nio.file.Path.of("build/locator-vanilla");
				var atlasFile = vanillaRoot.resolve("assets/minecraft/atlases/gui.json");
				java.nio.file.Files.createDirectories(atlasFile.getParent());
				java.nio.file.Files.write(atlasFile, resource("assets/minecraft/atlases/gui.json"));
				var vanilla = new net.minecraft.server.packs.PathPackResources(new net.minecraft.server.packs.PackLocationInfo("vanilla-test", Component.literal("Vanilla"), net.minecraft.server.packs.repository.PackSource.BUILT_IN, java.util.Optional.empty()), vanillaRoot);
				try (var atlasResources = new net.minecraft.server.packs.resources.MultiPackResourceManager(net.minecraft.server.packs.PackType.CLIENT_RESOURCES, List.of(vanilla, pack))) {
					net.minecraft.client.renderer.texture.atlas.SpriteSources.bootstrap();
					var sources = net.minecraft.client.renderer.texture.atlas.SpriteSourceList.load(atlasResources, net.minecraft.resources.Identifier.parse("minecraft:gui")).list(atlasResources);
					var loader = net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader.create(java.util.Set.of());
					var contents = sources.stream().map(source -> source.get(loader)).filter(java.util.Objects::nonNull).toList();
					try {
						check(contents.stream().anyMatch(sprite -> sprite.name().equals(manager.get(key).sprite(0))), "Minecraft GUI atlas loads agent face rather than missing texture");
						var stitcher = new net.minecraft.client.renderer.texture.Stitcher<net.minecraft.client.renderer.texture.SpriteContents>(4096, 4096, 0, 0);
						contents.forEach(stitcher::registerSprite); stitcher.stitch(); checks++;
					} finally { contents.forEach(net.minecraft.client.renderer.texture.SpriteContents::close); }
				}
			}
			for (String appearance : List.of("codex", "claude", "cursor", "gemini", "kimi")) {
				var actor = new SkitActor(AgentId.random(), "GPT 6-Astra_v2", appearance, false);
				SkitActors.applyPresentation(player, actor);
				check(player.world.manager.sentStyle == player.icon.style, "stationary cast icon is refreshed for connected viewers");
				int updates = player.world.manager.updates;
				SkitActors.applyPresentation(player, actor);
				check(player.world.manager.updates == updates, "unchanged cast presentation does not resend waypoint packets");
				checks += 2;
				check(player.label.getString().equals(actor.name()) && player.visible, "cast body has exact visible label");
				check(player.icon.color.orElseThrow() == 0xFFFFFFFF, "agent face is not tinted by UUID color");
				String stylePath = "assets/arenaagents/waypoint_style/" + player.icon.style.identifier().getPath() + ".json";
				var style = WaypointStyle.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(new String(resource(stylePath), StandardCharsets.UTF_8))).getOrThrow();
				var sprite = style.sprite(0);
				String pngPath = "assets/" + sprite.getNamespace() + "/textures/gui/sprites/" + sprite.getPath() + ".png";
				var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(resource(pngPath)));
				check(image != null && image.getWidth() == 16 && image.getHeight() == 16, "vanilla resolves a real 16px locator face");
				var colors = new java.util.HashSet<Integer>();
				for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) colors.add(image.getRGB(x, y));
				check(colors.size() > 1, "locator face has detail rather than a solid missing-texture square");
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
	private static final class PresentationLevel extends net.minecraft.server.level.ServerLevel {
		private PresentationWaypoints manager;
		private PresentationLevel() { super(null, null, null, null, null, null, false, 0, null, false); }
		@Override public net.minecraft.server.waypoints.ServerWaypointManager getWaypointManager() { return manager; }
	}
	private static final class PresentationWaypoints extends net.minecraft.server.waypoints.ServerWaypointManager {
		private Object sentStyle;
		private int updates;
		@Override public void untrackWaypoint(net.minecraft.world.waypoints.WaypointTransmitter source) { sentStyle = null; }
		@Override public void trackWaypoint(net.minecraft.world.waypoints.WaypointTransmitter source) { sentStyle = source.waypointIcon().style; updates++; }
	}
	private static final class PresentationPlayer extends ServerPlayer {
		private PresentationLevel world;
		@Override public net.minecraft.server.level.ServerLevel level() { return world; }
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
