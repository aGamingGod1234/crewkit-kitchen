import { ArenaScriptError, executionError } from './errors.mjs';
import { types as nodeTypes } from 'node:util';
import { DEFAULT_ARENA_SCRIPT_LIMITS, normalizeArenaScriptLimits } from './limits.mjs';

const CAPABILITY_NAMES = new Set(['program', 'player', 'world', 'inventory']);
const CAPABILITY_MEMBERS = Object.freeze({
	program: new Set(['onUnhandledAttention', 'repeatUntil', 'watch', 'checkpoint', 'finish']),
	player: new Set(['moveTo', 'state', 'wait']),
	world: new Set(),
	inventory: new Set(['countTag']),
});
const FORBIDDEN_MEMBER_NAMES = new Set(['__defineGetter__', '__defineSetter__', '__lookupGetter__', '__lookupSetter__', '__proto__', 'arguments', 'callee', 'caller', 'constructor', 'eval', 'prototype']);
const ACTION_RESULT_STATES = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT']);
const PLAYER_PRIMITIVES = Object.freeze({ moveTo: 'move_to', wait: 'wait' });
const CANONICAL_LIMITS = Object.freeze({ depth: 256, nodes: 4_096, keys: 4_096, stringBytes: 16_384, arrayLength: 256, outputBytes: 4_096, resultBytes: 4_096, watcherIdBytes: 128 });
const IDLE_YIELD = frozenRecord({ kind: 'idle' });

/** Deterministically executes a compiled ArenaScript AST without evaluating source JavaScript. */
export class ArenaScriptInterpreter {
	#compiled;
	#bindings;
	#limits;
	#frames = [];
	#values = [];
	#waiting = null;
	#started = false;
	#terminal = false;
	#operations = 0;
	#commands = 0;
	#sequence = 0;
	#context = null;
	#watchers = new Map();
	#yield = null;
	#lifecycle = 'READY';
	#loopIterations = 0;

	constructor(compiled, bindings, { limits = DEFAULT_ARENA_SCRIPT_LIMITS } = {}) {
		if (!compiled?.ast || compiled.ast.type !== 'Program') throw executionError('INVALID_PROGRAM', 'ArenaScript INVALID_PROGRAM: compiled program is required');
		if (!Object.isFrozen(compiled)) throw executionError('INVALID_PROGRAM', 'ArenaScript INVALID_PROGRAM: compiled program must be frozen');
		const normalizedBindings = normalizeBindings(bindings);
		this.#compiled = compiled;
		this.#bindings = normalizedBindings;
		this.#limits = normalizeArenaScriptLimits(limits);
	}

	start(facts) {
		if (this.#started) throw executionError('ALREADY_STARTED', 'ArenaScript ALREADY_STARTED: program has already started');
		const normalizedFacts = freezeFacts(facts);
		this.#started = true;
		this.#beginActivation(normalizedFacts);
		this.#frames.push(statementListFrame(this.#compiled.ast.body, createRootEnvironment(this.#bindings, this.#context)));
		return this.#run();
	}

	resume(result, facts) {
		if (this.#waiting === null) throw executionError('NOT_WAITING', 'ArenaScript NOT_WAITING: no command is awaiting a result');
		const normalizedResult = normalizeActionResult(result);
		if (normalizedResult.stateToken !== this.#waiting.stateToken) throw executionError('STALE_STATE_TOKEN', 'ArenaScript STALE_STATE_TOKEN: action result does not match the pending command');
		const normalizedFacts = freezeFacts(facts);
		this.#context.facts = normalizedFacts;
		this.#waiting.environment.setResult(normalizedResult);
		this.#waiting = null;
		this.#beginSlice();
		return this.#run();
	}

	runWatcher(watcherId, facts) {
		if (!this.#started) throw executionError('NOT_STARTED', 'ArenaScript NOT_STARTED: start the program before running watchers');
		validateWatcherId(watcherId);
		if (this.#lifecycle !== 'ACTIVE') throw executionError('INACTIVE_LIFECYCLE', `ArenaScript INACTIVE_LIFECYCLE: program is ${this.#lifecycle}`);
		if (this.#waiting !== null) throw executionError('NOT_IDLE', 'ArenaScript NOT_IDLE: a command result is still required');
		const watcher = this.#watchers.get(watcherId);
		if (!watcher) throw executionError('UNKNOWN_WATCHER', `ArenaScript UNKNOWN_WATCHER: ${watcherId}`);
		const normalizedFacts = freezeFacts(facts);
		this.#context.facts = normalizedFacts;
		this.#frames = [];
		this.#values = [];
		this.#terminal = false;
		this.#yield = null;
		this.#beginSlice();
		this.#frames.push({ type: 'watcher-after-condition', watcher });
		this.#invokeFunction(watcher.condition, []);
		return this.#run();
	}

	#beginActivation(normalizedFacts) {
		this.#context = { facts: normalizedFacts };
		this.#frames = [];
		this.#values = [];
		this.#terminal = false;
		this.#operations = 0;
		this.#yield = null;
		this.#lifecycle = 'ACTIVE';
		this.#loopIterations = 0;
	}

	#beginSlice() {
		this.#operations = 0;
		this.#loopIterations = 0;
	}

	#run() {
		try {
			while (this.#frames.length > 0) {
				if (this.#terminal) return this.#yield;
				const frame = this.#frames.pop();
				this.#dispatch(frame);
				if (this.#yield) {
					const yielded = this.#yield;
					this.#yield = null;
					return yielded;
				}
			}
			return IDLE_YIELD;
		} catch (error) {
			this.#frames = [];
			this.#values = [];
			this.#waiting = null;
			this.#yield = null;
			this.#terminal = true;
			this.#lifecycle = 'PAUSED';
			if (error instanceof ArenaScriptError) throw error;
			throw executionError('EXECUTION_ERROR', 'ArenaScript EXECUTION_ERROR: execution failed', null, { cause: error });
		}
	}

	#dispatch(frame) {
		switch (frame.type) {
			case 'statements': return this.#runStatements(frame);
			case 'after-statement': return this.#afterStatement(frame);
			case 'statement': return this.#runStatement(frame);
			case 'expression': return this.#runExpression(frame);
			case 'after-member': return this.#afterMember(frame);
			case 'after-unary': return this.#afterUnary(frame);
			case 'after-binary-left': return this.#afterBinaryLeft(frame);
			case 'after-binary-right': return this.#afterBinaryRight(frame);
			case 'after-conditional': return this.#afterConditional(frame);
			case 'expressions': return this.#runExpressions(frame);
			case 'after-object': return this.#afterObject(frame);
			case 'after-call': return this.#afterCall(frame);
			case 'assignment-set': return this.#assignmentSet(frame);
			case 'pending-result': return this.#values.push(frame.environment.takeResult());
			case 'await': return this.#values.push(this.#values.pop());
			case 'discard': this.#values.pop(); return this.#values.push(normalCompletion());
			case 'declare': return this.#declare(frame);
			case 'if': return this.#afterIf(frame);
			case 'return': return this.#values.push({ kind: 'return', value: this.#values.pop() });
			case 'for-init': return this.#forInit(frame);
			case 'for-after-init': return this.#forAfterInit(frame);
			case 'for-check': return this.#forCheck(frame);
			case 'for-body': return this.#forBody(frame);
			case 'for-update': return this.#forUpdate(frame);
			case 'for-after-update': return this.#forAfterUpdate(frame);
			case 'while-check': return this.#whileCheck(frame);
			case 'while-body': return this.#whileBody(frame);
			case 'do-body': return this.#doBody(frame);
			case 'do-check': return this.#doCheck(frame);
			case 'do-test': return this.#doTest(frame);
			case 'for-each-start': return this.#forEachStart(frame);
			case 'for-each-next': return this.#forEachNext(frame);
			case 'for-each-after-body': return this.#forEachAfterBody(frame);
			case 'function-after-body': return this.#functionAfterBody();
			case 'repeat-check': return this.#repeatCheck(frame);
			case 'repeat-after-condition': return this.#repeatAfterCondition(frame);
			case 'repeat-after-body': return this.#repeatAfterBody(frame);
			case 'watcher-after-condition': return this.#watcherAfterCondition(frame);
			case 'watcher-after-handler': this.#values.pop(); return;
			default: throw executionError('INVALID_FRAME', `ArenaScript INVALID_FRAME: ${frame.type}`);
		}
	}

	#visit(node) {
		this.#operations += 1;
		if (this.#operations > this.#limits.operationsPerResume) throw this.#error('OPERATION_LIMIT', `ArenaScript OPERATION_LIMIT: exceeded ${this.#limits.operationsPerResume} operations`, node);
	}

	#runStatements(frame) {
		if (frame.index === 0) hoistFunctionDeclarations(frame.statements, frame.environment);
		if (frame.index >= frame.statements.length) return this.#values.push(normalCompletion());
		this.#frames.push({ type: 'after-statement', frame });
		this.#frames.push({ type: 'statement', node: frame.statements[frame.index], environment: frame.environment });
	}

	#afterStatement({ frame }) {
		const completion = this.#values.pop();
		if (completion.kind !== 'normal') return this.#values.push(completion);
		frame.index += 1;
		this.#frames.push(frame);
	}

	#runStatement({ node, environment }) {
		this.#visit(node);
		switch (node.type) {
			case 'EmptyStatement': return this.#values.push(normalCompletion());
			case 'ExpressionStatement': this.#frames.push({ type: 'discard' }); return this.#frames.push({ type: 'expression', node: node.expression, environment });
			case 'BlockStatement': return this.#frames.push(statementListFrame(node.body, new Environment(environment, this.#context)));
			case 'VariableDeclaration': return this.#runDeclaration(node, environment, 0);
			case 'FunctionDeclaration':
				if (!environment.hasOwn(node.id.name)) environment.define(node.id.name, createFunction(node, environment), 'const');
				return this.#values.push(normalCompletion());
			case 'IfStatement': this.#frames.push({ type: 'if', node, environment }); return this.#frames.push({ type: 'expression', node: node.test, environment });
			case 'ForStatement': {
				const loopEnvironment = new Environment(environment, this.#context);
				this.#frames.push({ type: 'for-init', node, environment: loopEnvironment, iterations: 0 });
				return;
			}
			case 'WhileStatement': return this.#frames.push({ type: 'while-check', node, environment, iterations: 0 });
			case 'DoWhileStatement': return this.#frames.push({ type: 'do-body', node, environment, iterations: 0 });
			case 'ForInStatement':
			case 'ForOfStatement': this.#frames.push({ type: 'for-each-start', node, environment, iterations: 0 }); return this.#frames.push({ type: 'expression', node: node.right, environment });
			case 'ReturnStatement':
				if (!node.argument) return this.#values.push({ kind: 'return', value: undefined });
				this.#frames.push({ type: 'return' }); return this.#frames.push({ type: 'expression', node: node.argument, environment });
			case 'BreakStatement': return this.#values.push({ kind: 'break' });
			case 'ContinueStatement': return this.#values.push({ kind: 'continue' });
			default: throw this.#error('UNSUPPORTED_SYNTAX', `ArenaScript UNSUPPORTED_SYNTAX: statement ${node.type}`, node);
		}
	}

	#runDeclaration(node, environment, index) {
		if (index >= node.declarations.length) return this.#values.push(normalCompletion());
		const declaration = node.declarations[index];
		if (declaration.id.type !== 'Identifier') throw this.#error('UNSUPPORTED_SYNTAX', 'ArenaScript UNSUPPORTED_SYNTAX: declaration patterns are not supported', declaration);
		if (!declaration.init) {
			environment.define(declaration.id.name, undefined, node.kind);
			return this.#runDeclaration(node, environment, index + 1);
		}
		this.#frames.push({ type: 'declare', node, environment, index, declaration });
		this.#frames.push({ type: 'expression', node: declaration.init, environment });
	}

	#declare(frame) {
		frame.environment.define(frame.declaration.id.name, this.#values.pop(), frame.node.kind);
		this.#runDeclaration(frame.node, frame.environment, frame.index + 1);
	}

	#afterIf({ node, environment }) {
		const test = this.#values.pop();
		if (test) return this.#frames.push({ type: 'statement', node: node.consequent, environment });
		if (node.alternate) return this.#frames.push({ type: 'statement', node: node.alternate, environment });
		this.#values.push(normalCompletion());
	}

	#runExpression({ node, environment }) {
		this.#visit(node);
		switch (node.type) {
			case 'Literal':
				if (typeof node.value === 'string' && Buffer.byteLength(node.value, 'utf8') > CANONICAL_LIMITS.outputBytes) throw this.#error('OUTPUT_LIMIT', 'ArenaScript OUTPUT_LIMIT: model string exceeds the output limit', node);
				return this.#values.push(node.value);
			case 'Identifier': return this.#values.push(environment.get(node.name, node));
			case 'MemberExpression': this.#frames.push({ type: 'after-member', node }); return this.#frames.push({ type: 'expression', node: node.object, environment });
			case 'ObjectExpression': {
				this.#frames.push({ type: 'after-object', node, count: node.properties.length, object: true });
				return this.#frames.push(expressionListFrame(node.properties.map((property) => property.value), environment));
			}
			case 'ArrayExpression': this.#frames.push({ type: 'after-object', node, count: node.elements.length, object: false }); return this.#frames.push(expressionListFrame(node.elements, environment));
			case 'UnaryExpression': this.#frames.push({ type: 'after-unary', operator: node.operator, node }); return this.#frames.push({ type: 'expression', node: node.argument, environment });
			case 'BinaryExpression':
			case 'LogicalExpression': this.#frames.push({ type: 'after-binary-left', node, environment }); return this.#frames.push({ type: 'expression', node: node.left, environment });
			case 'ConditionalExpression': this.#frames.push({ type: 'after-conditional', node, environment }); return this.#frames.push({ type: 'expression', node: node.test, environment });
			case 'AssignmentExpression': return this.#assignment(node, environment);
			case 'UpdateExpression': return this.#update(node, environment);
			case 'ArrowFunctionExpression':
			case 'FunctionExpression': return this.#values.push(createFunction(node, environment));
			case 'CallExpression': this.#frames.push({ type: 'after-call', node, environment, base: this.#values.length }); return this.#frames.push(expressionListFrame(node.arguments, environment));
			case 'AwaitExpression': this.#frames.push({ type: 'await' }); return this.#frames.push({ type: 'expression', node: node.argument, environment });
			default: throw this.#error('UNSUPPORTED_SYNTAX', `ArenaScript UNSUPPORTED_SYNTAX: expression ${node.type}`, node);
		}
	}

	#afterMember({ node }) {
		const object = this.#values.pop();
		const name = node.property?.name;
		if (!name || FORBIDDEN_MEMBER_NAMES.has(name)) throw this.#error('UNSAFE_MEMBER_ACCESS', `ArenaScript UNSAFE_MEMBER_ACCESS: member ${name ?? '<unknown>'} is not available`, node);
		if (isCapability(object)) {
			if (!CAPABILITY_MEMBERS[object.capability].has(name)) throw this.#error('UNSAFE_MEMBER_ACCESS', `ArenaScript UNSAFE_MEMBER_ACCESS: member ${name} is not available`, node);
			return this.#values.push(Object.freeze(Object.assign(Object.create(null), { capability: object.capability, member: name })));
		}
		if (object === null || typeof object !== 'object' || !Object.hasOwn(object, name)) throw this.#error('UNKNOWN_MEMBER', `ArenaScript UNKNOWN_MEMBER: ${name}`, node);
		this.#values.push(object[name]);
	}

	#afterUnary({ operator, node }) {
		const value = this.#values.pop();
		if (operator === '!') return this.#values.push(!value);
		assertFiniteNumber(value, () => this.#error('INVALID_OPERAND', 'ArenaScript INVALID_OPERAND: unary arithmetic requires a finite number', node));
		if (operator === '+') return this.#values.push(value);
		if (operator === '-') return this.#values.push(-value);
		if (operator === '~') return this.#values.push(~value);
		throw this.#error('UNSUPPORTED_SYNTAX', `ArenaScript UNSUPPORTED_SYNTAX: unary ${operator}`, node);
	}

	#afterBinaryLeft({ node, environment }) {
		const left = this.#values.pop();
		if (node.type === 'LogicalExpression') {
			if ((node.operator === '&&' && !left) || (node.operator === '||' && left) || (node.operator === '??' && left !== null && left !== undefined)) return this.#values.push(left);
		}
		this.#frames.push({ type: 'after-binary-right', node, left });
		this.#frames.push({ type: 'expression', node: node.right, environment });
	}

	#afterBinaryRight({ node, left }) {
		const right = this.#values.pop();
		if (node.type === 'LogicalExpression') return this.#values.push(right);
		this.#values.push(applyBinary(node.operator, left, right, (code, message) => this.#error(code, message, node)));
	}

	#afterConditional({ node, environment }) {
		const test = this.#values.pop();
		this.#frames.push({ type: 'expression', node: test ? node.consequent : node.alternate, environment });
	}

	#assignment(node, environment) {
		if (node.left.type !== 'Identifier') throw this.#error('UNSAFE_MEMBER_ACCESS', 'ArenaScript UNSAFE_MEMBER_ACCESS: only local variables may be assigned', node);
		if (node.operator === '=') this.#frames.push({ type: 'assignment-set', environment, name: node.left.name });
		else this.#frames.push({ type: 'assignment-set', environment, name: node.left.name, operator: node.operator.slice(0, -1), left: environment.get(node.left.name, node.left), node });
		return this.#frames.push({ type: 'expression', node: node.right, environment });
	}

	#assignmentSet({ environment, name, operator = null, left, node }) {
		const right = this.#values.pop();
		const value = operator ? applyBinary(operator, left, right, (code, message) => this.#error(code, message, node)) : right;
		environment.set(name, value, node?.left);
		this.#values.push(value);
	}

	#update(node, environment) {
		if (node.argument.type !== 'Identifier') throw this.#error('UNSAFE_MEMBER_ACCESS', 'ArenaScript UNSAFE_MEMBER_ACCESS: only local variables may be updated', node);
		const previous = environment.get(node.argument.name, node.argument);
		assertFiniteNumber(previous, () => this.#error('INVALID_OPERAND', 'ArenaScript INVALID_OPERAND: update requires a finite number', node));
		const next = node.operator === '++' ? previous + 1 : previous - 1;
		environment.set(node.argument.name, next, node.argument);
		this.#values.push(node.prefix ? next : previous);
	}

	#runExpressions(frame) {
		if (frame.index >= frame.nodes.length) return;
		const node = frame.nodes[frame.index];
		if (!node) throw executionError('UNSUPPORTED_SYNTAX', 'ArenaScript UNSUPPORTED_SYNTAX: array holes are not supported');
		frame.index += 1;
		this.#frames.push(frame);
		this.#frames.push({ type: 'expression', node, environment: frame.environment });
	}

	#afterObject({ node, count, object }) {
		const values = this.#values.splice(this.#values.length - count, count);
		if (!object) return this.#values.push(Object.freeze(values));
		const record = Object.create(null);
		for (let index = 0; index < count; index += 1) record[propertyKey(node.properties[index])] = values[index];
		this.#values.push(Object.freeze(record));
	}

	#afterCall({ node, environment, base }) {
		const args = this.#values.splice(base);
		const path = memberPath(node.callee);
		if (node.callee.type === 'Identifier') {
			if (node.callee.name === 'tryResult') return this.#values.push(args[0]);
			const target = environment.get(node.callee.name, node.callee);
			if (!isArenaFunction(target)) throw this.#error('UNSUPPORTED_SYNTAX', 'ArenaScript UNSUPPORTED_SYNTAX: call target is not an Arena function', node);
			return this.#invokeFunction(target, args);
		}
		if (!path) throw this.#error('UNSUPPORTED_SYNTAX', 'ArenaScript UNSUPPORTED_SYNTAX: call target is not declared', node);
		const callPath = path.join('.');
		switch (callPath) {
			case 'program.onUnhandledAttention': return this.#values.push(undefined);
			case 'program.watch': return this.#registerWatcher(node, args);
			case 'program.checkpoint': return this.#terminalYield('checkpoint', node, terminalText(args[0], 'checkpoint', node));
			case 'program.finish': return this.#terminalYield('finish', node, terminalText(args[0], 'finished', node));
			case 'program.repeatUntil': return this.#repeatUntil(node, args);
			case 'player.state': return this.#values.push(this.#context.facts.player);
			case 'inventory.countTag':
				if (typeof args[0] !== 'string') throw this.#error('INVALID_ARGUMENT', 'ArenaScript INVALID_ARGUMENT: inventory tag must be a string', node);
				return this.#values.push(this.#context.facts.inventory.tagCounts[args[0]] ?? 0);
			default: return this.#yieldCommand(callPath, args, node, environment);
		}
	}

	#yieldCommand(path, args, node, environment) {
		const binding = actionBinding(this.#bindings, path);
		if (!binding) throw this.#error('UNBOUND_ACTION', `ArenaScript UNBOUND_ACTION: ${path}`, node);
		if (this.#commands >= this.#limits.commandsPerProgram) throw this.#error('COMMAND_LIMIT', `ArenaScript COMMAND_LIMIT: exceeded ${this.#limits.commandsPerProgram} commands`, node);
		this.#commands += 1;
		const stateToken = `arena-state-${++this.#sequence}`;
		this.#frames.push({ type: 'pending-result', environment });
		this.#waiting = { environment, stateToken };
		this.#yield = frozenRecord({
			kind: 'command',
			stepId: stepIdFor(node),
			call: frozenRecord({ primitive: binding.primitive, arguments: freezeOutput(args.length === 1 ? args[0] : args) }),
			stateToken,
		});
	}

	#terminalYield(kind, node, value) {
		this.#terminal = true;
		this.#lifecycle = kind === 'finish' ? 'FINISHED' : 'PAUSED';
		this.#frames = [];
		this.#values = [];
		this.#yield = kind === 'finish'
			? frozenRecord({ kind, stepId: stepIdFor(node), summary: value })
			: frozenRecord({ kind, stepId: stepIdFor(node), reason: value });
	}

	#registerWatcher(node, args) {
		if (!isArenaFunction(args[0]) || !isArenaFunction(args[2])) throw this.#error('INVALID_WATCHER', 'ArenaScript INVALID_WATCHER: watcher functions are required', node);
		const id = `watcher-${this.#watchers.size}`;
		this.#watchers.set(id, Object.freeze({ id, condition: args[0], handler: args[2], mode: args[1]?.mode }));
		this.#values.push(undefined);
	}

	#repeatUntil(node, args) {
		if (!isArenaFunction(args[0]) || !isArenaFunction(args[2]) || !Number.isSafeInteger(args[1]?.maxIterations)) throw this.#error('INVALID_REPEAT', 'ArenaScript INVALID_REPEAT: literal bounded callbacks are required', node);
		this.#frames.push({ type: 'repeat-check', node, condition: args[0], body: args[2], maxIterations: args[1].maxIterations, iterations: 0 });
	}

	#repeatCheck(frame) {
		this.#frames.push({ type: 'repeat-after-condition', frame });
		this.#invokeFunction(frame.condition, []);
	}

	#repeatAfterCondition({ frame }) {
		const condition = this.#values.pop();
		if (condition) return this.#values.push(undefined);
		if (frame.iterations >= frame.maxIterations) return this.#terminalYield('checkpoint', frame.node, 'repeat_until_exhausted');
		this.#claimLoopIteration(frame.node);
		frame.iterations += 1;
		this.#frames.push({ type: 'repeat-after-body', frame });
		this.#invokeFunction(frame.body, []);
	}

	#repeatAfterBody({ frame }) {
		const completion = this.#values.pop();
		if (completion?.kind && completion.kind !== 'normal') return this.#values.push(completion);
		this.#frames.push({ type: 'repeat-check', ...frame });
	}

	#watcherAfterCondition({ watcher }) {
		if (!this.#values.pop()) return;
		this.#frames.push({ type: 'watcher-after-handler' });
		this.#invokeFunction(watcher.handler, []);
	}

	#invokeFunction(fn, args) {
		if (!isArenaFunction(fn)) throw executionError('UNSUPPORTED_SYNTAX', 'ArenaScript UNSUPPORTED_SYNTAX: only Arena functions can be invoked');
		if (args.length !== fn.node.params.length) throw this.#error('ARGUMENT_COUNT', 'ArenaScript ARGUMENT_COUNT: argument count does not match function parameters', fn.node);
		const environment = new Environment(fn.environment, this.#context);
		for (let index = 0; index < args.length; index += 1) environment.define(fn.node.params[index].name, args[index], 'let');
		if (fn.node.id?.name) environment.define(fn.node.id.name, fn, 'const');
		this.#frames.push({ type: 'function-after-body' });
		if (fn.node.body.type === 'BlockStatement') this.#frames.push(statementListFrame(fn.node.body.body, environment));
		else this.#frames.push({ type: 'expression', node: fn.node.body, environment });
	}

	#functionAfterBody() {
		const completion = this.#values.pop();
		if (completion?.kind === 'return') return this.#values.push(completion.value);
		if (completion?.kind === 'break' || completion?.kind === 'continue') throw executionError('INVALID_CONTROL_FLOW', 'ArenaScript INVALID_CONTROL_FLOW: loop control escaped a function');
		this.#values.push(completion?.kind === 'normal' ? undefined : completion);
	}

	#forInit(frame) {
		if (!frame.node.init) return this.#frames.push({ ...frame, type: 'for-check' });
		this.#frames.push({ type: 'for-after-init', frame });
		this.#frames.push({ type: 'statement', node: frame.node.init, environment: frame.environment });
	}

	#forAfterInit({ frame }) {
		const completion = this.#values.pop();
		if (completion.kind !== 'normal') return this.#values.push(completion);
		this.#frames.push({ ...frame, type: 'for-check' });
	}

	#forCheck(frame) {
		if (!frame.node.test) return this.#forBody(frame);
		this.#frames.push({ type: 'for-body', frame });
		this.#frames.push({ type: 'expression', node: frame.node.test, environment: frame.environment });
	}

	#forBody({ frame }) {
		if (!this.#values.pop()) return this.#values.push(normalCompletion());
		this.#assertLoop(frame.node, frame.iterations);
		frame.iterations += 1;
		this.#frames.push({ type: 'for-update', frame });
		this.#frames.push({ type: 'statement', node: frame.node.body, environment: frame.environment });
	}

	#forUpdate({ frame }) {
		const completion = this.#values.pop();
		if (completion.kind === 'return') return this.#values.push(completion);
		if (completion.kind !== 'normal' && completion.kind !== 'continue' && completion.kind !== 'break') return this.#values.push(completion);
		if (completion.kind === 'break') return this.#values.push(normalCompletion());
		if (!frame.node.update) return this.#frames.push({ ...frame, type: 'for-check' });
		this.#frames.push({ type: 'for-after-update', frame });
		this.#frames.push({ type: 'expression', node: frame.node.update, environment: frame.environment });
	}

	#forAfterUpdate({ frame }) {
		this.#values.pop();
		this.#frames.push({ ...frame, type: 'for-check' });
	}

	#whileCheck(frame) {
		this.#frames.push({ type: 'while-body', frame });
		this.#frames.push({ type: 'expression', node: frame.node.test, environment: frame.environment });
	}

	#whileBody({ frame }) {
		if (!this.#values.pop()) return this.#values.push(normalCompletion());
		this.#assertLoop(frame.node, frame.iterations);
		frame.iterations += 1;
		this.#frames.push({ type: 'do-check', frame, whileLoop: true });
		this.#frames.push({ type: 'statement', node: frame.node.body, environment: frame.environment });
	}

	#doBody(frame) {
		this.#assertLoop(frame.node, frame.iterations);
		frame.iterations += 1;
		this.#frames.push({ type: 'do-check', frame });
		this.#frames.push({ type: 'statement', node: frame.node.body, environment: frame.environment });
	}

	#doCheck({ frame, whileLoop = false }) {
		const completion = this.#values.pop();
		if (completion.kind === 'return') return this.#values.push(completion);
		if (completion.kind === 'break') return this.#values.push(normalCompletion());
		if (completion.kind !== 'normal' && completion.kind !== 'continue') return this.#values.push(completion);
		if (whileLoop) return this.#frames.push({ type: 'while-check', ...frame });
		this.#frames.push({ type: 'do-test', frame });
		this.#frames.push({ type: 'expression', node: frame.node.test, environment: frame.environment });
	}

	#doTest({ frame }) {
		if (this.#values.pop()) this.#frames.push({ type: 'do-body', ...frame });
		else this.#values.push(normalCompletion());
	}

	#forEachStart(frame) {
		const source = this.#values.pop();
		if (source === null || typeof source !== 'object') throw this.#error('INVALID_ITERABLE', 'ArenaScript INVALID_ITERABLE: loop source must be an object', frame.node);
		frame.values = frame.node.type === 'ForInStatement' ? Object.keys(source) : [...source];
		this.#frames.push({ type: 'for-each-next', frame });
	}

	#forEachNext({ frame }) {
		if (frame.iterations >= frame.values.length) return this.#values.push(normalCompletion());
		this.#assertLoop(frame.node, frame.iterations);
		const value = frame.values[frame.iterations++];
		const left = frame.node.left;
		if (left.type === 'VariableDeclaration') {
			const name = left.declarations[0]?.id?.name;
			if (!name) throw this.#error('UNSUPPORTED_SYNTAX', 'ArenaScript UNSUPPORTED_SYNTAX: loop declaration must use an identifier', left);
			if (frame.iterations === 1) frame.environment.define(name, value, left.kind); else frame.environment.set(name, value, left);
		} else if (left.type === 'Identifier') frame.environment.set(left.name, value, left); else throw this.#error('UNSAFE_MEMBER_ACCESS', 'ArenaScript UNSAFE_MEMBER_ACCESS: loop target must be local', left);
		this.#frames.push({ type: 'for-each-after-body', frame });
		this.#frames.push({ type: 'statement', node: frame.node.body, environment: frame.environment });
	}

	#forEachAfterBody({ frame }) {
		const completion = this.#values.pop();
		if (completion.kind === 'return') return this.#values.push(completion);
		if (completion.kind === 'break') return this.#values.push(normalCompletion());
		if (completion.kind !== 'normal' && completion.kind !== 'continue') return this.#values.push(completion);
		this.#frames.push({ type: 'for-each-next', frame });
	}

	#assertLoop(node, iterations) {
		this.#claimLoopIteration(node);
	}

	#claimLoopIteration(node) {
		if (this.#loopIterations >= this.#limits.loopIterationsPerYield) throw this.#error('LOOP_LIMIT', `ArenaScript LOOP_LIMIT: exceeded ${this.#limits.loopIterationsPerYield} loop iterations`, node);
		this.#loopIterations += 1;
	}

	#error(code, message, node = null) {
		return executionError(code, message, node ? this.#compiled.stepLocations.get(stepIdFor(node)) ?? null : null);
	}
}

class Environment {
	constructor(parent, context) {
		this.parent = parent;
		this.context = context;
		this.values = new Map();
		this.result = undefined;
	}
	define(name, value, kind = 'let') { this.values.set(name, { kind, value }); }
	hasOwn(name) { return this.values.has(name); }
	get(name, node) {
		for (let current = this; current; current = current.parent) if (current.values.has(name)) return current.values.get(name).value;
		throw executionError('UNKNOWN_IDENTIFIER', `ArenaScript UNKNOWN_IDENTIFIER: ${name}`, node?.loc ? Object.freeze({ start: node.start, end: node.end, line: node.loc.start.line, column: node.loc.start.column }) : null);
	}
	set(name, value, node) {
		for (let current = this; current; current = current.parent) if (current.values.has(name)) {
			const slot = current.values.get(name);
			if (slot.kind === 'const') throw executionError('CONST_ASSIGNMENT', `ArenaScript CONST_ASSIGNMENT: ${name} is immutable`, node?.loc ? Object.freeze({ start: node.start, end: node.end, line: node.loc.start.line, column: node.loc.start.column }) : null);
			slot.value = value;
			return;
		}
		throw this.get(name, node);
	}
	setResult(result) { this.result = result; }
	takeResult() { const result = this.result; this.result = undefined; return result; }
}

function createRootEnvironment(bindings, context) {
	const environment = new Environment(null, context);
	for (const name of CAPABILITY_NAMES) environment.define(name, Object.freeze(Object.assign(Object.create(null), { capability: name })), 'const');
	environment.define('tryResult', Object.freeze(Object.assign(Object.create(null), { builtin: 'tryResult' })), 'const');
	environment.define('undefined', undefined, 'const');
	environment.define('NaN', NaN, 'const');
	environment.define('Infinity', Infinity, 'const');
	return environment;
}

function statementListFrame(statements, environment) { return { type: 'statements', statements, environment, index: 0 }; }
function expressionListFrame(nodes, environment) { return { type: 'expressions', nodes, environment, index: 0 }; }
function normalCompletion() { return { kind: 'normal' }; }
function isCapability(value) { return value && typeof value === 'object' && typeof value.capability === 'string'; }
function isArenaFunction(value) { return value && typeof value === 'object' && value.kind === 'arena-function'; }
function createFunction(node, environment) { return Object.freeze(Object.assign(Object.create(null), { kind: 'arena-function', node, environment })); }
function stepIdFor(node) { return `step-${node.start}-${node.end}`; }

function memberPath(node) {
	const names = [];
	for (let current = node; current?.type === 'MemberExpression'; current = current.object) {
		if (current.computed || current.property?.type !== 'Identifier') return null;
		names.unshift(current.property.name);
	}
	if (node?.type === 'Identifier') return [node.name];
	let current = node;
	while (current?.type === 'MemberExpression') current = current.object;
	if (current?.type !== 'Identifier') return null;
	names.unshift(current.name);
	return names;
}

function propertyKey(property) {
	if (property.key.type === 'Identifier') return property.key.name;
	if (property.key.type === 'Literal' && typeof property.key.value === 'string') return property.key.value;
	throw executionError('UNSAFE_MEMBER_ACCESS', 'ArenaScript UNSAFE_MEMBER_ACCESS: object keys must be safe identifiers');
}

function applyBinary(operator, left, right, fail) {
	if (!isSafeOperand(left) || !isSafeOperand(right)) throw fail('INVALID_OPERAND', 'ArenaScript INVALID_OPERAND: binary operators require primitive values');
	switch (operator) {
		case '==': return left === right;
		case '!=': return left !== right;
		case '===': return left === right;
		case '!==': return left !== right;
		case '<': return compareOperands(left, right, fail, '<');
		case '<=': return compareOperands(left, right, fail, '<=');
		case '>': return compareOperands(left, right, fail, '>');
		case '>=': return compareOperands(left, right, fail, '>=');
		case '+': return addOperands(left, right, fail);
		case '-': return numericOperands(left, right, fail, (a, b) => a - b);
		case '*': return numericOperands(left, right, fail, (a, b) => a * b);
		case '/': return numericOperands(left, right, fail, (a, b) => a / b);
		case '%': return numericOperands(left, right, fail, (a, b) => a % b);
		case '**': return numericOperands(left, right, fail, (a, b) => a ** b);
		case '|': return numericOperands(left, right, fail, (a, b) => a | b);
		case '&': return numericOperands(left, right, fail, (a, b) => a & b);
		case '^': return numericOperands(left, right, fail, (a, b) => a ^ b);
		case '<<': return numericOperands(left, right, fail, (a, b) => a << b);
		case '>>': return numericOperands(left, right, fail, (a, b) => a >> b);
		case '>>>': return numericOperands(left, right, fail, (a, b) => a >>> b);
		default: throw fail('UNSUPPORTED_SYNTAX', `ArenaScript UNSUPPORTED_SYNTAX: binary ${operator}`);
	}
}

function isSafeOperand(value) { return value === null || value === undefined || typeof value === 'boolean' || typeof value === 'string' || (typeof value === 'number' && Number.isFinite(value)); }
function assertFiniteNumber(value, fail) { if (typeof value !== 'number' || !Number.isFinite(value)) throw fail(); }
function numericOperands(left, right, fail, operation) { assertFiniteNumber(left, () => fail('INVALID_OPERAND', 'ArenaScript INVALID_OPERAND: arithmetic requires finite numbers')); assertFiniteNumber(right, () => fail('INVALID_OPERAND', 'ArenaScript INVALID_OPERAND: arithmetic requires finite numbers')); return operation(left, right); }
function addOperands(left, right, fail) {
	if (typeof left === 'string' && typeof right === 'string') {
		if (Buffer.byteLength(left, 'utf8') + Buffer.byteLength(right, 'utf8') > CANONICAL_LIMITS.outputBytes) throw fail('OUTPUT_LIMIT', 'ArenaScript OUTPUT_LIMIT: model string exceeds the output limit');
		return left + right;
	}
	return numericOperands(left, right, fail, (a, b) => a + b);
}
function compareOperands(left, right, fail, operator) { if (typeof left !== typeof right || !['number', 'string'].includes(typeof left)) throw fail('INVALID_OPERAND', 'ArenaScript INVALID_OPERAND: comparisons require matching strings or finite numbers'); if (operator === '<') return left < right; if (operator === '<=') return left <= right; if (operator === '>') return left > right; return left >= right; }

function actionBinding(bindings, path) {
	const [root, member] = path.split('.');
	const binding = bindings[root]?.[member];
	if (!binding || typeof binding !== 'object' || typeof binding.primitive !== 'string') return null;
	return binding;
}

function normalizeActionResult(result) {
	const values = exactOwnDataRecord(result, 'action result', ['stateToken', 'state', 'reasonCode']);
	const { stateToken, state, reasonCode } = values;
	if (typeof stateToken !== 'string' || stateToken.length === 0 || typeof state !== 'string' || !ACTION_RESULT_STATES.has(state) || typeof reasonCode !== 'string') {
		throw executionError('INVALID_ACTION_RESULT', 'ArenaScript INVALID_ACTION_RESULT: result fields are invalid');
	}
	if (Buffer.byteLength(stateToken, 'utf8') > CANONICAL_LIMITS.resultBytes || Buffer.byteLength(reasonCode, 'utf8') > CANONICAL_LIMITS.resultBytes) throw limitError('RESULT_LIMIT', 'string bytes');
	return frozenRecord({ stateToken, state, succeeded: state === 'SUCCEEDED', reason: reasonCode, reasonCode });
}

function freezeFacts(facts) {
	const root = exactOwnDataRecord(facts, 'facts', ['player', 'world', 'inventory']);
	const inventory = exactOwnDataRecord(root.inventory, 'facts.inventory', ['tagCounts']);
	return frozenRecord({
		player: freezeDataRecord(root.player, 'facts.player'),
		world: freezeDataRecord(root.world, 'facts.world'),
		inventory: frozenRecord({ tagCounts: freezeDataRecord(inventory.tagCounts, 'facts.inventory.tagCounts') }),
	});
}

function freezeDataRecord(value, label) {
	return canonicalize(value, { errorCode: 'FACT_LIMIT', invalidCode: 'INVALID_FACTS', label, maxBytes: CANONICAL_LIMITS.stringBytes });
}

function hoistFunctionDeclarations(statements, environment) {
	for (const statement of statements) if (statement.type === 'FunctionDeclaration' && !environment.hasOwn(statement.id.name)) environment.define(statement.id.name, createFunction(statement, environment), 'const');
}

function normalizeBindings(bindings) {
	const root = exactOwnDataRecord(bindings, 'bindings', ['player'], { requireNullPrototype: true, requireFrozen: true, errorCode: 'INVALID_BINDINGS' });
	const player = ownDataEntries(root.player, 'bindings.player', { requireNullPrototype: true, requireFrozen: true, errorCode: 'INVALID_BINDINGS' });
	const normalizedPlayer = Object.create(null);
	for (const [name, binding] of player) {
		if (!['moveTo', 'wait'].includes(name)) throw executionError('INVALID_BINDINGS', 'ArenaScript INVALID_BINDINGS: unsupported player binding');
		const action = exactOwnDataRecord(binding, `bindings.player.${name}`, ['primitive'], { requireNullPrototype: true, requireFrozen: true, errorCode: 'INVALID_BINDINGS' });
		if (action.primitive !== PLAYER_PRIMITIVES[name]) throw executionError('INVALID_BINDINGS', 'ArenaScript INVALID_BINDINGS: player primitive does not match its capability');
		normalizedPlayer[name] = frozenRecord({ primitive: action.primitive });
	}
	return frozenRecord({ player: Object.freeze(normalizedPlayer) });
}

function exactOwnDataRecord(value, label, requiredKeys, options = {}) {
	const { errorCode = label.startsWith('facts') ? 'INVALID_FACTS' : 'INVALID_ACTION_RESULT' } = options;
	const entries = ownDataEntries(value, label, { ...options, errorCode });
	const values = Object.create(null);
	for (const [key, entry] of entries) values[key] = entry;
	if (entries.length !== requiredKeys.length || requiredKeys.some((key) => !Object.hasOwn(values, key))) throw executionError(errorCode, `ArenaScript ${errorCode}: ${label} has an invalid schema`);
	return values;
}

function ownDataEntries(value, label, { requireNullPrototype = false, requireFrozen = false, errorCode = label.startsWith('bindings') ? 'INVALID_BINDINGS' : 'INVALID_FACTS' } = {}) {
	if (value === null || typeof value !== 'object' || Array.isArray(value) || nodeTypes.isProxy(value)) throw executionError(errorCode, `ArenaScript ${errorCode}: ${label} must be a plain record`);
	if (requireNullPrototype ? Object.getPrototypeOf(value) !== null : ![null, Object.prototype].includes(Object.getPrototypeOf(value))) throw executionError(errorCode, `ArenaScript ${errorCode}: ${label} has an unsafe prototype`);
	if (requireFrozen && !Object.isFrozen(value)) throw executionError(errorCode, `ArenaScript ${errorCode}: ${label} must be frozen`);
	const keys = Reflect.ownKeys(value);
	if (keys.some((key) => typeof key !== 'string')) throw executionError(errorCode, `ArenaScript ${errorCode}: ${label} cannot use symbols`);
	const entries = [];
	for (const key of keys) {
		const descriptor = Object.getOwnPropertyDescriptor(value, key);
		if (!descriptor || !descriptor.enumerable || !Object.hasOwn(descriptor, 'value') || descriptor.get || descriptor.set) throw executionError(errorCode, `ArenaScript ${errorCode}: ${label}.${key} must be own data`);
		entries.push([key, descriptor.value]);
	}
	return entries;
}

function freezeOutput(value) {
	return canonicalize(value, { errorCode: 'OUTPUT_LIMIT', invalidCode: 'INVALID_COMMAND', label: 'command argument', maxBytes: CANONICAL_LIMITS.outputBytes, requireNullPrototype: true });
}

function frozenRecord(values) {
	const record = Object.create(null);
	for (const [key, value] of Object.entries(values)) record[key] = value;
	return Object.freeze(record);
}

function safePrimitive(value, code, label) {
	if (value === undefined || value === null || typeof value === 'string' || typeof value === 'boolean') return value;
	if (typeof value === 'number' && Number.isFinite(value)) return value;
	throw executionError(code, `ArenaScript ${code}: ${label} must be a safe data value`);
}

function canonicalize(value, { errorCode, invalidCode, label, maxBytes, requireNullPrototype = false }) {
	const state = { nodes: 0, keys: 0, bytes: 0, seen: new Set(), containers: [] };
	const root = createCanonicalNode(value, label, 0, state, { errorCode, invalidCode, maxBytes, requireNullPrototype });
	if (!root.container) return root.value;
	const stack = [root];
	while (stack.length > 0) {
		const current = stack.pop();
		if (current.depth > CANONICAL_LIMITS.depth) throw limitError(errorCode, 'depth');
		const entries = current.array ? arrayDataEntries(current.source, current.label, invalidCode) : ownDataEntries(current.source, current.label, { requireNullPrototype, errorCode: invalidCode });
		for (const [key, childValue] of entries) {
			if (FORBIDDEN_MEMBER_NAMES.has(key)) throw executionError(invalidCode, `ArenaScript ${invalidCode}: forbidden ${current.label} key`);
			state.keys += 1;
			addCanonicalBytes(key, state, maxBytes, errorCode);
			if (state.keys > CANONICAL_LIMITS.keys) throw limitError(errorCode, 'keys');
			const child = createCanonicalNode(childValue, `${current.label}.${key}`, current.depth + 1, state, { errorCode, invalidCode, maxBytes, requireNullPrototype });
			current.target[key] = child.value;
			if (child.container) stack.push(child);
		}
	}
	for (let index = state.containers.length - 1; index >= 0; index -= 1) Object.freeze(state.containers[index]);
	return root.value;
}

function createCanonicalNode(value, label, depth, state, options) {
	if (value === null || typeof value !== 'object') {
		if (typeof value === 'string') addCanonicalBytes(value, state, options.maxBytes, options.errorCode);
		try { return { value: safePrimitive(value, options.invalidCode, label), container: false }; } catch (error) { if (error instanceof ArenaScriptError) throw error; throw executionError(options.invalidCode, `ArenaScript ${options.invalidCode}: invalid ${label}`); }
	}
	if (nodeTypes.isProxy(value) || state.seen.has(value)) throw executionError(options.invalidCode, `ArenaScript ${options.invalidCode}: cyclic or proxy ${label}`);
	state.seen.add(value);
	state.nodes += 1;
	if (state.nodes > CANONICAL_LIMITS.nodes || depth > CANONICAL_LIMITS.depth) throw limitError(options.errorCode, 'nodes');
	if (Array.isArray(value)) {
		if (value.length > CANONICAL_LIMITS.arrayLength) throw limitError(options.errorCode, 'array length');
		const target = [];
		state.containers.push(target);
		return { value: target, source: value, target, label, depth, array: true, container: true };
	}
	const target = Object.create(null);
	state.containers.push(target);
	return { value: target, source: value, target, label, depth, array: false, container: true };
}

function arrayDataEntries(value, label, errorCode) {
	if (nodeTypes.isProxy(value) || value.length > CANONICAL_LIMITS.arrayLength) throw executionError(errorCode, `ArenaScript ${errorCode}: unsafe ${label}`);
	const descriptors = Object.getOwnPropertyDescriptors(value);
	const entries = [];
	for (let index = 0; index < value.length; index += 1) {
		const descriptor = descriptors[String(index)];
		if (!descriptor || !Object.hasOwn(descriptor, 'value')) throw executionError(errorCode, `ArenaScript ${errorCode}: unsafe ${label}`);
		entries.push([String(index), descriptor.value]);
	}
	if (Reflect.ownKeys(value).some((key) => typeof key === 'symbol' || (typeof key === 'string' && key !== 'length' && !/^\d+$/.test(key)))) throw executionError(errorCode, `ArenaScript ${errorCode}: unsafe ${label}`);
	return entries;
}

function addCanonicalBytes(value, state, maxBytes, errorCode) {
	const bytes = Buffer.byteLength(value, 'utf8');
	if (bytes > CANONICAL_LIMITS.stringBytes) throw limitError(errorCode, 'string bytes');
	state.bytes += bytes;
	if (state.bytes > maxBytes) throw limitError(errorCode, 'bytes');
}

function limitError(code, category) { return executionError(code, `ArenaScript ${code}: canonical ${category} limit exceeded`); }

function validateWatcherId(watcherId) {
	if (typeof watcherId !== 'string' || watcherId.length === 0 || Buffer.byteLength(watcherId, 'utf8') > CANONICAL_LIMITS.watcherIdBytes) throw executionError('INVALID_WATCHER_ID', 'ArenaScript INVALID_WATCHER_ID: watcher id must be a bounded string');
}

function terminalText(value, fallback, node) {
	if (value === undefined) return fallback;
	if (typeof value !== 'string') throw executionError('INVALID_TERMINAL_VALUE', 'ArenaScript INVALID_TERMINAL_VALUE: terminal values must be strings', node?.loc ? Object.freeze({ start: node.start, end: node.end, line: node.loc.start.line, column: node.loc.start.column }) : null);
	if (Buffer.byteLength(value, 'utf8') > CANONICAL_LIMITS.outputBytes) throw executionError('OUTPUT_LIMIT', 'ArenaScript OUTPUT_LIMIT: terminal text exceeds the output limit', node?.loc ? Object.freeze({ start: node.start, end: node.end, line: node.loc.start.line, column: node.loc.start.column }) : null);
	return value;
}
