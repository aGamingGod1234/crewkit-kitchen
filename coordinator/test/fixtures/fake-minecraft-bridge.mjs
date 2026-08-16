import { validateProtocolV2Payload } from '../../src/protocol-v2.mjs';

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
	#pending = new Map();

	sent = [];
	progress = [];
	results = [];

	constructor({ record, initialObservation = observation(), onAction = () => ({}), onCancel = () => ({}) } = {}) {
		this.#record = record;
		this.#onAction = onAction;
		this.#onCancel = onCancel;
		this.#observation = initialObservation;
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
		if (type === 'action_command') {
			const normalized = validateProtocolV2Payload(type, payload);
			this.sent.push({ type, agentId, payload: normalized });
			queueMicrotask(() => { void this.#execute(normalized); });
			return;
		}
		if (type === 'action_cancel') {
			const normalized = validateProtocolV2Payload(type, payload);
			this.sent.push({ type, agentId, payload: normalized });
			queueMicrotask(() => { void this.#cancel(normalized.actionId); });
			return;
		}
		this.sent.push({ type, agentId, payload });
	}

	async publish(nextObservation, { attention = false, eventSequence = this.#nextSequence(), observedAtEpochMs = this.#clock } = {}) {
		this.#observation = nextObservation;
		this.#eventSequence = Math.max(this.#eventSequence, eventSequence);
		if (!this.#manager) throw new Error('FakeMinecraftBridge is not attached to a manager');
		return this.#manager.onObservation(this.#record, {
			observation: nextObservation,
			eventSequence,
			attention,
			observedAtEpochMs,
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
			await this.#manager.onActionProgress(this.#record, {
				goalRevision: command.goalRevision,
				actionId: command.actionId,
				eventSequence: progressSequence,
			});
		}
		if (plan.observation !== undefined) this.#observation = plan.observation;
		const observationSequence = this.#nextSequence();
		this.#eventSequence = observationSequence;
		if (this.#manager) {
			await this.#manager.onObservation(this.#record, {
				observation: this.#observation,
				eventSequence: observationSequence,
				attention: false,
				observedAtEpochMs: this.#clock,
				receiptMonotonicMs: ++this.#clock,
				receiptEpochMs: this.#clock,
			});
		}
		const result = {
			goalRevision: command.goalRevision,
			actionId: command.actionId,
			state: plan.state ?? 'SUCCEEDED',
			reasonCode: plan.reasonCode ?? 'DONE',
		};
		this.results.push(result);
		if (this.#manager) await this.#manager.onActionResult(this.#record, result);
	}

	#nextSequence() {
		this.#eventSequence += 1;
		return this.#eventSequence;
	}
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
