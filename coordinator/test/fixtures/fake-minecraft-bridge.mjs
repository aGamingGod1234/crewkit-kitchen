import { createProtocolV2Envelope, validateProtocolV2Envelope, validateProtocolV2Payload } from '../../src/protocol-v2.mjs';
import { adaptObservation } from '../../src/observation-adapter.mjs';

export const SELECTED_PROFILE = Object.freeze({
	agentId: 'task10-agent',
	provider: 'codex',
	model: 'gpt-5.6-sol',
	reasoningEffort: 'high',
	serviceTier: 'fast',
});

export function observation(overrides = {}) {
	return {
		player: { x: 0, y: 64, z: 0, health: 20, dead: false, ...overrides.player },
		items: overrides.items ?? [],
		entities: overrides.entities ?? [],
		blocks: overrides.blocks ?? [],
		inventory: { items: [], tagCounts: { '#minecraft:logs': 0 }, ...overrides.inventory },
	};
}

/** Deterministic loopback bridge that executes only commands accepted by protocol v2. */
export class FakeMinecraftBridge {
	#record;
	#manager;
	#onAction;
	#onCancel;
	#observation;
	#eventSequence;
	#clock;
	#recorder;
	#pending = new Map();
	#messageSequence = 0;
	#serverInstanceId = 'task10-fake-server';

	sent = [];
	progress = [];
	results = [];
	validatedInbound = 0;
	validatedOutbound = 0;

	constructor({ record, initialObservation = observation(), onAction = () => ({}), onCancel = () => ({}), recorder = null, benchmarkRecorder = null } = {}) {
		this.#record = record;
		this.#onAction = onAction;
		this.#onCancel = onCancel;
		this.#recorder = recorder ?? benchmarkRecorder;
		if (this.#recorder !== null && typeof this.#recorder.record !== 'function') throw new TypeError('recorder.record must be a function');
		this.#observation = adaptObservation(validateProtocolV2Payload('observation', toWireObservation(initialObservation, record.goalRevision, 1, false, 1)));
		this.#eventSequence = 1;
		this.#clock = 1;
	}

	attach(manager) {
		this.#manager = manager;
	}

	get currentObservation() {
		return this.#observation;
	}

	get eventSequence() {
		return this.#eventSequence;
	}

	async send(type, agentId, payload) {
		if (agentId !== this.#record.agentId) throw new Error(`unexpected agent ${agentId}`);
		const envelope = createProtocolV2Envelope({
			serverInstanceId: this.#serverInstanceId,
			agentId,
			type,
			messageId: `out-${++this.#messageSequence}`,
			payload,
		});
		validateProtocolV2Envelope(envelope, { direction: 'coordinator_to_server' });
		this.validatedOutbound += 1;
		const normalized = envelope.payload;
		this.#recordBenchmark('bridge_command_accepted', { type, actionType: normalized.actionType ?? null, actionId: normalized.actionId ?? null });
		if (type === 'action_command') {
			this.sent.push(envelope);
			queueMicrotask(() => { void this.#execute(normalized); });
			return;
		}
		if (type === 'action_cancel') {
			this.sent.push(envelope);
			queueMicrotask(() => { void this.#cancel(normalized.actionId); });
			return;
		}
		this.sent.push(envelope);
	}

	async publish(nextObservation, { attention = false, eventSequence = this.#nextSequence(), observedAtEpochMs = this.#clock } = {}) {
		const inbound = createProtocolV2Envelope({
			serverInstanceId: this.#serverInstanceId,
			agentId: this.#record.agentId,
			type: 'observation',
			messageId: `in-${++this.#messageSequence}`,
			payload: toWireObservation(nextObservation, this.#record.goalRevision, eventSequence, attention, observedAtEpochMs),
		});
		const normalized = validateProtocolV2Envelope(inbound, { direction: 'server_to_coordinator' });
		this.validatedInbound += 1;
		this.#observation = adaptObservation(normalized.payload);
		this.#recordBenchmark('observation_published', { eventSequence: normalized.payload.eventSequence, attention: normalized.payload.attention === true });
		this.#eventSequence = Math.max(this.#eventSequence, eventSequence);
		if (!this.#manager) throw new Error('FakeMinecraftBridge is not attached to a manager');
		return this.#manager.onObservation(this.#record, {
			observation: this.#observation,
			eventSequence: normalized.payload.eventSequence,
			attention: normalized.payload.attention,
			observedAtEpochMs: normalized.payload.observedAtEpochMs,
			receiptMonotonicMs: ++this.#clock,
			receiptEpochMs: observedAtEpochMs + 1,
		});
	}

	async #execute(command) {
		const plan = await this.#onAction(command, this);
		if (plan?.defer === true) this.#pending.set(command.actionId, command);
		if (plan?.attentionObservation !== undefined) {
			await this.publish(plan.attentionObservation, { attention: true, eventSequence: this.#nextSequence() });
		}
		if (plan?.defer === true) {
			return;
		}
		await this.#finish(command, plan);
	}

	async #cancel(actionId) {
		const command = this.#pending.get(actionId) ?? this.sent.find((entry) => entry.type === 'action_command' && entry.payload.actionId === actionId)?.payload;
		if (!command) return;
		this.#pending.delete(actionId);
		const plan = await this.#onCancel(command, this);
		await this.#finish(command, { state: 'CANCELLED', reasonCode: plan?.reasonCode ?? 'CANCELLED', observation: plan?.observation ?? this.#observation });
	}

	async #finish(command, plan = {}) {
		const progressSequence = this.#nextSequence();
		this.progress.push({ actionId: command.actionId, eventSequence: progressSequence });
		if (this.#manager) {
			const inbound = createProtocolV2Envelope({ serverInstanceId: this.#serverInstanceId, agentId: this.#record.agentId, type: 'action_progress', messageId: `in-${++this.#messageSequence}`, payload: {
				traceId: command.traceId,
				goalRevision: command.goalRevision,
				actionId: command.actionId,
				commandId: command.actionId,
				state: 'RUNNING',
				message: 'progress',
				progress: 0.5,
				elapsedMs: 1,
				observedAtEpochMs: this.#clock,
			} });
			const normalized = validateProtocolV2Envelope(inbound, { direction: 'server_to_coordinator' });
			this.validatedInbound += 1;
			await this.#manager.onActionProgress(this.#record, normalized.payload);
		}
		if (plan.observation !== undefined) this.#observation = plan.observation;
		const observationSequence = this.#nextSequence();
		await this.publish(this.#observation, { eventSequence: observationSequence, observedAtEpochMs: this.#clock });
		const result = {
			goalRevision: command.goalRevision,
			actionId: command.actionId,
			state: plan.state ?? 'SUCCEEDED',
			reasonCode: plan.reasonCode ?? 'DONE',
		};
		this.results.push(result);
		this.#recordBenchmark('bridge_action_completed', { actionId: result.actionId, actionType: command.actionType, eventSequence: observationSequence, state: result.state, reasonCode: result.reasonCode });
		if (this.#manager) {
			const inbound = createProtocolV2Envelope({ serverInstanceId: this.#serverInstanceId, agentId: this.#record.agentId, type: 'action_result', messageId: `in-${++this.#messageSequence}`, payload: {
				...result,
				traceId: command.traceId,
				commandId: result.actionId,
				actionType: command.actionType,
				message: result.reasonCode,
				executionStarted: true,
				physicalAttempted: true,
				elapsedMs: 1,
				observedAtEpochMs: this.#clock,
			} });
			const normalized = validateProtocolV2Envelope(inbound, { direction: 'server_to_coordinator' });
			this.validatedInbound += 1;
			await this.#manager.onActionResult(this.#record, normalized.payload);
		}
	}

	#nextSequence() {
		this.#eventSequence += 1;
		return this.#eventSequence;
	}

	#recordBenchmark(stage, fields) {
		if (this.#recorder === null) return;
		try { this.#recorder.record(stage, { agentId: this.#record.agentId, goalRevision: this.#record.goalRevision }, fields); }
		catch { /* benchmark telemetry cannot affect fixture execution */ }
	}
}

function toWireObservation(value, goalRevision, eventSequence, attention, observedAtEpochMs) {
	const player = value.player ?? {};
	if (player.dead === true) return { goalRevision, observedAtEpochMs, ready: false, status: 'PLAYER_DEAD', eventSequence, attention: false, changedFacts: [] };
	const position = { x: player.x ?? 0, y: player.y ?? 64, z: player.z ?? 0 };
	return {
		goalRevision,
		observedAtEpochMs,
		ready: player.dead !== true,
		status: player.dead === true ? 'PLAYER_DEAD' : 'ready',
		eventSequence,
		attention,
		changedFacts: attention ? ['player.health'] : [],
		position,
		velocity: { x: 0, y: 0, z: 0 },
		view: { yaw: 0, pitch: 0 },
		player: {
			health: player.health ?? 20, maxHealth: 20, armor: 0, foodLevel: 20, saturation: 5,
			gameMode: 'survival', onGround: true, inWater: false, onFire: player.fire === true,
			air: 300, maxAir: 300, suffocating: false, fallDistance: player.fallDistance ?? 0, effects: [],
		},
		inventory: { items: (value.inventory?.items ?? []).map((item, index) => ({ itemId: item.itemId, count: item.count, damage: 0, maxDamage: 0, slot: item.slot ?? index, ...(item.tags ? { tags: item.tags } : {}) })), selectedItem: 'minecraft:air', ...(value.inventory?.tagCounts ? { tagCounts: value.inventory.tagCounts } : {}) },
		entities: (value.items ?? []).map((item) => ({ uuid: item.stableId, type: 'minecraft:item', name: 'drop', distance: Math.hypot(item.x - position.x, item.y - position.y, item.z - position.z), position: { x: item.x, y: item.y, z: item.z }, itemId: item.itemId, count: item.count, ...(item.tags ? { tags: item.tags } : {}) })),
		blocks: (value.blocks ?? []).map((block) => ({ x: block.x, y: block.y, z: block.z, blockId: block.blockId, placeableFaces: ['up', 'down', 'north', 'south', 'east', 'west'], ...(block.tags ? { tags: block.tags } : {}) })),
		nearbyContainers: [], world: { dimension: 'minecraft:overworld', gameTime: 1, dayTime: 1, raining: false, thundering: false },
		currentAction: { active: false }, lastResult: { present: false },
	};
}

export function commandPayloads(bridge) {
	return bridge.sent.filter((entry) => entry.type === 'action_command').map((entry) => entry.payload);
}

export function assertCommandProvenance(commands, profile = SELECTED_PROFILE, expectedProgramId = null) {
	for (const command of commands) {
		const payload = command.payload ?? command;
		const provenance = payload.provenance;
		if (provenance === null || typeof provenance !== 'object') throw new Error('command is missing model-program provenance');
		if (provenance.model !== profile.model) throw new Error(`command used ${provenance.model}, expected ${profile.model}`);
		if (expectedProgramId !== null && provenance.programId !== expectedProgramId) throw new Error(`command used ${provenance.programId}, expected ${expectedProgramId}`);
		if (!/^program-\d+-\d+$/.test(provenance.programId)) throw new Error(`invalid program id ${provenance.programId}`);
		if (!/^step-/.test(provenance.sourceStepId)) throw new Error(`invalid source step ${provenance.sourceStepId}`);
		if (!Number.isSafeInteger(provenance.eventSequence)) throw new Error('command event sequence is not a safe integer');
	}
}
