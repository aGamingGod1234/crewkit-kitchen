// Coalesces repeated damage/threat steering into one in-flight native turn.
//
// Every hit used to steer the running provider turn with a full event payload (5-8k tokens each). Steering does
// not restart the turn (Codex turn/steer keeps the turn id; Claude appends the steer to the next tool result),
// but each one grows the context the model must read before it can act: in the live trace 13 per-hit steers
// grew the turn from 79k to 191k input tokens in 15 seconds while the model kept re-deciding. Here the first
// danger steer goes out at once; further hits fold into one summary delivered at most every interval, or at
// once when something materially new happens (a new trigger kind, a new threat type or a swelling creeper,
// or health crossing a low threshold). The model still receives every fact; it just gets time to decide.

export const DANGER_STEER_INTERVAL_MS = 2_000;
const DANGER_STEER_TRIGGERS = new Set(['damage', 'threat']);
// 70% (when eating while safe becomes worthwhile), half health and three hearts: crossing any changes what a
// sensible choice is. 14 is deliberate: waiting to be told at 5 hearts taught agents to heal far too late.
const LOW_HEALTH_THRESHOLDS = Object.freeze([14, 10, 6]);
const MAX_SUMMARY_ATTACKERS = 4;

/** The danger facts of one steer request, or null when the request is not a damage/threat steer. */
export function dangerSteerFacts(request) {
	const event = request?.nativeEvent;
	const trigger = event?.event === 'program_attention' ? event.status?.decision?.trigger ?? request?.trigger : request?.trigger;
	if (!DANGER_STEER_TRIGGERS.has(trigger)) return null;
	const observation = event?.observation ?? request?.observation ?? {};
	const player = observation?.player ?? {};
	const threats = Array.isArray(player.threats) ? player.threats : Array.isArray(observation?.threats?.entries) ? observation.threats.entries : [];
	const threatKeys = new Set();
	for (const threat of threats) {
		if (typeof threat?.type !== 'string') continue;
		threatKeys.add(threat.type);
		if (threat.swelling === true) threatKeys.add(`${threat.type}#swelling`);
	}
	return {
		trigger,
		health: Number.isFinite(player.health) ? player.health : null,
		attacker: typeof player.lastAttacker?.type === 'string' ? player.lastAttacker.type : null,
		threatKeys,
	};
}

/** Per-turn coalescing state. Pure apart from the caller-supplied clock values. */
export class DangerSteerCoalescer {
	#intervalMs; #lastDeliveredAt = null; #deliveredTriggers = new Set(); #deliveredThreatKeys = new Set(); #deliveredHealth = null; #folded = null;

	constructor({ intervalMs = DANGER_STEER_INTERVAL_MS } = {}) {
		if (!Number.isFinite(intervalMs) || intervalMs < 0) throw new TypeError('intervalMs must be a nonnegative number');
		this.#intervalMs = intervalMs;
	}

	/** Records the request that started the turn, so its first repeat is not treated as new. */
	noteDelivered(request, nowMs) {
		const facts = dangerSteerFacts(request);
		if (facts !== null) this.#remember(facts, nowMs);
	}

	/**
	 * Returns { action: 'deliver', request } to steer now (with any folded summary attached), or
	 * { action: 'fold', dueInMs, folded } when the request was folded into the pending summary.
	 */
	offer(request, nowMs) {
		const facts = dangerSteerFacts(request);
		// Other urgent steers (chat, death, lava...) are never delayed; they carry any folded hits along.
		if (facts === null) return { action: 'deliver', request: this.#attachFolded(request) };
		// Without a usable clock reading nothing can be timed, so nothing is held back.
		const elapsed = this.#lastDeliveredAt === null || !Number.isFinite(nowMs) ? Number.POSITIVE_INFINITY : nowMs - this.#lastDeliveredAt;
		if (elapsed >= this.#intervalMs || this.#materiallyNew(facts)) {
			const delivered = this.#attachFolded(request);
			this.#remember(facts, nowMs);
			return { action: 'deliver', request: delivered };
		}
		this.#fold(request, facts);
		return { action: 'fold', dueInMs: Math.max(0, this.#intervalMs - elapsed), folded: this.#folded.events };
	}

	hasFolded() { return this.#folded !== null; }

	/** Takes the folded request with its summary (timer flush or turn end), or null. */
	flush(nowMs) {
		if (this.#folded === null) return null;
		const facts = dangerSteerFacts(this.#folded.request);
		const request = this.#attachFolded(this.#folded.request);
		if (facts !== null) this.#remember(facts, nowMs);
		return request;
	}

	#materiallyNew(facts) {
		if (!this.#deliveredTriggers.has(facts.trigger)) return true;
		for (const key of facts.threatKeys) if (!this.#deliveredThreatKeys.has(key)) return true;
		if (facts.health !== null && this.#deliveredHealth !== null
			&& LOW_HEALTH_THRESHOLDS.some((threshold) => this.#deliveredHealth > threshold && facts.health <= threshold)) return true;
		return false;
	}

	#remember(facts, nowMs) {
		this.#lastDeliveredAt = nowMs;
		this.#deliveredTriggers.add(facts.trigger);
		for (const key of facts.threatKeys) this.#deliveredThreatKeys.add(key);
		if (facts.health !== null) this.#deliveredHealth = facts.health;
	}

	#fold(request, facts) {
		const folded = this.#folded ?? { request, events: 0, hits: 0, healthBefore: this.#deliveredHealth, attackers: new Set() };
		folded.request = request;
		folded.events += 1;
		if (facts.trigger === 'damage') folded.hits += 1;
		if (facts.attacker !== null && folded.attackers.size < MAX_SUMMARY_ATTACKERS) folded.attackers.add(facts.attacker);
		folded.healthNow = facts.health;
		this.#folded = folded;
	}

	#attachFolded(request) {
		const folded = this.#folded;
		this.#folded = null;
		if (folded === null) return request;
		const facts = dangerSteerFacts(request);
		const dangerSummary = {
			foldedEvents: folded.events,
			hitsSinceLastUpdate: folded.hits + (facts?.trigger === 'damage' && request !== folded.request ? 1 : 0),
			healthAtLastUpdate: folded.healthBefore,
			healthNow: facts?.health ?? folded.healthNow ?? null,
			attackers: [...folded.attackers, ...(facts?.attacker && !folded.attackers.has(facts.attacker) ? [facts.attacker] : [])].slice(0, MAX_SUMMARY_ATTACKERS),
		};
		return { ...request, dangerSummary };
	}
}
