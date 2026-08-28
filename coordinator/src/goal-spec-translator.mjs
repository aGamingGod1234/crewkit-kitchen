import {
	GOAL_PREDICATE_SCHEMA,
	GOAL_SPEC_PROPOSAL_SCHEMA,
	GoalSpecError,
	goalPredicateIdentifiers,
	parseGoalSpecProposal,
	parseGoalSpecRequest,
} from './goal-spec.mjs';

export class GoalSpecTranslator {
	#generate;

	constructor({ generate }) {
		if (typeof generate !== 'function') throw new TypeError('generate must be a function');
		this.#generate = generate;
	}

	async translate(requestValue, { signal } = {}) {
		const request = parseGoalSpecRequest(requestValue);
		if (signal?.aborted) throw signal.reason ?? codedError('GOAL_SPEC_CANCELLED', 'Goal translation was cancelled');
		const output = await this.#generate({
			request,
			prompt: buildGoalSpecTranslatorPrompt(request),
			schema: GOAL_SPEC_PROPOSAL_SCHEMA,
			signal,
		});
		let value = output;
		if (typeof output === 'string') {
			try { value = JSON.parse(output); }
			catch (error) { throw codedError('MALFORMED_GOAL_SPEC_PROPOSAL', 'Translator output must be one JSON object', error); }
		}
		const proposal = parseGoalSpecProposal(value);
		if (proposal.requestId !== request.requestId) {
			throw codedError('GOAL_SPEC_REQUEST_MISMATCH', 'Translator proposal does not match the outstanding request');
		}
		const candidates = new Set(request.candidateIds);
		for (const identifier of goalPredicateIdentifiers(proposal.predicate)) {
			if (!candidates.has(identifier)) {
				throw codedError('UNLISTED_GOAL_IDENTIFIER', `Translator used identifier '${identifier}' outside the server candidate list`);
			}
		}
		return proposal;
	}
}

export function buildGoalSpecTranslatorPrompt(requestValue) {
	const request = parseGoalSpecRequest(requestValue);
	return [
		'Translate one Minecraft request into one factual, server-verifiable predicate.',
		'Use only the predicate schema and candidate identifiers below. Do not invent identifiers.',
		'Preserve compound factual requests: use all_of for results joined by "and" and any_of only for explicit alternatives joined by "or".',
		'For each requested item or kill, emit its own inventory_contains or entity_killed_by_agent leaf, including the requested item count.',
		'Compound predicates may contain at most 16 factual leaves and must use only the bounded candidate list.',
		'If the outcome is subjective, use operator_confirmed. Keep the summary short and concrete.',
		'Return exactly one JSON object matching the supplied schema and nothing else.',
		`Request: ${JSON.stringify(request.originalRequest)}`,
		`Candidate identifiers: ${JSON.stringify(request.candidateIds)}`,
		`Predicate schema: ${JSON.stringify(GOAL_PREDICATE_SCHEMA)}`,
		`The requestId field must be ${JSON.stringify(request.requestId)}.`,
	].join('\n');
}

function codedError(code, message, cause) {
	if (cause instanceof GoalSpecError) return cause;
	const error = new Error(message, cause === undefined ? undefined : { cause });
	error.code = code;
	return error;
}
