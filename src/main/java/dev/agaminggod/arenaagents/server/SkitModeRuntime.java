package dev.agaminggod.arenaagents.server;

import carpet.helpers.EntityPlayerActionPack;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Server-authoritative placement and timeline playback. It never participates in normal agent control. */
public final class SkitModeRuntime {
	private static final Map<MinecraftServer, Map<AgentId, Playback>> PLAYBACK = new ConcurrentHashMap<>();

	private SkitModeRuntime() {
	}

	public static boolean enabled(MinecraftServer server) {
		return SkitModeSavedData.get(server).enabled();
	}

	public static boolean setEnabled(MinecraftServer server, boolean enabled) {
		SkitModeSavedData data = SkitModeSavedData.get(server);
		data.setEnabled(enabled);
		if (!enabled) PLAYBACK.remove(server);
		return data.enabled();
	}

	public static void requireEnabled(MinecraftServer server) {
		if (!enabled(server)) throw new AgentDomainException("SKIT_MODE_DISABLED", "Enable skit mode with /codex skit on first");
	}

	public static SkitPlacement place(CodexAgentManager manager, String selector, ServerLevel level, double x, double y, double z, float yaw, float pitch) {
		Objects.requireNonNull(manager, "manager must not be null");
		Objects.requireNonNull(level, "level must not be null");
		requireEnabled(manager.server());
		AgentRecord record = manager.resolve(selector);
		SkitPlacement placement = new SkitPlacement(level.dimension().identifier().toString(), x, y, z, yaw, pitch);
		manager.findAgentPlayer(record.agentId()).ifPresentOrElse(
				player -> teleport(player, level, placement),
				() -> { throw new AgentDomainException("AGENT_NOT_PRESENT", "Agent has not joined the world yet"); }
		);
		SkitModeSavedData.get(manager.server()).putPlacement(record.agentId().toString(), placement);
		return placement;
	}

	/** Persists and applies a placement captured from the controlling player. */
	public static SkitPlacement placeFromPlayer(CodexAgentManager manager, String selector, ServerPlayer player) {
		SkitPlacement placement = SkitPlacement.fromPlayer(player);
		return place(manager, selector, (ServerLevel) player.level(), placement.x(), placement.y(), placement.z(), placement.yaw(), placement.pitch());
	}

	/** Persists and applies a placement offset from the controlling player's view. */
	public static SkitPlacement placeRelative(CodexAgentManager manager, String selector, ServerPlayer player,
			double right, double up, double forward) {
		SkitPlacement placement = SkitPlacement.relativeTo(player, right, up, forward);
		return place(manager, selector, (ServerLevel) player.level(), placement.x(), placement.y(), placement.z(), placement.yaw(), placement.pitch());
	}

	/** Persists and applies a placement at the player while looking at a target point. */
	public static SkitPlacement placeLookingAt(CodexAgentManager manager, String selector, ServerPlayer player, net.minecraft.world.phys.Vec3 target) {
		SkitPlacement placement = SkitPlacement.lookingAt(player, target);
		return place(manager, selector, (ServerLevel) player.level(), placement.x(), placement.y(), placement.z(), placement.yaw(), placement.pitch());
	}

	public static Optional<SkitPlacement> savedPlacement(CodexAgentManager manager, String selector) {
		AgentRecord record = manager.resolve(selector);
		return Optional.ofNullable(SkitModeSavedData.get(manager.server()).placement(record.agentId().toString()));
	}

	public static SkitScript createScript(MinecraftServer server, String name, String agentSelector) {
		requireEnabled(server);
		SkitScript script = new SkitScript(name, agentSelector, List.of());
		SkitModeSavedData.get(server).putScript(script);
		return script;
	}

	public static SkitScript addStep(MinecraftServer server, String name, SkitStep step) {
		requireEnabled(server);
		SkitModeSavedData data = SkitModeSavedData.get(server);
		SkitScript current = Optional.ofNullable(data.script(name))
				.orElseThrow(() -> new AgentDomainException("SKIT_SCRIPT_NOT_FOUND", "No skit script named " + name));
		SkitScript updated = current.append(step);
		data.putScript(updated);
		return updated;
	}

	/** Appends one action step while retaining the same explicit endpoint model as pose steps. */
	public static SkitScript addAction(MinecraftServer server, String name, SkitPlacement endpoint, SkitAction action) {
		Objects.requireNonNull(endpoint, "endpoint must not be null");
		Objects.requireNonNull(action, "action must not be null");
		return addStep(server, name, new SkitStep(0, endpoint, List.of(action)));
	}

	public static SkitScript play(CodexAgentManager manager, String name, String selectorOverride) {
		requireEnabled(manager.server());
		SkitModeSavedData data = SkitModeSavedData.get(manager.server());
		SkitScript script = Optional.ofNullable(data.script(name))
				.orElseThrow(() -> new AgentDomainException("SKIT_SCRIPT_NOT_FOUND", "No skit script named " + name));
		if (script.steps().isEmpty()) throw new AgentDomainException("SKIT_SCRIPT_EMPTY", "Skit script has no steps");
		String selector = selectorOverride == null || selectorOverride.isBlank() ? script.agentSelector() : selectorOverride;
		AgentRecord record = manager.resolve(selector);
		if (record.state() != dev.agaminggod.arenaagents.agent.AgentLifecycleState.IDLE
				|| !record.currentGoal().isEmpty() || !record.queuedGoals().isEmpty()) {
			throw new AgentDomainException("SKIT_AGENT_BUSY", "Skit playback requires an idle agent with no queued goals");
		}
		AgentId agentId = record.agentId();
		PLAYBACK.computeIfAbsent(manager.server(), ignored -> new ConcurrentHashMap<>())
				.put(agentId, Playback.waiting(script.steps(), manager.server().getTickCount()));
		return script;
	}

	/** Prevents normal goal execution from racing a server-authoritative skit timeline. */
	public static void requireNormalControlAllowed(MinecraftServer server, AgentId agentId) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		if (server == null) return;
		if (Optional.ofNullable(PLAYBACK.get(server)).map(runs -> runs.containsKey(agentId)).orElse(false)) {
			throw new AgentDomainException("SKIT_AGENT_RESERVED", "Agent is reserved by active skit playback");
		}
	}

	public static boolean deleteScript(MinecraftServer server, String name) {
		requireEnabled(server);
		SkitModeSavedData data = SkitModeSavedData.get(server);
		if (!data.removeScript(name)) {
			throw new AgentDomainException("SKIT_SCRIPT_NOT_FOUND", "No skit script named " + name);
		}
		return true;
	}

	public static void stop(MinecraftServer server, String selector) {
		if (!enabled(server)) return;
		stop(server, CodexAgentManager.get(server).resolve(selector).agentId());
	}

	static void stop(MinecraftServer server, AgentId agentId) {
		if (server == null) return;
		Optional.ofNullable(PLAYBACK.get(server)).ifPresent(runs -> runs.remove(agentId));
	}

	public static void tick(MinecraftServer server) {
		if (!enabled(server)) { PLAYBACK.remove(server); return; }
		Map<AgentId, Playback> runs = PLAYBACK.get(server);
		if (runs == null || runs.isEmpty()) return;
		long tick = server.getTickCount();
		CodexAgentManager manager = CodexAgentManager.get(server);
		for (var entry : runs.entrySet()) {
			Playback playback = entry.getValue();
			if (tick < playback.nextTick()) continue;
			if (playback.index() >= playback.steps().size()) { runs.remove(entry.getKey(), playback); continue; }
			Optional<ServerPlayer> player;
			try { player = manager.findAgentPlayer(entry.getKey()); }
			catch (RuntimeException ignored) {
				runs.remove(entry.getKey(), playback);
				continue;
			}
			if (player.isEmpty()) continue;
			ServerPlayer actor = player.orElseThrow();
			SkitStep step = playback.steps().get(playback.index());
			Playback updated = advancePlayback(server, actor, playback, step, tick);
			if (updated == null) runs.remove(entry.getKey(), playback);
			else runs.replace(entry.getKey(), playback, updated);
		}
	}

	public static void release(MinecraftServer server) {
		PLAYBACK.remove(server);
	}

	private static void teleport(ServerPlayer player, ServerLevel level, SkitPlacement placement) {
		player.teleportTo(level, placement.x(), placement.y(), placement.z(), Set.<Relative>of(), placement.yaw(), placement.pitch(), false);
		player.setYHeadRot(placement.yaw());
	}

	private static Playback advancePlayback(MinecraftServer server, ServerPlayer actor, Playback playback, SkitStep step, long tick) {
		if (!playback.started()) {
			SkitAction first = step.actions().isEmpty() ? null : step.actions().getFirst();
			if (first == null || first.type() != SkitAction.Type.MOVE) {
				findLevel(server, step.placement().dimension()).ifPresent(level -> teleport(actor, level, step.placement()));
			}
			SkitPlacement start = currentPlacement(actor);
			long end = tick + Math.max(1, first == null ? 1 : first.durationTicks());
			Playback started = playback.begin(start, end);
			if (first == null) return finishStep(server, started, tick);
			applyAction(actor, step, first, start, 0, tick, true);
			return started;
		}

		SkitAction action = step.actions().get(playback.actionIndex());
		long elapsed = tick - playback.actionStartTick();
		applyAction(actor, step, action, playback.actionOrigin(), elapsed, tick, false);
		if (tick + 1 < playback.actionEndTick()) return playback;
		stopAction(actor, action);
		int nextAction = playback.actionIndex() + 1;
		if (nextAction < step.actions().size()) {
			SkitAction next = step.actions().get(nextAction);
			long end = tick + Math.max(1, next.durationTicks());
			Playback advanced = playback.nextAction(nextAction, tick + 1, end, currentPlacement(actor));
			applyAction(actor, step, next, advanced.actionOrigin(), 0, tick, true);
			return advanced;
		}
		return finishStep(server, playback, tick);
	}

	private static Playback finishStep(MinecraftServer server, Playback playback, long tick) {
		int next = playback.index() + 1;
		if (next >= playback.steps().size()) return null;
		SkitStep nextStep = playback.steps().get(next);
		return new Playback(playback.steps(), next, tick + Math.max(0, nextStep.delayTicks()), 0, 0, 0, false, null);
	}

	private static void applyAction(ServerPlayer actor, SkitStep step, SkitAction action, SkitPlacement origin,
			long elapsed, long tick, boolean firstTick) {
		switch (action.type()) {
			case MOVE -> {
				float progress = Math.min(1.0F, (elapsed + 1.0F) / Math.max(1, action.durationTicks()));
				SkitPlacement target = step.placement();
				SkitPlacement interpolated = interpolate(origin, target, progress);
				findLevel(actor.level().getServer(), interpolated.dimension()).ifPresent(level -> teleport(actor, level, interpolated));
			}
			case WAIT -> { }
			case JUMP -> {
				if (firstTick) OfflineAgentPlayers.actions(actor).start(
						EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.continuous());
			}
			case EQUIP -> {
				if (!firstTick) return;
				Identifier id = Identifier.parse(action.itemId());
				if (!BuiltInRegistries.ITEM.containsKey(id)) throw new AgentDomainException("SKIT_ITEM_NOT_FOUND", "Unknown item: " + action.itemId());
				Item item = BuiltInRegistries.ITEM.getValue(id);
				actor.getInventory().setItem(actor.getInventory().getSelectedSlot(), new ItemStack(item.builtInRegistryHolder(), 1));
			}
			case SWING -> { if (firstTick) actor.swing(InteractionHand.MAIN_HAND); }
			case USE -> {
				if (firstTick) {
					var result = actor.gameMode.useItem(actor, actor.level(), actor.getMainHandItem(), InteractionHand.MAIN_HAND);
					if (result.consumesAction()) actor.swing(InteractionHand.MAIN_HAND);
				}
			}
			case EMOTE -> OfflineAgentPlayers.actions(actor).setSneaking(action.sneak());
		}
	}

	private static void stopAction(ServerPlayer actor, SkitAction action) {
		if (action.type() == SkitAction.Type.JUMP || action.type() == SkitAction.Type.MOVE || action.type() == SkitAction.Type.EMOTE) {
			OfflineAgentPlayers.actions(actor).stopAll();
		}
	}

	private static SkitPlacement currentPlacement(ServerPlayer player) {
		return new SkitPlacement(player.level().dimension().identifier().toString(), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
	}

	private static SkitPlacement interpolate(SkitPlacement from, SkitPlacement to, float progress) {
		float yaw = from.yaw() + (float) Math.toDegrees(Math.atan2(Math.sin(Math.toRadians(to.yaw() - from.yaw())), Math.cos(Math.toRadians(to.yaw() - from.yaw())))) * progress;
		return new SkitPlacement(to.dimension(),
				from.x() + (to.x() - from.x()) * progress,
				from.y() + (to.y() - from.y()) * progress,
				from.z() + (to.z() - from.z()) * progress,
				yaw, from.pitch() + (to.pitch() - from.pitch()) * progress);
	}

	private static Optional<ServerLevel> findLevel(MinecraftServer server, String dimension) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.dimension().identifier().toString().equals(dimension)) return Optional.of(level);
		}
		return Optional.empty();
	}

	private record Playback(List<SkitStep> steps, int index, long nextTick, int actionIndex,
			long actionStartTick, long actionEndTick, boolean started, SkitPlacement actionOrigin) {
		private Playback { steps = List.copyOf(steps); }

		private static Playback waiting(List<SkitStep> steps, long now) {
			return new Playback(steps, 0, now + steps.getFirst().delayTicks(), 0, 0, 0, false, null);
		}

		private Playback begin(SkitPlacement origin, long endTick) {
			return new Playback(steps, index, nextTick, 0, nextTick, endTick, true, origin);
		}

		private Playback nextAction(int nextIndex, long startTick, long endTick, SkitPlacement origin) {
			return new Playback(steps, index, startTick, nextIndex, startTick, endTick, true, origin);
		}
	}
}
