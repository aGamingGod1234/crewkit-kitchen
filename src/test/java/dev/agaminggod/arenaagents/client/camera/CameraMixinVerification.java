package dev.agaminggod.arenaagents.client.camera;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.knot.Knot;

/** Verifies the required accessor through Fabric without launching Minecraft client main. */
public final class CameraMixinVerification {
	private CameraMixinVerification() {
	}

	public static void main(String[] args) throws Exception {
		ClassLoader target = new Knot(EnvType.CLIENT).init(args);
		Class<?> playerType = target.loadClass("net.minecraft.world.entity.player.Player");
		if (java.util.Arrays.stream(playerType.getDeclaredMethods()).noneMatch(method -> method.getName().contains("arenaagents$directorLabel"))) {
			throw new AssertionError("Fabric did not apply the Director chat-name mixin");
		}
		target.loadClass("net.minecraft.client.renderer.entity.player.AvatarRenderer");
		target.loadClass("net.minecraft.client.gui.contextualbar.LocatorBarRenderer");
		Class<?> gameRenderer = target.loadClass("net.minecraft.client.renderer.GameRenderer");
		if (java.util.Arrays.stream(gameRenderer.getDeclaredMethods()).noneMatch(method -> method.getName().contains("arenaagents$planItemIcons")))
			throw new AssertionError("Fabric did not apply the render-thread plan icon hook");
		verifyProfileSkinLookup(target);
        Class<?> cartType = target.loadClass("net.minecraft.world.entity.vehicle.minecart.Minecart");
        for (String method : new String[]{"arenaagents$cameraDrop", "arenaagents$cameraPick"}) {
            if (java.util.Arrays.stream(cartType.getDeclaredMethods()).noneMatch(candidate -> candidate.getName().contains(method)))
                throw new AssertionError("Fabric did not apply " + method + " to Minecart");
        }
		Class<?> cameraType = target.loadClass("net.minecraft.client.Camera");
		Class<?> accessor = target.loadClass("dev.agaminggod.arenaagents.client.mixin.CameraEyeHeightAccessor");
		Object camera = cameraType.getConstructor().newInstance();
		if (!accessor.isInstance(camera)) throw new AssertionError("Fabric did not apply CameraEyeHeightAccessor to Camera");

		var setHeight = accessor.getMethod("arenaagents$setEyeHeight", float.class);
		var setOldHeight = accessor.getMethod("arenaagents$setEyeHeightOld", float.class);
		setHeight.invoke(camera, 1.25F);
		setOldHeight.invoke(camera, 0.75F);
		assertHeight(cameraType, camera, "eyeHeight", 1.25F);
		assertHeight(cameraType, camera, "eyeHeightOld", 0.75F);
		setHeight.invoke(camera, 0.0F);
		setOldHeight.invoke(camera, 0.0F);
		assertHeight(cameraType, camera, "eyeHeight", 0.0F);
		assertHeight(cameraType, camera, "eyeHeightOld", 0.0F);
		System.out.println("Camera, plan icon and agent presentation mixin verification passed (20 checks); Minecraft client main was not invoked.");
	}

	private static void verifyProfileSkinLookup(ClassLoader target) throws Exception {
		Class<?> registryType = target.loadClass("dev.agaminggod.arenaagents.agent.AgentRegistry");
		Object registry = registryType.getMethod("createDefault", Runnable.class, Consumer.class)
				.invoke(null, (Runnable) () -> { }, (Consumer<Object>) transition -> { });
		Object agent = registryType.getMethod("create", String.class, String.class, String.class, Optional.class, long.class)
				.invoke(registry, "codex", "gpt-6.1-sol", "medium", Optional.empty(), 1L);
		Class<?> snapshotType = target.loadClass("dev.agaminggod.arenaagents.control.AgentControlSnapshot");
		Object snapshot = snapshotType.getMethod("fromRecords", boolean.class, long.class, List.class)
				.invoke(null, true, 1L, List.of(agent));
		Class<?> rosterType = target.loadClass("dev.agaminggod.arenaagents.client.control.AgentClientRoster");
		Field snapshots = rosterType
				.getDeclaredField("SNAPSHOTS");
		snapshots.setAccessible(true);
		Object store = snapshots.get(null);
		store.getClass().getMethod("accept", snapshotType).invoke(store, snapshot);
		try {
			Class<?> profileType = target.loadClass("com.mojang.authlib.GameProfile");
			String name = "GPT_6_1_Sol";
			UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name)
					.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			Object profile = profileType.getConstructor(UUID.class, String.class).newInstance(offlineUuid, name);
			if (!Optional.of("GPT-6.1 Sol").equals(rosterType.getMethod("displayName", profileType).invoke(null, profile)))
				throw new AssertionError("Allocated agent names did not reach the client display-name API");
			Class<?> playerInfo = target.loadClass("net.minecraft.client.multiplayer.PlayerInfo");
			if (java.util.Arrays.stream(playerInfo.getDeclaredMethods()).noneMatch(method -> method.getName().contains("arenaagents$tabLabel")))
				throw new AssertionError("Fabric did not apply the tab-list name mixin");
			if (java.util.Arrays.stream(playerInfo.getDeclaredMethods()).noneMatch(method -> method.getName().contains("arenaagents$agentTabSkin")))
				throw new AssertionError("Fabric did not apply the tab-list agent face mixin");
			Class<?> player = target.loadClass("net.minecraft.world.entity.player.Player");
			if (java.util.Arrays.stream(player.getDeclaredMethods()).noneMatch(method -> method.getName().contains("arenaagents$clientLabel")))
				throw new AssertionError("Fabric did not apply the target HUD name mixin");
			Class<?> skins = target.loadClass("dev.agaminggod.arenaagents.client.render.AgentPlayerSkins");
			Object bodySkin = ((Optional<?>) skins.getMethod("forProfile", profileType).invoke(null, profile)).orElseThrow();
			String texture = bodyTexture(bodySkin);
			if (!texture.startsWith("arenaagents:textures/entity/brand_openai_agent_") || !texture.endsWith(".png"))
				throw new AssertionError("Agent body did not resolve a loadable bundled texture: " + texture);
			try (var resource = target.getResourceAsStream("assets/arenaagents/" + texture.substring("arenaagents:".length()))) {
				if (resource == null) throw new AssertionError("Profile skin texture is missing: " + texture);
			}
			Class<?> managerType = target.loadClass("net.minecraft.client.resources.SkinManager");
			if (java.util.Arrays.stream(managerType.getDeclaredMethods()).noneMatch(method -> method.getName().contains("arenaagents$profileSkin")))
				throw new AssertionError("Fabric did not apply the profile skin lookup mixin");
			Object manager = managerType.getConstructor(Path.class, target.loadClass("net.minecraft.server.Services"),
					target.loadClass("net.minecraft.client.renderer.texture.SkinTextureDownloader"), Executor.class)
					.newInstance(Path.of("."), null, null, (Executor) Runnable::run);
			for (boolean requireSecure : new boolean[]{false, true}) {
				// This is Facebar's actual entry point, not a call to the resolver alone.
				// A missing hook reaches the null vanilla services and fails immediately.
				Supplier<?> lookup = (Supplier<?>) managerType.getMethod("createLookup", profileType, boolean.class)
						.invoke(manager, profile, requireSecure);
				if (!texture.equals(bodyTexture(lookup.get()))) throw new AssertionError("Profile UI and body skins disagree");
			}
			Object sameNameHuman = profileType.getConstructor(UUID.class, String.class).newInstance(UUID.randomUUID(), name);
			if (((Optional<?>) skins.getMethod("forProfile", profileType).invoke(null, sameNameHuman)).isPresent())
				throw new AssertionError("A human with the same name inherited an agent skin");
			Object ordinary = profileType.getConstructor(UUID.class, String.class).newInstance(UUID.randomUUID(), "Steve");
			if (((Optional<?>) skins.getMethod("forProfile", profileType).invoke(null, ordinary)).isPresent())
				throw new AssertionError("An ordinary player inherited an agent skin");
		} finally {
			store.getClass().getMethod("clear").invoke(store);
		}
	}

	private static String bodyTexture(Object skin) throws Exception {
		Object body = skin.getClass().getMethod("body").invoke(skin);
		return body.getClass().getMethod("id").invoke(body).toString();
	}

	private static void assertHeight(Class<?> cameraType, Object camera, String name, float expected) throws Exception {
		Field field = cameraType.getDeclaredField(name);
		field.setAccessible(true);
		float actual = field.getFloat(camera);
		if (Float.compare(expected, actual) != 0) throw new AssertionError(name + ": expected " + expected + ", got " + actual);
	}
}
