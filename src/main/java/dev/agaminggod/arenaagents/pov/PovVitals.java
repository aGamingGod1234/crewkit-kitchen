package dev.agaminggod.arenaagents.pov;

import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.GameType;

/** HUD numbers of the watched agent, applied to the client's stand-in player. */
public record PovVitals(
		float health,
		float maxHealth,
		float absorption,
		int armor,
		int food,
		float saturation,
		int air,
		int maxAir,
		int xpLevel,
		float xpProgress,
		GameType gameMode
) {
	public static final int MAX_FOOD = 20;
	/** Vanilla runs the air supply down to -20 while drowning before resetting it, so raw values may be negative. */
	public static final int MIN_AIR = -20;
	public static final StreamCodec<RegistryFriendlyByteBuf, PovVitals> CODEC = StreamCodec.composite(
			ByteBufCodecs.FLOAT, PovVitals::health,
			ByteBufCodecs.FLOAT, PovVitals::maxHealth,
			ByteBufCodecs.FLOAT, PovVitals::absorption,
			ByteBufCodecs.VAR_INT, PovVitals::armor,
			ByteBufCodecs.VAR_INT, PovVitals::food,
			ByteBufCodecs.FLOAT, PovVitals::saturation,
			ByteBufCodecs.VAR_INT, PovVitals::air,
			ByteBufCodecs.VAR_INT, PovVitals::maxAir,
			ByteBufCodecs.VAR_INT, PovVitals::xpLevel,
			ByteBufCodecs.FLOAT, PovVitals::xpProgress,
			GameType.STREAM_CODEC, PovVitals::gameMode,
			PovVitals::new
	);

	public PovVitals {
		PovPayloads.requireNonNegative(health, "health");
		PovPayloads.requireNonNegative(maxHealth, "maxHealth");
		PovPayloads.requireNonNegative(absorption, "absorption");
		PovPayloads.requireNonNegative(saturation, "saturation");
		PovPayloads.requireRange(xpProgress, 0f, 1f, "xpProgress");
		if (armor < 0) throw new IllegalArgumentException("armor must not be negative");
		if (food < 0 || food > MAX_FOOD) throw new IllegalArgumentException("food must be within 0.." + MAX_FOOD);
		if (maxAir < 0) throw new IllegalArgumentException("maxAir must not be negative");
		if (air < MIN_AIR || air > maxAir) throw new IllegalArgumentException("air must be within " + MIN_AIR + "..maxAir");
		if (xpLevel < 0) throw new IllegalArgumentException("xpLevel must not be negative");
		gameMode = Objects.requireNonNull(gameMode, "gameMode must not be null");
	}
}
