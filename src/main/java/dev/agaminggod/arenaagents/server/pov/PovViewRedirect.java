package dev.agaminggod.arenaagents.server.pov;

import dev.agaminggod.arenaagents.pov.PovViewAnchors;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * Answers "where is this player?" for the reads that decide what its client is sent, so an operator in a POV session
 * receives chunks, entities, sounds, particles, block cracks and explosions around the agent instead of its body.
 *
 * <p>The mixins only replace reads that feed the client view: the chunk tracking centre, entity tracking distance,
 * chunk send order and the per-player distance checks for broadcast effects. The body's own chunk ticket is untouched
 * because {@code ChunkMap.updatePlayerStatus} and {@code ChunkMap.move} register it with the distance manager through
 * {@code SectionPos.of(player)}, the body's real section, which no mixin here redirects. So the body's area stays
 * loaded and ticking, mobs still spawn around it, and the body stays exactly as vulnerable as before.
 *
 * <p>Every helper returns the vanilla value when {@link PovViewAnchors#hasAnchor} is false, which costs one map lookup.
 * {@link PovViewAnchors#anchor} is empty when the agent is offline, removed or in another dimension; the view then
 * falls back to the body rather than pointing into a different level.
 */
public final class PovViewRedirect {
	private PovViewRedirect() {
	}

	/** The agent whose surroundings this player's client receives, or null when it receives its own. */
	public static ServerPlayer anchorOf(ServerPlayer player) {
		if (player == null || !PovViewAnchors.hasAnchor(player.getUUID())) return null;
		return PovViewAnchors.anchor(player).orElse(null);
	}

	public static boolean followsAnchor(ServerPlayer player) {
		return anchorOf(player) != null;
	}

	public static ChunkPos chunkAnchor(ServerPlayer player, ChunkPos fallback) {
		ServerPlayer anchor = anchorOf(player);
		return anchor == null ? fallback : anchor.chunkPosition();
	}

	public static Vec3 positionAnchor(ServerPlayer player, Vec3 fallback) {
		ServerPlayer anchor = anchorOf(player);
		return anchor == null ? fallback : anchor.position();
	}

	public static BlockPos blockAnchor(ServerPlayer player, BlockPos fallback) {
		ServerPlayer anchor = anchorOf(player);
		return anchor == null ? fallback : anchor.blockPosition();
	}

	public static double anchorX(ServerPlayer player, double fallback) {
		ServerPlayer anchor = anchorOf(player);
		return anchor == null ? fallback : anchor.getX();
	}

	public static double anchorY(ServerPlayer player, double fallback) {
		ServerPlayer anchor = anchorOf(player);
		return anchor == null ? fallback : anchor.getY();
	}

	public static double anchorZ(ServerPlayer player, double fallback) {
		ServerPlayer anchor = anchorOf(player);
		return anchor == null ? fallback : anchor.getZ();
	}

	/**
	 * True when {@code entity} is the agent this viewer's camera sits on. The client binds its camera to that entity,
	 * so it must be sent even where vanilla would hide it (a spectator-mode agent from a non-spectator viewer).
	 */
	public static boolean isAnchorOf(ServerPlayer viewer, Entity entity) {
		if (!(entity instanceof ServerPlayer)) return false;
		return anchorOf(viewer) == entity;
	}

	/**
	 * Squared distance used to decide whether an explosion packet reaches this player. Explosions use the nearer of
	 * body and agent rather than replacing the body: the packet also carries the knockback the body's client applies,
	 * and the client already culls effects that are far from its camera.
	 */
	public static double nearestDistanceSqr(ServerPlayer player, Vec3 point, double bodyDistanceSqr) {
		ServerPlayer anchor = anchorOf(player);
		return nearestDistanceSqr(bodyDistanceSqr, point, anchor == null ? null : anchor.position());
	}

	static double nearestDistanceSqr(double bodyDistanceSqr, Vec3 point, Vec3 anchorPosition) {
		if (point == null || anchorPosition == null) return bodyDistanceSqr;
		return Math.min(bodyDistanceSqr, anchorPosition.distanceToSqr(point));
	}

	/**
	 * Adds every anchored player to {@code viewers}, the list {@code ChunkMap.tick} re-evaluates every tracked entity
	 * against. Vanilla only re-evaluates a player when its own body changes section or it sends a move packet; an
	 * operator's body stands still, so without this entities near a moving agent, or in chunks that finished sending,
	 * would not appear until they moved themselves.
	 */
	public static void addAnchoredViewers(List<ServerPlayer> players, List<ServerPlayer> viewers) {
		for (int index = 0; index < players.size(); index++) {
			ServerPlayer player = players.get(index);
			if (followsAnchor(player)) viewers.add(player);
		}
	}
}
