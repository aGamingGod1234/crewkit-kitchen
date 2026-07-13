package dev.agaminggod.arenaagents.client.action;

import dev.agaminggod.arenaagents.client.combat.CombatController;
import dev.agaminggod.arenaagents.client.combat.CombatTarget;
import dev.agaminggod.arenaagents.client.combat.TargetSelector;
import dev.agaminggod.arenaagents.client.combat.WeaponCandidate;
import dev.agaminggod.arenaagents.client.combat.WeaponSelector;
import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class AttackAction implements RunningAction {
	private static final float MAX_YAW_DELTA = 20.0F;
	private static final float MAX_PITCH_DELTA = 20.0F;
	private static final float AIM_TOLERANCE = 3.0F;
	private static final double APPROACH_MARGIN = 0.75D;

	private final String selectorText;
	private final long timeoutMs;
	private final TargetSelector targetSelector = new TargetSelector();
	private final WeaponSelector weaponSelector = new WeaponSelector();
	private final CombatController combatController = new CombatController();
	private UUID targetId;
	private GridPosition approachPosition;
	private MoveToAction approachAction;
	private boolean weaponSelected;

	public AttackAction(String selectorText, long timeoutMs) {
		this.selectorText = Objects.requireNonNull(selectorText, "selectorText must not be null");
		if (selectorText.isBlank()) {
			throw new ActionCreationException("INVALID_TARGET_SELECTOR", "Target selector must not be blank");
		}
		if (timeoutMs <= 0L) {
			throw new ActionCreationException("INVALID_ATTACK_TIMEOUT", "Attack timeout must be positive");
		}
		this.timeoutMs = timeoutMs;
	}

	@Override
	public long timeoutMs() {
		return timeoutMs;
	}

	@Override
	public ActionUpdate tick(ActionContext context, long elapsedMs) {
		ActionContext.CombatSnapshot snapshot = context.combatSnapshot();
		Optional<CombatTarget> selected = targetId == null
				? targetSelector.select(snapshot.targets(), selectorText)
				: snapshot.targets().stream().filter(target -> target.uuid().equals(targetId)).findFirst();
		if (selected.isEmpty()) {
			stopMovement(context);
			return ActionUpdate.failed("TARGET_GONE", "Selected combat target is unavailable");
		}
		CombatTarget target = selected.orElseThrow();
		targetId = target.uuid();
		if (!target.alive()) {
			stopMovement(context);
			return ActionUpdate.succeeded("TARGET_DEFEATED", "Combat target is no longer alive");
		}

		ActionContext.LookResult look = context.lookAt(
				target.x(),
				target.eyeY(),
				target.z(),
				MAX_YAW_DELTA,
				MAX_PITCH_DELTA,
				AIM_TOLERANCE
		);
		CombatController.Phase phase = combatController.phase(
				target,
				snapshot.attackReach(),
				look.withinTolerance(),
				snapshot.attackStrength()
		);
		if (phase == CombatController.Phase.APPROACH) {
			return approach(context, target, snapshot.attackReach(), elapsedMs);
		}
		stopMovement(context);
		if (phase == CombatController.Phase.FACE) {
			return ActionUpdate.running("Facing combat target");
		}
		if (phase == CombatController.Phase.WAIT_COOLDOWN) {
			return ActionUpdate.running("Waiting for attack cooldown");
		}
		if (!weaponSelected) {
			Optional<WeaponCandidate> weapon = weaponSelector.selectBest(snapshot.hotbarItems());
			if (weapon.isPresent()) {
				ActionContext.OperationResult selection = context.selectHotbarItem(weapon.orElseThrow().itemId());
				if (!selection.successful()) {
					return ActionUpdate.failed(selection.reasonCode(), selection.message());
				}
			}
			weaponSelected = true;
		}
		ActionContext.OperationResult attack = context.attackTarget(target.uuid());
		return attack.successful()
				? ActionUpdate.running("Attack sent; tracking target")
				: ActionUpdate.failed(attack.reasonCode(), attack.message());
	}

	@Override
	public void cancel(ActionContext context) {
		stopMovement(context);
	}

	private ActionUpdate approach(ActionContext context, CombatTarget target, double reach, long elapsedMs) {
		GridPosition nextPosition = new GridPosition(
				floorCoordinate(target.x()),
				floorCoordinate(target.y()),
				floorCoordinate(target.z())
		);
		if (approachAction == null || !nextPosition.equals(approachPosition)) {
			stopMovement(context);
			approachPosition = nextPosition;
			approachAction = new MoveToAction(
					target.x(),
					target.y(),
					target.z(),
					Math.max(0.5D, reach - APPROACH_MARGIN),
					true
			);
		}
		ActionUpdate update = approachAction.tick(context, elapsedMs);
		if (update.state() == dev.agaminggod.arenaagents.protocol.ActionState.FAILED) {
			return ActionUpdate.failed("TARGET_UNREACHABLE", update.message());
		}
		return ActionUpdate.running("Approaching combat target");
	}

	private void stopMovement(ActionContext context) {
		context.setMovement(ActionContext.MovementInput.stopped());
		approachAction = null;
		approachPosition = null;
	}

	private static int floorCoordinate(double value) {
		double floored = Math.floor(value);
		if (floored < Integer.MIN_VALUE || floored > Integer.MAX_VALUE) {
			throw new ActionCreationException("TARGET_OUT_OF_RANGE", "Combat target is outside the local grid");
		}
		return (int) floored;
	}
}
