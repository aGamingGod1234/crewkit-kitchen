// Every model call re-reads the whole provider conversation. Past this many context tokens a provider session is
// replaced by a fresh one (same instructions and tools) that starts with this short carry-over; each event already
// restates the goal, task memory, facts and program state.
export const DEFAULT_CONTEXT_ROTATION_TOKENS = 64_000;
// Hysteresis: a fresh session whose first turns already pass the threshold (large tool results) must not thrash.
export const MIN_TURNS_BETWEEN_ROTATIONS = 3;
const CARRY_OVER_TOOL_CALLS = 10;
const CARRY_OVER_CONVERSATION = 6;

/** Remembers the little a fresh provider session needs to continue: recent tool calls, delivered chat, last program. */
export class ContextCarryOver {
	#tools = [];
	#conversation = [];
	#program = null;

	rememberTool(tool, result, error = null) {
		if (tool === null || typeof tool !== 'object') return;
		const { kind, ...rest } = tool;
		const isAction = (kind === 'action' || kind === 'start_action') && typeof tool.actionType === 'string';
		const name = isAction ? `${kind}:${tool.actionType}` : String(kind);
		const args = isAction ? tool.arguments : rest;
		const outcome = error !== null
			? `error ${typeof error?.code === 'string' ? error.code : 'TOOL_FAILED'}`
			: [result?.state, result?.reasonCode].filter((value) => typeof value === 'string').join(' ');
		this.#tools.push(`${name} ${truncate(safeJson(args), 160)} -> ${outcome || 'returned'}`);
		if (this.#tools.length > CARRY_OVER_TOOL_CALLS) this.#tools.splice(0, this.#tools.length - CARRY_OVER_TOOL_CALLS);
		if (typeof result?.programId === 'string') this.#noteProgram(result);
	}

	/** Delivered conversation and program state from a native event ("instruction\njson"). */
	noteEvent(text) {
		if (typeof text !== 'string') return;
		const separator = text.indexOf('\n');
		if (separator < 0) return;
		const lineEnd = text.indexOf('\n', separator + 1);
		let value;
		try { value = JSON.parse(text.slice(separator + 1, lineEnd < 0 ? text.length : lineEnd)); } catch { return; }
		for (const entry of Array.isArray(value?.conversation?.entries) ? value.conversation.entries : []) {
			const speaker = typeof entry?.sourceName === 'string' ? entry.sourceName : typeof entry?.sourceId === 'string' ? entry.sourceId : entry?.kind ?? 'message';
			if (typeof entry?.text !== 'string') continue;
			// Quoted, so a player's newlines cannot forge extra carry-over lines.
			this.#conversation.push(`${JSON.stringify(truncate(String(speaker), 48))}: ${JSON.stringify(truncate(entry.text, 200))}`);
		}
		if (this.#conversation.length > CARRY_OVER_CONVERSATION) this.#conversation.splice(0, this.#conversation.length - CARRY_OVER_CONVERSATION);
		if (value?.program !== null && typeof value?.program === 'object') this.#noteProgram(value.program);
	}

	text(reason) {
		return [
			`${reason}: your earlier turns are not shown. The event below restates your goal, task memory and facts; read taskPlan, programStatus, queryMemory or taskMemory for anything else.`,
			...(this.#tools.length === 0 ? [] : ['Your most recent tool calls (oldest first):', ...this.#tools.map((entry) => `- ${entry}`)]),
			...(this.#conversation.length === 0 ? [] : ['Recent conversation already delivered to you (oldest first):', ...this.#conversation.map((entry) => `- ${entry}`)]),
			...(this.#program === null ? [] : [`Last known program (verify with programStatus): ${JSON.stringify(this.#program)}`]),
		].join('\n');
	}

	#noteProgram(value) {
		if (typeof value.programId !== 'string') return;
		const decision = value.decision ?? value.status?.decision;
		this.#program = {
			programId: value.programId,
			...(typeof value.state === 'string' ? { state: value.state } : {}),
			...(typeof value.engineState === 'string' ? { engineState: value.engineState } : {}),
			...(typeof decision?.decisionId === 'string' ? { pendingDecisionId: decision.decisionId, trigger: decision.trigger } : {}),
		};
	}
}

function safeJson(value) { try { return JSON.stringify(value ?? {}); } catch { return '{}'; } }
function truncate(text, max) { return text.length <= max ? text : `${text.slice(0, max - 3)}...`; }
