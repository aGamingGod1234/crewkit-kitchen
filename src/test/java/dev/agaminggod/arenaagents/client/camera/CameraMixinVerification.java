package dev.agaminggod.arenaagents.client.camera;

import java.lang.reflect.Field;
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
		System.out.println("Camera and Director presentation mixin verification passed (9 checks); Minecraft client main was not invoked.");
	}

	private static void assertHeight(Class<?> cameraType, Object camera, String name, float expected) throws Exception {
		Field field = cameraType.getDeclaredField(name);
		field.setAccessible(true);
		float actual = field.getFloat(camera);
		if (Float.compare(expected, actual) != 0) throw new AssertionError(name + ": expected " + expected + ", got " + actual);
	}
}
