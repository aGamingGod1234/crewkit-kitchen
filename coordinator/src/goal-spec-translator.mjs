import {
	GOAL_PREDICATE_SCHEMA,
	GOAL_SPEC_PROPOSAL_SCHEMA,
	GoalSpecError,
	goalPredicateIdentifiers,
	parseGoalSpecProposal,
	parseGoalSpecRequest,
} from './goal-spec.mjs';
import { TASK_PLAN_SCHEMA, validateTaskPlan } from './live-task-view.mjs';

export const MAX_GOAL_SPEC_CORRECTION_ATTEMPTS = 3;

/** A compiled dragon goal can start without its optional advisory plan if Luna fails. */
export function fallbackCompiledDragonGoal(requestValue) {
	const request = parseGoalSpecRequest(requestValue);
	if (request.candidateIds.length !== 1 || request.candidateIds[0] !== 'minecraft:ender_dragon') return null;
	const command = request.originalRequest.trim().toLowerCase()
		.replace(/[.!?]+$/, '')
		.replace(/^(?:(?:hey[, ]*|please\s+|can you\s+|could you\s+|would you\s+))+/, '');
	if (!/^(?:beat (?:the )?game|(?:kill|slay|defeat) (?:(?:the|a|an) )?ender dragon)$/.test(command)) return null;
	return parseGoalSpecProposal({
		requestId: request.requestId,
		summary: 'Defeat the Ender Dragon; choose and revise prerequisites from live world evidence.',
		predicate: { type: 'entity_killed_by_agent', entityType: 'minecraft:ender_dragon', afterGoalStart: true },
	});
}

export class GoalSpecTranslator {
	#generate;

	constructor({ generate }) {
		if (typeof generate !== 'function') throw new TypeError('generate must be a function');
		this.#generate = generate;
	}

	async translate(requestValue, { signal, correctiveFeedback = null } = {}) {
		const request = parseGoalSpecRequest(requestValue);
		const correction = normalizeCorrectiveFeedback(correctiveFeedback, request.requestId);
		if (signal?.aborted) throw signal.reason ?? codedError('GOAL_SPEC_CANCELLED', 'Goal translation was cancelled');
		const output = await this.#generate({
			request,
			prompt: buildGoalSpecTranslatorPrompt(request, { correctiveFeedback: correction }),
			schema: { ...GOAL_SPEC_PROPOSAL_SCHEMA, required: [...GOAL_SPEC_PROPOSAL_SCHEMA.required, 'plan'], properties: { ...GOAL_SPEC_PROPOSAL_SCHEMA.properties, plan: TASK_PLAN_SCHEMA } },
			signal,
		});
		let value = output;
		if (typeof output === 'string') {
			try { value = JSON.parse(output); }
			catch (error) { throw codedError('MALFORMED_GOAL_SPEC_PROPOSAL', 'Translator output must be one JSON object', error); }
		}
		const normalized = normalizeStructuredProposal(value);
		const { plan, ...goalProposal } = normalized ?? {};
		const proposal = { ...parseGoalSpecProposal(goalProposal) };
		// Optional display advice cannot reject an otherwise valid completion goal.
		if (plan !== undefined) { try { proposal.plan = validateTaskPlan(plan); } catch { /* the main agent may publish its own plan */ } }
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

function normalizeStructuredProposal(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) return value;
	return { ...value, predicate: normalizeStructuredPredicate(value.predicate) };
}

function normalizeStructuredPredicate(value) {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) return value;
	const normalized = { ...value };
	if (normalized.dimensionId === null) delete normalized.dimensionId;
	if (Array.isArray(normalized.predicates)) {
		normalized.predicates = normalized.predicates.map(normalizeStructuredPredicate);
	}
	if (normalized.type === 'block_matches' && Array.isArray(normalized.properties)) {
		const entries = normalized.properties.map(entry => {
			if (entry === null || typeof entry !== 'object' || Array.isArray(entry)) {
				throw codedError('MALFORMED_GOAL_SPEC_PROPOSAL', 'Translator block properties must be name/value objects');
			}
			const { name, value } = entry;
			if (typeof name !== 'string' || name.trim() === '' || typeof value !== 'string' || value.trim() === '') {
				throw codedError('MALFORMED_GOAL_SPEC_PROPOSAL', 'Translator block properties require nonblank string name and value');
			}
			return [name, value];
		});
		if (new Set(entries.map(([name]) => name)).size !== entries.length) {
			throw codedError('MALFORMED_GOAL_SPEC_PROPOSAL', 'Translator block properties contain a duplicate name');
		}
		normalized.properties = Object.fromEntries(entries);
	}
	return normalized;
}

export function buildGoalSpecTranslatorPrompt(requestValue, { correctiveFeedback = null } = {}) {
	const request = parseGoalSpecRequest(requestValue);
	const correction = normalizeCorrectiveFeedback(correctiveFeedback, request.requestId);
	return [
		'Translate one Minecraft request into one server-checked completion predicate.',
		'Use only the predicate schema and candidate identifiers below. Do not invent identifiers.',
		'Preserve compound factual requests: use all_of when every requested result is required. Use any_of for explicit alternatives, including "or", "either", or any eligible member of a requested category.',
		'A factual category request can accept several relevant candidate identifiers without naming one subtype. Preserve those accepted item variants with inventory_contains_any { itemIds, count } rather than arbitrarily requiring one subtype. itemIds contains 1 to 64 unique candidate identifiers; count is the minimum SUM of inventory counts across matching variants and stacks, not a separate requirement for every variant. This is one factual leaf, so a category with more than 16 identifiers does not need an oversized any_of. Category ambiguity alone is not a subjective outcome and must not become operator_confirmed.',
		'For a request to get any block, inventory_contains_block { count } verifies the number of placeable block items held without choosing a material.',
		'For each requested item or kill, emit its own inventory_contains, inventory_contains_any, or entity_killed_by_agent leaf. Use inventory_contains for one exact item and inventory_contains_any when interchangeable variants may contribute to the same requested quantity. Preserve item counts in count. Preserve kill counts by repeating the kill leaf under all_of so each kill needs distinct evidence.',
		'Every any_of branch must preserve all factual quantities required by the request. operator_confirmed may accompany subjective factual results, but it cannot replace their item or kill leaves.',
		'Compound predicates may contain at most 16 factual leaves and must use only the bounded candidate list. The combined itemIds lengths across all inventory_contains_any leaves may not exceed 64, including references repeated in different groups; exact inventory_contains leaves do not consume this additional group-reference budget.',
		'If no listed candidate identifies the requested object, do not invent one or substitute an unrelated position or survival result. Use operator_confirmed so the agent may act and the operator can verify completion.',
		'For crafting, building, subjective, or other results not provable by the offered factual predicates, include operator_confirmed on every completion path.',
		'Write summary as a concise advisory route with concrete subgoals and prerequisites, separated by semicolons. The Minecraft agent chooses and may reorder or replace these steps from live evidence. You do not control actions or choose its final route. Keep summary within 512 characters.',
		'Also return plan:{steps:[...]} as an advisory dependency DAG with stable short IDs, readable labels, dependsOn IDs, detail, kind, status and evidence. Use pending for every unobserved step. The main agent owns all action decisions and may revise this plan. Include useful prerequisite branches, not each mouse/key action. Inventory evidence names eligible itemIds and count; world evidence needs an exact OBSERVED dimension/block position. Unknown locations must be manual steps with evidence:null. Milestones are historical achievements, manual steps are agent-reported intentions, inventory steps are present possessions, and world steps are persistent observed structures. evidence is null or {itemIds:[],count:1,dimension:null,x:null,y:null,z:null,blockId:null} with the required fields filled. Do not claim any resource, location or structure is observed. Planning prerequisite item IDs may use ordinary Minecraft identifiers beyond the final-predicate candidate list; only the completion predicate is restricted to that list.',
		'Return exactly one JSON object matching the supplied schema and nothing else.',
		`Request: ${JSON.stringify(request.originalRequest)}`,
		`Candidate identifiers: ${JSON.stringify(request.candidateIds)}`,
		...(correction === null ? [] : [
			`Correction attempt ${correction.attempt}: Minecraft rejected this exact proposal with reason code ${JSON.stringify(correction.reasonCode)}: ${JSON.stringify(correction.rejectedProposal)}. Return a corrected proposal and do not repeat it unchanged.`,
		]),
		`Predicate schema: ${JSON.stringify(GOAL_PREDICATE_SCHEMA)}`,
		`The requestId field must be ${JSON.stringify(request.requestId)}.`,
	].join('\n');
}

function normalizeCorrectiveFeedback(value, requestId) {
	if (value === null || value === undefined) return null;
	if (typeof value !== 'object' || Array.isArray(value)) throw new TypeError('correctiveFeedback must be an object or null');
	const keys = Object.keys(value).sort();
	if (keys.length !== 3 || keys[0] !== 'attempt' || keys[1] !== 'reasonCode' || keys[2] !== 'rejectedProposal') {
		throw new TypeError('correctiveFeedback fields differ from the closed schema');
	}
	if (!Number.isSafeInteger(value.attempt) || value.attempt < 1 || value.attempt > MAX_GOAL_SPEC_CORRECTION_ATTEMPTS) {
		throw new TypeError(`correctiveFeedback.attempt must be between 1 and ${MAX_GOAL_SPEC_CORRECTION_ATTEMPTS}`);
	}
	if (typeof value.reasonCode !== 'string' || !/^[A-Z0-9_]{1,128}$/.test(value.reasonCode)) {
		throw new TypeError('correctiveFeedback.reasonCode must be a bounded error code');
	}
	const rejectedProposal = parseGoalSpecProposal(value.rejectedProposal);
	if (rejectedProposal.requestId !== requestId) throw new TypeError('correctiveFeedback proposal must match requestId');
	return Object.freeze({ attempt: value.attempt, reasonCode: value.reasonCode, rejectedProposal });
}

function codedError(code, message, cause) {
	if (cause instanceof GoalSpecError) return cause;
	const error = new Error(message, cause === undefined ? undefined : { cause });
	error.code = code;
	return error;
}
