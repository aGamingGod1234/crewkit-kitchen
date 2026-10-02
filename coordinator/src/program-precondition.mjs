import { parseArenaScriptPrecondition } from './arena-script/parser.mjs';
import { ArenaScriptInterpreter } from './arena-script/interpreter.mjs';
import { createInterpreterFacts } from './arena-script/facts.mjs';
import { SCRIPT_BINDINGS } from './arena-script/minecraft-api.mjs';
import { adaptObservation } from './observation-adapter.mjs';
import { validateProgramParameters } from './program-parameters.mjs';

const compiledPreconditions = new WeakSet();

/** Validates one bounded, read-only expression before it can be queued. */
export function compileProgramPrecondition(source) {
	try {
		const compiled = parseArenaScriptPrecondition(source);
		compiledPreconditions.add(compiled);
		return compiled;
	} catch (cause) {
		throw Object.assign(new TypeError('precondition must be one bounded, side-effect-free ArenaScript expression', { cause }), { code: 'INVALID_PROGRAM_PRECONDITION' });
	}
}

/** Evaluate against the caller's fresh observation; parameters remain independent literals. */
export function evaluateProgramPrecondition(precondition, { observation, parameters } = {}) {
	const compiled = typeof precondition === 'string' ? compileProgramPrecondition(precondition) : precondition;
	if (!compiledPreconditions.has(compiled)) throw Object.assign(new TypeError('precondition must be compiled by compileProgramPrecondition'), { code: 'INVALID_PROGRAM_PRECONDITION' });
	const raw = observation && Object.hasOwn(observation, 'ready') && (Object.hasOwn(observation, 'position') || observation.ready === false)
		? adaptObservation(observation) : observation;
	const facts = createInterpreterFacts(raw);
	const vm = new ArenaScriptInterpreter(compiled, SCRIPT_BINDINGS, { parameters: validateProgramParameters(parameters) });
	vm.start(facts);
	return vm.evaluateWatcher('watcher-0', facts);
}
