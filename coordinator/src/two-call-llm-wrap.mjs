/**
 * Compose recovery + frontier + failure routing for the two-call native LLM.
 * Persistent stores live in recovery-progress-wrap.mjs and explore-frontier.mjs.
 * This module only shapes what observe/say/mine/moveTo/exploreFrontier already see.
 * It never dispatches a body action.
 */

import { cueClassFor, ExplorationOccupancy, extractDimension } from './explore-frontier.mjs';
import { RecoveryProgressStore, doNotRedoFor } from './recovery-progress-wrap.mjs';

const EXPLORE_FAILURE_CODES = new Set([
	'PATH_BLOCKED',
	'DESTINATION_BLOCKED',
	'TARGET_NOT_LOADED',
	'NO_PATH',
	'NO_STANDABLE_PATH',
	'PATH_LIMIT_REACHED',
	'NO_FRONTIER',
]);

const REPLAN_FAILURE_CODES = new Set([
	'TARGET_NOT_VISIBLE',
	'TARGET_OUT_OF_RANGE',
	'TARGET_TOO_FAR',
	'TARGET_CHANGED',
	'ACTION_TIMEOUT',
	'STALE_REVISION',
	'EXPECTED_BLOCK_MISMATCH',
	'NO_OBSERVATION',
]);

const SUCCESS_REASON_CODES = new Set([
	'DONE',
	'BLOCK_BROKEN',
	'DESTINATION_REACHED',
	'ACTION_COMPLETED',
	'CONTROL_SEGMENT_COMPLETED',
	'TARGET_ALREADY_SATISFIED',
	'BLOCK_PLACED',
	'ITEM_PICKED_UP',
	'CUE_IN_VIEW',
	'COMPLETION_VERIFIED',
]);

const RECOVER_FAILURE_CODES = new Set([
	'PLAYER_DEAD',
	'PLAYER_UNAVAILABLE',
	'AGENT_DEAD',
]);

const SKIP_FAILURE_CODES = new Set([
	'RECIPE_NOT_FOUND',
	'UNSUPPORTED_ACTION',
	'SIMULATOR_UNSUPPORTED_ACTION',
]);

const EXPLORATION_GOAL = /nether|blaze|ender.?pearl|stronghold|dragon|beat.{0,24}(game|minecraft)|end portal|\bportal\b|fortress|bastion/i;

export { doNotRedoFor };

/** Unit facade over recovery + occupancy. Production uses NativeToolRuntime's stores. */
export class TwoCallLlmWrap {
	#recovery = new RecoveryProgressStore();
	#occupancy = new ExplorationOccupancy();
	#goals = new Map();
	#lastObservation = new Map();

	ingest(agentId, observation = {}, { goal = null, goalRevision = 0 } = {}) {
		if (typeof goal === 'string' && goal.length > 0) this.#goals.set(agentId, goal);
		this.#lastObservation.set(agentId, observation);
		this.#recovery.remember(agentId, goalRevision, observation);
		this.#occupancy.ingest(agentId, observation);
		return this.#recovery.snapshot(agentId, observation);
	}

	decorate(agentId, observation = {}, { goal = null } = {}) {
		const resolvedGoal = typeof goal === 'string' && goal.length > 0 ? goal : this.#goals.get(agentId) ?? null;
		if (resolvedGoal !== null) this.#goals.set(agentId, resolvedGoal);
		const snapshot = this.#recovery.snapshot(agentId, observation);
		return composeTwoCallView(observation, snapshot, {
			occupancy: this.#occupancy,
			agentId,
			goal: resolvedGoal,
		});
	}

	ingestAndDecorate(agentId, observation = {}, options = {}) {
		this.ingest(agentId, observation, options);
		return this.decorate(agentId, observation, options);
	}
}

export function wrapTwoCallObservation(observation = {}, recoveryState = {}, { goal = null } = {}) {
	const recovery = recoveryFromState(observation, recoveryState);
	return composeTwoCallView(observation, recovery, {
		occupancy: null,
		agentId: 'wrap',
		goal,
	});
}

export function classifyBodyFailure(reasonCode, state = undefined) {
	const code = typeof reasonCode === 'string' ? reasonCode : '';
	if (code.length === 0) return null;
	const normalizedState = typeof state === 'string' ? state : '';
	if (normalizedState === 'SUCCEEDED' || normalizedState === 'COMPLETED') return null;
	if (SUCCESS_REASON_CODES.has(code)) return null;
	if (RECOVER_FAILURE_CODES.has(code)) return 'recover';
	if (EXPLORE_FAILURE_CODES.has(code)) return 'explore';
	if (SKIP_FAILURE_CODES.has(code)) return 'skip';
	if (REPLAN_FAILURE_CODES.has(code) || code.length > 0) return 'replan';
	return null;
}

export function inferFrontierSeek(goal) {
	const text = typeof goal === 'string' ? goal : '';
	if (/stronghold|end.?portal|portal\s+to\s+(?:the\s+)?end/i.test(text)) return 'structure';
	if (/nether|blaze|fortress|bastion|nether.?portal|ruined.?portal/i.test(text)) return 'nether';
	if (/village|villager|trade/i.test(text)) return 'village';
	if (/structure|temple|monument/i.test(text)) return 'structure';
	if (/\bportal\b/i.test(text)) return 'nether';
	if (/cave|diamond|iron ore|deepslate/i.test(text)) return 'cave';
	return 'any';
}

export function composeTwoCallView(observation = {}, recovery = null, {
	occupancy = null,
	agentId = null,
	goal = null,
} = {}) {
	const failureClass = inferFailureClass(observation);
	const normalized = normalizeRecovery(observation, recovery);
	const options = [];
	const lost = normalized?.lastLostInventory ?? [];
	const lastDeath = normalized?.lastDeath ?? observation.death ?? null;
	if (canRecoverCorpse(observation, lastDeath, lost)) {
		options.push({
			id: 'recover_corpse',
			feasible: true,
			reason: 'Last death dropped stacks at these coordinates. Go back or recraft from current inventory.',
			moveTo: {
				x: lastDeath.x,
				y: Number.isFinite(lastDeath.y) ? lastDeath.y : 64,
				z: lastDeath.z,
				tolerance: 2,
				sprint: true,
				timeoutMs: 30_000,
			},
		});
	}
	const seek = inferFrontierSeek(goal);
	if (shouldExplore(goal, failureClass, observation.lastResult)) {
		const explore = occupancyFrontier(occupancy, agentId, observation, seek);
		if (explore !== null) {
			options.push(explore);
		} else if (occupancy === null && observation.lastResult?.reasonCode !== 'NO_FRONTIER') {
			options.push({
				id: 'explore_frontier',
				feasible: true,
				reason: 'Needed biome or structure is not in view. Call exploreFrontier for one bounded hop.',
				seek,
			});
		}
	}
	const facts = recoveryFacts({ recovery: normalized, lastDeath, observation });
	const hasStandaloneFacts = normalized === null
		&& facts !== 'Continue the active goal from current inventory facts.';
	const recoveryView = normalized === null
		? (hasStandaloneFacts ? { alreadyHave: [], doNotRedo: [], facts } : null)
		: { ...normalized, facts };
	return {
		...observation,
		...(recoveryView === null ? {} : { recovery: recoveryView }),
		...(options.length === 0 ? {} : { options }),
		...(failureClass === null ? {} : { failureClass }),
	};
}

function occupancyFrontier(occupancy, agentId, observation, seek) {
	if (occupancy === null || typeof occupancy.select !== 'function' || agentId === null) return null;
	const selection = occupancy.select(agentId, observation, { seek });
	if (selection?.kind === 'cue_in_view') {
		return {
			id: 'interact_cue',
			feasible: true,
			reason: selection.reason,
			seek: selection.seek,
			cue: selection.cue,
		};
	}
	if (selection?.kind !== 'frontier' && selection?.kind !== 'cue') return null;
	if (selection.destination === null || !Number.isFinite(selection.destination.x)) return null;
	return {
		id: 'explore_frontier',
		feasible: true,
		reason: selection.reason,
		seek: selection.seek,
		moveTo: {
			x: selection.destination.x,
			y: selection.destination.y,
			z: selection.destination.z,
			tolerance: 1,
			sprint: true,
			timeoutMs: 15_000,
		},
	};
}

function inferFailureClass(observation) {
	const classified = classifyBodyFailure(observation.lastResult?.reasonCode, observation.lastResult?.state);
	if (classified !== null) return classified;
	if (observation.continuity?.phase === 'dead' || observation.player?.dead === true || observation.death != null) {
		return 'recover';
	}
	return null;
}

function canRecoverCorpse(observation, lastDeath, lost) {
	if (lastDeath === null || !Number.isFinite(lastDeath.x) || lost.length === 0) return false;
	if (!observationHasDimension(observation)) return true;
	return sameDimension(extractDimension(observation), lastDeath.dimensionId ?? lastDeath.dimension ?? 'minecraft:overworld');
}

function sameDimension(left, right) {
	return normalizeDimension(left) === normalizeDimension(right);
}

function normalizeDimension(value) {
	if (typeof value !== 'string' || value.length === 0) return 'minecraft:overworld';
	return value.includes(':') ? value : `minecraft:${value}`;
}

function observationHasDimension(observation) {
	return typeof observation?.world?.dimension === 'string' && observation.world.dimension.length > 0
		|| typeof observation?.world?.dimensionId === 'string' && observation.world.dimensionId.length > 0;
}

function shouldExplore(goal, failureClass, lastResult) {
	if (lastResult?.reasonCode === 'NO_FRONTIER') return false;
	if (failureClass === 'explore') return true;
	return EXPLORATION_GOAL.test(String(goal ?? ''));
}

function recoveryFacts({ recovery, lastDeath, observation }) {
	const parts = [];
	if (lastDeath !== null) {
		parts.push(`Last death: ${lastDeath.cause ?? 'unknown'} at x=${lastDeath.x},y=${lastDeath.y},z=${lastDeath.z}.`);
	}
	const held = inventoryItemIds(observation ?? {});
	if (lastDeath !== null) {
		parts.push(held.length === 0 ? 'Current inventory is empty.' : `Current inventory: ${unique(held).join(', ')}.`);
	}
	const have = itemIds(recovery);
	if (have.length > 0) parts.push(`Currently evidenced: ${unique(have).join(', ')}.`);
	const lost = Array.isArray(recovery?.lastLostInventory)
		? recovery.lastLostInventory.map((entry) => entry?.itemId).filter(Boolean)
		: [];
	if (lost.length > 0) parts.push(`Lost on death: ${unique(lost).join(', ')}.`);
	const skip = Array.isArray(recovery?.doNotRedo)
		? recovery.doNotRedo.map((entry) => typeof entry === 'string' ? entry : entry?.recipeId).filter(Boolean)
		: [];
	if (skip.length > 0) parts.push(`Already evidenced outputs: ${unique(skip).join(', ')}.`);
	const cues = visibleCueIds(observation);
	if (cues.length > 0) parts.push(`Visible cue: ${cues.join(', ')}.`);
	return parts.join(' ') || 'Continue the active goal from current inventory facts.';
}

function visibleCueIds(observation) {
	const blocks = [
		...(Array.isArray(observation?.blocks) ? observation.blocks : []),
		...(Array.isArray(observation?.nearby?.blocks) ? observation.nearby.blocks : []),
	];
	return unique(blocks
		.map((block) => block?.blockId)
		.filter((blockId) => typeof blockId === 'string' && cueClassFor(blockId) !== null));
}

function normalizeRecovery(observation, recovery) {
	if (recovery === null && observation?.recovery === undefined) {
		return recoveryFromState(observation, {});
	}
	const source = recovery ?? observation.recovery ?? null;
	if (source === null) return null;
	if (Array.isArray(source.alreadyHave) && source.alreadyHave.some((entry) => entry !== null && typeof entry === 'object')) {
		return {
			...source,
			lastDeath: source.lastDeath ?? observation.death ?? null,
		};
	}
	const alreadyHave = itemIds(source);
	const lastDeath = source.lastDeath ?? observation.death ?? null;
	const lastLostInventory = Array.isArray(source.lastLostInventory) ? source.lastLostInventory : [];
	const doNotRedo = Array.isArray(source.doNotRedo) && source.doNotRedo.length > 0
		? source.doNotRedo
		: doNotRedoFor(alreadyHave);
	if (lastDeath === null && alreadyHave.length === 0 && lastLostInventory.length === 0 && !(Array.isArray(doNotRedo) && doNotRedo.length > 0)) {
		return null;
	}
	return {
		...source,
		...(lastDeath === null ? {} : { lastDeath }),
		alreadyHave,
		doNotRedo,
		...(lastLostInventory.length === 0 ? {} : { lastLostInventory }),
	};
}

function recoveryFromState(observation, recoveryState = {}) {
	const inventoryIds = inventoryItemIds(observation);
	const alreadyHave = unique([
		...inventoryIds,
		...(Array.isArray(recoveryState.alreadyHave) ? itemIds({ alreadyHave: recoveryState.alreadyHave }) : []),
	]);
	const lastDeath = recoveryState.lastDeath ?? observation.death ?? null;
	const lastLostInventory = Array.isArray(recoveryState.lastLostInventory) ? recoveryState.lastLostInventory : [];
	const doNotRedo = doNotRedoFor(alreadyHave);
	if (lastDeath === null && alreadyHave.length === 0 && lastLostInventory.length === 0) return null;
	return {
		...(lastDeath === null ? {} : { lastDeath }),
		alreadyHave,
		doNotRedo,
		...(lastLostInventory.length === 0 ? {} : { lastLostInventory }),
	};
}

function itemIds(recovery) {
	const have = recovery?.alreadyHave ?? [];
	if (have.length === 0) return [];
	if (typeof have[0] === 'string') return have;
	return have.map((entry) => entry?.itemId ?? entry?.blockId).filter((value) => typeof value === 'string');
}

function inventoryItemIds(observation) {
	const raw = observation?.inventory;
	const items = Array.isArray(raw) ? raw : Array.isArray(raw?.items) ? raw.items : [];
	return items.map((item) => item?.itemId).filter((value) => typeof value === 'string' && value !== 'minecraft:air');
}

function unique(values) {
	return [...new Set(values.filter(Boolean))];
}
