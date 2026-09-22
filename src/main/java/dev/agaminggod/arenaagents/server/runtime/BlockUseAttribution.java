package dev.agaminggod.arenaagents.server.runtime;

import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;

/** Causality exists only during the synchronous block interaction, without changing vanilla damage. */
public final class BlockUseAttribution {
	private static final ThreadLocal<ServerPlayer> ACTIVE = new ThreadLocal<>();
	private BlockUseAttribution() { }

	public static <T> T during(ServerPlayer player, Supplier<T> interaction) {
		ServerPlayer previous = ACTIVE.get();
		ACTIVE.set(player);
		try { return interaction.get(); }
		finally { if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous); }
	}

	public static ServerPlayer responsible(DamageSource source) {
		if (source.getEntity() instanceof ServerPlayer player) return player;
		return source.getEntity() == null && source.is(DamageTypes.BAD_RESPAWN_POINT) ? ACTIVE.get() : null;
	}
}
