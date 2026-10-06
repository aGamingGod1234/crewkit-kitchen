package dev.agaminggod.arenaagents.server.pov;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/** Fallback and distance rules of the POV view redirect; the mixins themselves need a running server. */
public final class PovViewRedirectVerification {
	private static final String[] MIXINS = {
			"ChunkMapPovViewMixin",
			"ChunkMapTrackedEntityPovMixin",
			"PlayerChunkSenderPovMixin",
			"PlayerListBroadcastPovMixin",
			"ServerLevelParticlesPovMixin"
	};

	private PovViewRedirectVerification() {
	}

	public static void main(String[] args) {
		System.out.println("PASS: " + verify() + " POV view redirect assertions");
	}

	public static int verify() {
		// ChunkPos's static init reaches the chunk status registry; both calls are no-ops once bootstrapped.
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		int passed = 0;

		ChunkPos chunk = new ChunkPos(3, -7);
		passed += same(chunk, PovViewRedirect.chunkAnchor(null, chunk), "chunk view stays on the body without an anchor");
		Vec3 position = new Vec3(1.5, 64.0, -2.25);
		passed += same(position, PovViewRedirect.positionAnchor(null, position), "entity range stays on the body without an anchor");
		BlockPos block = new BlockPos(1, 64, -3);
		passed += same(block, PovViewRedirect.blockAnchor(null, block), "particle range stays on the body without an anchor");
		passed += equal(12.5, PovViewRedirect.anchorX(null, 12.5), "sound x stays on the body without an anchor");
		passed += equal(-64.0, PovViewRedirect.anchorY(null, -64.0), "sound y stays on the body without an anchor");
		passed += equal(30_000_000.0, PovViewRedirect.anchorZ(null, 30_000_000.0), "sound z stays on the body without an anchor");
		passed += check(PovViewRedirect.anchorOf(null) == null, "a missing player has no anchor");
		passed += check(!PovViewRedirect.followsAnchor(null), "a missing player does not follow an anchor");
		passed += check(!PovViewRedirect.isAnchorOf(null, null), "no entity is the anchor of a missing viewer");

		Vec3 blast = new Vec3(0.0, 64.0, 0.0);
		passed += equal(49.0, PovViewRedirect.nearestDistanceSqr(null, blast, 49.0), "explosion range stays on the body without an anchor");
		passed += equal(49.0, PovViewRedirect.nearestDistanceSqr(49.0, blast, null), "no anchor position keeps the body distance");
		passed += equal(4.0, PovViewRedirect.nearestDistanceSqr(49.0, blast, new Vec3(2.0, 64.0, 0.0)), "a nearer agent brings the explosion in range");
		passed += equal(49.0, PovViewRedirect.nearestDistanceSqr(49.0, blast, new Vec3(100.0, 64.0, 0.0)), "a nearer body keeps its knockback packet");
		passed += equal(49.0, PovViewRedirect.nearestDistanceSqr(49.0, null, new Vec3(2.0, 64.0, 0.0)), "a missing explosion centre keeps the body distance");

		List<ServerPlayer> viewers = new ArrayList<>();
		PovViewRedirect.addAnchoredViewers(List.of(), viewers);
		passed += check(viewers.isEmpty(), "no players adds no re-checked viewers");
		PovViewRedirect.addAnchoredViewers(Arrays.asList((ServerPlayer) null), viewers);
		passed += check(viewers.isEmpty(), "players without an anchor are not re-checked every tick");

		try (var input = PovViewRedirectVerification.class.getClassLoader().getResourceAsStream("arenaagents.mixins.json")) {
			if (input == null) throw new AssertionError("server mixin configuration is missing");
			String config = new String(input.readAllBytes(), StandardCharsets.UTF_8);
			for (String mixin : MIXINS) {
				passed += check(config.contains("\"" + mixin + "\""), mixin + " is registered in the server mixin configuration");
			}
		} catch (java.io.IOException exception) {
			throw new AssertionError("could not read server mixin configuration", exception);
		}
		return passed;
	}

	private static int same(Object expected, Object actual, String label) {
		if (expected != actual) throw new AssertionError(label + ": expected the fallback instance, got " + actual);
		return 1;
	}

	private static int equal(double expected, double actual, String label) {
		if (Double.compare(expected, actual) != 0) throw new AssertionError(label + ": expected " + expected + " but was " + actual);
		return 1;
	}

	private static int check(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
		return 1;
	}
}
