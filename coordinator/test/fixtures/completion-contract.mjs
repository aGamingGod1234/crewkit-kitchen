/**
 * Supplies the smallest explicit factual contract for coordinator fixtures.
 * Production code still parses, binds, and verifies this contract normally.
 */
export function completionContract(goalRevision = 1, predicate = { type: 'position_within', x: 0, y: 64, z: 0, radius: 16 }) {
	return { goalRevision, predicates: [structuredClone(predicate)] };
}

export function withCompletionContract(decision, goalRevision = 1) {
	if (decision === null || typeof decision !== 'object' || !['replace', 'finish'].includes(decision.directive)) return decision;
	return decision.completionContract === undefined
		? { ...decision, completionContract: completionContract(goalRevision) }
		: decision;
}
