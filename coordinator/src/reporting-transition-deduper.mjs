/** Keeps only the last published transition per agent. */
export class ReportingTransitionDeduper {
	#maximumAgents;
	#last = new Map();

	constructor({ maximumAgents = 16 } = {}) {
		if (!Number.isSafeInteger(maximumAgents) || maximumAgents < 1 || maximumAgents > 16) throw new TypeError('maximumAgents must be in [1, 16]');
		this.#maximumAgents = maximumAgents;
	}

	get size() { return this.#last.size; }

	accept({ agentId, goalRevision, component, boundary, code, state, detail = null }) {
		const identity = JSON.stringify([goalRevision, component, boundary, code, state, detail]);
		if (this.#last.get(agentId) === identity) return false;
		this.#last.delete(agentId);
		this.#last.set(agentId, identity);
		while (this.#last.size > this.#maximumAgents) this.#last.delete(this.#last.keys().next().value);
		return true;
	}

	clear(agentId) { this.#last.delete(agentId); }
	clearAll() { this.#last.clear(); }
}

