import { parse as parseAcorn } from 'acorn';

import { ArenaScriptError } from './errors.mjs';
import {
	DEFAULT_ARENA_SCRIPT_LIMITS,
	normalizeArenaScriptLimits,
} from './limits.mjs';
import { SCRIPT_API_CALL_PATHS } from './minecraft-api.mjs';

const ALLOWED_GLOBALS = new Set([
	'program',
	'player',
	'world',
	'inventory',
	'tryResult',
	'undefined',
	'NaN',
	'Infinity',
]);

const FORBIDDEN_MEMBER_NAMES = new Set([
	'__defineGetter__',
	'__defineSetter__',
	'__lookupGetter__',
	'__lookupSetter__',
	'__proto__',
	'arguments',
	'callee',
	'caller',
	'constructor',
	'eval',
	'prototype',
]);

const UNHANDLED_POLICIES = new Set(['continue_and_notify', 'pause_and_notify']);
const SPECIAL_PROGRAM_MEMBER_PATHS = [
	['program', 'onUnhandledAttention'],
	['program', 'repeatUntil'],
	['program', 'watch'],
	['program', 'checkpoint'],
	['program', 'finish'],
];
const APPROVED_API_CALL_PATHS = new Set([
	'program.onUnhandledAttention',
	'program.repeatUntil',
	'program.watch',
	'program.checkpoint',
	'program.finish',
	...SCRIPT_API_CALL_PATHS,
]);
const APPROVED_BUILTIN_CALLS = new Set(['tryResult']);
const RESERVED_CAPABILITY_NAMES = new Set([...ALLOWED_GLOBALS, ...APPROVED_BUILTIN_CALLS]);
const MAX_ARENA_SCRIPT_AST_DEPTH = 256;

const ALLOWED_NODE_TYPES = new Set([
	'Program',
	'ExpressionStatement',
	'EmptyStatement',
	'BlockStatement',
	'VariableDeclaration',
	'VariableDeclarator',
	'Identifier',
	'Literal',
	'CallExpression',
	'MemberExpression',
	'AwaitExpression',
	'ArrowFunctionExpression',
	'FunctionExpression',
	'FunctionDeclaration',
	'IfStatement',
	'ForStatement',
	'WhileStatement',
	'DoWhileStatement',
	'ForInStatement',
	'ForOfStatement',
	'ReturnStatement',
	'BinaryExpression',
	'LogicalExpression',
	'UnaryExpression',
	'AssignmentExpression',
	'UpdateExpression',
	'ConditionalExpression',
	'ObjectExpression',
	'Property',
	'ArrayExpression',
	'BreakStatement',
	'ContinueStatement',
]);

const ASSIGNMENT_OPERATORS = new Set(['=', '+=', '-=', '*=', '/=', '%=', '**=', '&&=', '||=', '??=']);

/**
 * Parse and statically validate model-authored ArenaScript without executing it.
 */
export function parseArenaScript(source, { limits = DEFAULT_ARENA_SCRIPT_LIMITS } = {}) {
	if (typeof source !== 'string') {
		throw arenaError('SYNTAX_ERROR', 'source must be a string');
	}
	const normalizedLimits = normalizeArenaScriptLimits(limits);
	if (Buffer.byteLength(source, 'utf8') > normalizedLimits.sourceBytes) {
		throw arenaError('SOURCE_TOO_LARGE', `source exceeds ${normalizedLimits.sourceBytes} UTF-8 bytes`);
	}

	let ast;
	try {
		ast = parseAcorn(source, {
			ecmaVersion: 2024,
			sourceType: 'script',
			locations: true,
			allowAwaitOutsideFunction: true,
		});
	} catch (error) {
		throw new ArenaScriptError(
			'SYNTAX_ERROR',
			`ArenaScript syntax error: ${error.message}`,
			parseErrorLocation(error),
			{ cause: error },
		);
	}

	validateAstShape(ast, normalizedLimits);
	const analysis = validateProgram(ast, normalizedLimits);
	return deepFreeze({ source, ast, ...analysis });
}

function validateProgram(ast, limits) {
	const state = {
		limits,
		nodeCount: 0,
		stepLocations: new Map(),
		policyCount: 0,
		unhandledPolicy: null,
		watcherCount: 0,
		userDeclarations: new Set(),
		functionBindingsByNode: new WeakMap(),
		functionBindings: new Set(),
		functionEdges: new Map(),
	};

	collectDeclarations(ast, state);
	const rootScope = createLexicalScope(null, ast.body, state);
	visit(ast, state, { functionBinding: null, scope: rootScope, topLevelExpression: false });

	if (state.policyCount === 0) {
		throw arenaError('MISSING_UNHANDLED_POLICY', 'exactly one top-level program.onUnhandledAttention policy is required');
	}
	detectRecursion(state);

	return {
		nodeCount: state.nodeCount,
		stepLocations: createFrozenMap(state.stepLocations),
		unhandledPolicy: state.unhandledPolicy,
		watcherCount: state.watcherCount,
	};
}

function visit(node, state, context) {
	if (node === null || node === undefined) return;
	if (Array.isArray(node)) {
		for (const child of node) visit(child, state, context);
		return;
	}
	if (typeof node !== 'object' || typeof node.type !== 'string') return;

	state.nodeCount += 1;
	if (state.nodeCount > state.limits.astNodes) {
		throw arenaError('AST_TOO_LARGE', `syntax tree exceeds ${state.limits.astNodes} nodes`, node);
	}
	if (!ALLOWED_NODE_TYPES.has(node.type)) {
		throw arenaError('UNSUPPORTED_SYNTAX', `syntax node ${node.type} is not allowed`, node);
	}
	if (hasLocation(node)) {
		state.stepLocations.set(`step-${node.start}-${node.end}`, locationFromNode(node));
	}

	switch (node.type) {
		case 'Program':
			for (const statement of node.body) {
				visit(statement, state, { ...context, functionBinding: null, topLevelExpression: true });
			}
			return;
		case 'ExpressionStatement':
			visit(node.expression, state, { ...context, topLevelExpression: context.topLevelExpression });
			return;
		case 'EmptyStatement':
			return;
		case 'BlockStatement':
			visit(node.body, state, { ...context, scope: createLexicalScope(context.scope, node.body, state), topLevelExpression: false });
			return;
		case 'VariableDeclaration':
			if (node.kind !== 'const' && node.kind !== 'let') {
				throw arenaError('UNSUPPORTED_SYNTAX', 'only const and let declarations are allowed', node);
			}
			visit(node.declarations, state, { ...context, topLevelExpression: false });
			return;
		case 'VariableDeclarator':
			validateBindingPattern(node.id);
			visit(node.id, state, { ...context, binding: true, topLevelExpression: false });
			visit(node.init, state, { ...context, topLevelExpression: false });
			return;
		case 'Identifier':
			if (!context.binding && ['program', 'player', 'world', 'inventory'].includes(node.name) && !context.capabilityMemberObject) {
				throw arenaError('UNSUPPORTED_SYNTAX', `Arena capability ${node.name} may only be used through an approved direct call`, node);
			}
			if (!context.binding && !context.property && !ALLOWED_GLOBALS.has(node.name) && !state.userDeclarations.has(node.name)) {
				throw arenaError('UNSAFE_MEMBER_ACCESS', `identifier ${node.name} is outside the ArenaScript environment`, node);
			}
			return;
		case 'Literal':
			if (node.regex || typeof node.value === 'bigint' || (typeof node.value === 'number' && !Number.isFinite(node.value))) {
				throw arenaError('UNSUPPORTED_SYNTAX', 'regex, bigint, and non-finite literals are not allowed', node);
			}
			return;
		case 'CallExpression':
			validateCallExpression(node, state, context);
			visit(node.callee, state, { ...context, directCallCallee: node.callee, topLevelExpression: false });
			visit(node.arguments, state, { ...context, topLevelExpression: false });
			return;
		case 'MemberExpression':
			validateMemberExpression(node, state, context);
			visit(node.object, state, { ...context, capabilityMemberObject: true, topLevelExpression: false });
			visit(node.property, state, { ...context, property: true, topLevelExpression: false });
			return;
		case 'AwaitExpression':
			visit(node.argument, state, { ...context, topLevelExpression: false });
			return;
		case 'ArrowFunctionExpression':
		case 'FunctionExpression':
		case 'FunctionDeclaration': {
			if (node.generator) {
				throw arenaError('UNSUPPORTED_SYNTAX', 'generator functions are not allowed', node);
			}
			if (node.id) {
				validateBindingPattern(node.id);
				visit(node.id, state, { ...context, binding: true, topLevelExpression: false });
			}
			for (const parameter of node.params) {
				validateBindingPattern(parameter);
				visit(parameter, state, { ...context, binding: true, topLevelExpression: false });
			}
			const functionBinding = state.functionBindingsByNode.get(node) ?? context.functionBinding;
			const parameterScope = createParameterScope(context.scope, node, functionBinding);
			visit(node.body, state, {
				functionBinding,
				scope: parameterScope,
				topLevelExpression: false,
			});
			return;
		}
		case 'IfStatement':
			if (node.consequent?.type === 'FunctionDeclaration' || node.alternate?.type === 'FunctionDeclaration') {
				throw arenaError('UNSUPPORTED_SYNTAX', 'blockless conditional function declarations are not allowed', node);
			}
			visit(node.test, state, { ...context, topLevelExpression: false });
			visit(node.consequent, state, { ...context, topLevelExpression: false });
			visit(node.alternate, state, { ...context, topLevelExpression: false });
			return;
		case 'ForStatement':
			validateForStatement(node, state);
			visit(node.init, state, { ...context, topLevelExpression: false });
			visit(node.test, state, { ...context, topLevelExpression: false });
			visit(node.update, state, { ...context, topLevelExpression: false });
			visit(node.body, state, { ...context, topLevelExpression: false });
			return;
		case 'WhileStatement':
		case 'DoWhileStatement':
		case 'ForInStatement':
		case 'ForOfStatement':
			throw arenaError('UNBOUNDED_LOOP', `${node.type} does not have a literal static bound`, node);
		case 'ReturnStatement':
			visit(node.argument, state, { ...context, topLevelExpression: false });
			return;
		case 'BinaryExpression':
		case 'LogicalExpression':
			visit(node.left, state, { ...context, topLevelExpression: false });
			visit(node.right, state, { ...context, topLevelExpression: false });
			return;
		case 'UnaryExpression':
			if (!new Set(['!', '+', '-', '~']).has(node.operator)) {
				throw arenaError('UNSUPPORTED_SYNTAX', `unary operator ${node.operator} is not allowed`, node);
			}
			visit(node.argument, state, { ...context, topLevelExpression: false });
			return;
		case 'AssignmentExpression':
			if (!ASSIGNMENT_OPERATORS.has(node.operator)) {
				throw arenaError('UNSUPPORTED_SYNTAX', `assignment operator ${node.operator} is not allowed`, node);
			}
			validateLocalAssignmentTarget(node.left, state, node.right, context);
			visit(node.left, state, { ...context, topLevelExpression: false });
			visit(node.right, state, { ...context, topLevelExpression: false });
			return;
		case 'UpdateExpression':
			if (!['++', '--'].includes(node.operator)) {
				throw arenaError('UNSUPPORTED_SYNTAX', `update operator ${node.operator} is not allowed`, node);
			}
			validateLocalAssignmentTarget(node.argument, state, null, context);
			visit(node.argument, state, { ...context, topLevelExpression: false });
			return;
		case 'ConditionalExpression':
			visit(node.test, state, { ...context, topLevelExpression: false });
			visit(node.consequent, state, { ...context, topLevelExpression: false });
			visit(node.alternate, state, { ...context, topLevelExpression: false });
			return;
		case 'ObjectExpression':
			visit(node.properties, state, { ...context, topLevelExpression: false });
			return;
		case 'Property':
			if (node.kind !== 'init' || node.method || node.computed) {
				throw arenaError('UNSUPPORTED_SYNTAX', 'only plain object properties are allowed', node);
			}
			if (FORBIDDEN_MEMBER_NAMES.has(propertyName(node.key))) {
				throw arenaError('UNSAFE_MEMBER_ACCESS', 'object properties cannot use forbidden member names', node.key);
			}
			visit(node.key, state, { ...context, property: true, topLevelExpression: false });
			visit(node.value, state, { ...context, topLevelExpression: false });
			return;
		case 'ArrayExpression':
			visit(node.elements, state, { ...context, topLevelExpression: false });
			return;
		case 'BreakStatement':
		case 'ContinueStatement':
			return;
		default:
			throw arenaError('UNSUPPORTED_SYNTAX', `syntax node ${node.type} is not allowed`, node);
	}
}

function validateCallExpression(node, state, context) {
	if (node.optional) {
		throw arenaError('UNSUPPORTED_SYNTAX', 'optional calls are not allowed', node);
	}
	const path = staticMemberPath(node.callee);
	let functionBinding = null;
	if (node.callee.type === 'Identifier') {
		functionBinding = resolveBinding(context.scope, node.callee.name);
		if (APPROVED_BUILTIN_CALLS.has(node.callee.name) && functionBinding) {
			throw arenaError('UNSUPPORTED_SYNTAX', `approved built-in ${node.callee.name} cannot resolve to a local binding`, node.callee);
		}
		if (!functionBinding?.callable && !APPROVED_BUILTIN_CALLS.has(node.callee.name)) {
			throw arenaError('UNSUPPORTED_SYNTAX', 'calls must target an immutable local function or approved built-in', node.callee);
		}
	} else if (node.callee.type === 'MemberExpression') {
		if (node.callee.computed || node.callee.optional) {
			throw arenaError('UNSAFE_MEMBER_ACCESS', 'computed member call targets are not allowed', node.callee);
		}
		if (path?.[0] && !ALLOWED_GLOBALS.has(path[0])) {
			throw arenaError(state.userDeclarations.has(path[0]) ? 'UNSUPPORTED_SYNTAX' : 'UNSAFE_MEMBER_ACCESS', 'unapproved member call targets are not allowed', node.callee);
		}
		if (path?.[0] && resolveBinding(context.scope, path[0])) {
			throw arenaError('UNSUPPORTED_SYNTAX', `approved Arena API root ${path[0]} cannot resolve to a local binding`, node.callee);
		}
		if (!path || !APPROVED_API_CALL_PATHS.has(path.join('.'))) {
			throw arenaError('UNSUPPORTED_SYNTAX', 'calls must target an approved Arena API member path', node.callee);
		}
	} else {
		throw arenaError('UNSUPPORTED_SYNTAX', 'calls must use an identifier or approved Arena API member path', node.callee);
	}

	if (pathEqual(path, ['program', 'onUnhandledAttention'])) {
		if (!context.topLevelExpression) {
			throw arenaError('UNSUPPORTED_SYNTAX', 'onUnhandledAttention must be declared exactly once at program top level', node);
		}
		state.policyCount += 1;
		if (state.policyCount > 1) {
			throw arenaError('UNSUPPORTED_SYNTAX', 'exactly one top-level onUnhandledAttention policy is required', node);
		}
		if (node.arguments.length !== 1 || node.arguments[0]?.type !== 'Literal' || typeof node.arguments[0].value !== 'string' || !UNHANDLED_POLICIES.has(node.arguments[0].value)) {
			throw arenaError('UNSUPPORTED_SYNTAX', 'onUnhandledAttention requires one supported literal policy', node);
		}
		state.unhandledPolicy = node.arguments[0].value;
	}

	if (pathEqual(path, ['program', 'repeatUntil'])) {
		validateRepeatUntil(node, state);
	}
	if (pathEqual(path, ['program', 'watch'])) {
		validateWatcher(node, state, context);
	}
	if (functionBinding && context.functionBinding) {
		let edges = state.functionEdges.get(context.functionBinding);
			if (!edges) {
				edges = new Map();
				state.functionEdges.set(context.functionBinding, edges);
			}
		edges.set(functionBinding, node);
	}
}

function validateMemberExpression(node, state, context) {
	if (node.computed || node.optional) {
		throw arenaError('UNSAFE_MEMBER_ACCESS', 'computed and optional member access is not allowed', node);
	}
	if (isSpecialProgramMember(node) && context.directCallCallee !== node) {
		throw arenaError('UNSUPPORTED_SYNTAX', 'special program APIs must be called directly', node);
	}
	const memberName = propertyName(node.property);
	if (memberName === null || FORBIDDEN_MEMBER_NAMES.has(memberName)) {
		throw arenaError('UNSAFE_MEMBER_ACCESS', `member ${memberName ?? '<unknown>'} is not allowed`, node);
	}
	if (memberRoot(node) && ALLOWED_GLOBALS.has(memberRoot(node)) && context.directCallCallee !== node) {
		throw arenaError('UNSUPPORTED_SYNTAX', 'Arena capability members may only be used as approved direct call targets', node);
	}
	const root = memberRoot(node);
	if (root && !ALLOWED_GLOBALS.has(root) && !state.userDeclarations.has(root)) {
		throw arenaError('UNSAFE_MEMBER_ACCESS', `member root ${root} is outside the ArenaScript environment`, node);
	}
}

function validateRepeatUntil(node, state) {
	if (node.arguments.length !== 3) {
		throw arenaError('UNBOUNDED_LOOP', 'program.repeatUntil requires condition, literal maxIterations options, and body', node);
	}
	const maxIterations = literalObjectProperty(node.arguments[1], 'maxIterations', 'UNBOUNDED_LOOP');
	if (!Number.isSafeInteger(maxIterations) || maxIterations <= 0) {
		throw arenaError('UNBOUNDED_LOOP', 'repeatUntil maxIterations must be a positive integer literal', node.arguments[1]);
	}
	if (!isFunctionNode(node.arguments[0]) || !isFunctionNode(node.arguments[2])) {
		throw arenaError('UNSUPPORTED_SYNTAX', 'repeatUntil condition and body must be functions', node);
	}
	validatePureCondition(node.arguments[0]);
}

function validateWatcher(node, state, context) {
	if (!context.topLevelExpression) {
		throw arenaError('UNSUPPORTED_SYNTAX', 'program.watch must be declared exactly once at program top level', node);
	}
	state.watcherCount += 1;
	if (state.watcherCount > state.limits.watchers) {
		throw arenaError('TOO_MANY_WATCHERS', `program contains more than ${state.limits.watchers} watchers`, node);
	}
	if (node.arguments.length !== 3 || !isFunctionNode(node.arguments[0]) || !isFunctionNode(node.arguments[2])) {
		throw arenaError('UNSUPPORTED_SYNTAX', 'program.watch requires condition, options, and handler functions', node);
	}
	const mode = literalObjectProperty(node.arguments[1], 'mode', 'UNSUPPORTED_SYNTAX');
	if (!['boundary', 'interrupt'].includes(mode)) {
		throw arenaError('UNSUPPORTED_SYNTAX', 'watcher mode must be the boundary or interrupt literal', node.arguments[1]);
	}
	validatePureCondition(node.arguments[0]);
}

function validatePureCondition(node) {
	if (node.async) throw arenaError('UNSUPPORTED_SYNTAX', 'watcher and repeatUntil conditions cannot be async', node);
	const stack = [node];
	while (stack.length > 0) {
		const current = stack.pop();
		if (!current || typeof current !== 'object') continue;
		if (Array.isArray(current)) {
			for (const child of current) stack.push(child);
			continue;
		}
		if (current.type === 'AwaitExpression' || current.type === 'AssignmentExpression' || current.type === 'UpdateExpression') {
			throw arenaError('UNSUPPORTED_SYNTAX', 'watcher and repeatUntil conditions must be factual and side-effect free', current);
		}
		if (isFunctionNode(current) && current !== node && current.async) {
			throw arenaError('UNSUPPORTED_SYNTAX', 'watcher and repeatUntil conditions cannot contain async callbacks', current);
		}
		if (current.type === 'CallExpression') {
			const path = staticMemberPath(current.callee)?.join('.');
			if (!['player.state', 'inventory.count', 'inventory.countTag'].includes(path)) {
				throw arenaError('UNSUPPORTED_SYNTAX', 'watcher and repeatUntil conditions can call only factual Arena APIs', current);
			}
		}
		if (current.type === 'MemberExpression') {
			const path = staticMemberPath(current)?.join('.');
			if ([...SCRIPT_API_CALL_PATHS].filter((apiPath) => !['player.state', 'inventory.count', 'inventory.countTag'].includes(apiPath)).includes(path)
				|| ['program.checkpoint', 'program.finish', 'program.watch', 'program.repeatUntil'].includes(path)) {
				throw arenaError('UNSUPPORTED_SYNTAX', 'watcher and repeatUntil conditions cannot reference effectful Arena APIs', current);
			}
		}
		for (const [key, value] of Object.entries(current)) {
			if (key !== 'loc' && key !== 'start' && key !== 'end' && key !== 'type') stack.push(value);
		}
	}
}

function validateForStatement(node, state) {
	if (node.init?.type !== 'VariableDeclaration' || node.init.kind !== 'let' || node.init.declarations.length !== 1) {
		throw arenaError('UNBOUNDED_LOOP', 'for loops require one let counter initialized with a numeric literal', node);
	}
	const declaration = node.init.declarations[0];
	if (declaration.id.type !== 'Identifier' || !isSafeIntegerLiteral(declaration.init)) {
		throw arenaError('UNBOUNDED_LOOP', 'for loop counter must have a numeric literal initializer', node.init);
	}
	if (node.test?.type !== 'BinaryExpression' || !['<', '<=', '>', '>='].includes(node.test.operator) || node.test.left.type !== 'Identifier' || node.test.right.type !== 'Literal' || !isSafeIntegerLiteral(node.test.right)) {
		throw arenaError('UNBOUNDED_LOOP', 'for loop test must compare its counter with a numeric literal', node.test ?? node);
	}
	const counterName = declaration.id.name;
	if (node.test.left.name !== counterName) {
		throw arenaError('UNBOUNDED_LOOP', 'for loop test must use its declared counter', node.test);
	}
	const delta = loopDelta(node.update, counterName);
	if (delta === null || delta === 0) {
		throw arenaError('UNBOUNDED_LOOP', 'for loop update must move its counter by a non-zero literal step', node.update ?? node);
	}
	const ascending = node.test.operator === '<' || node.test.operator === '<=';
	if ((ascending && delta < 0) || (!ascending && delta > 0)) {
		throw arenaError('UNBOUNDED_LOOP', 'for loop counter moves away from its literal bound', node);
	}
	const iterations = forLoopIterations(declaration.init.value, node.test.right.value, delta, node.test.operator);
	if (iterations > BigInt(state.limits.loopIterationsPerYield)) {
		throw arenaError('UNBOUNDED_LOOP', `for loop exceeds ${state.limits.loopIterationsPerYield} literal iterations`, node);
	}
	if (containsCounterMutation(node.body, counterName)) {
		throw arenaError('UNBOUNDED_LOOP', 'for loop body cannot mutate its counter', node.body);
	}
}

function loopDelta(update, counterName) {
	if (update?.type === 'UpdateExpression' && update.argument.type === 'Identifier' && update.argument.name === counterName && update.operator === '++') return 1;
	if (update?.type === 'UpdateExpression' && update.argument.type === 'Identifier' && update.argument.name === counterName && update.operator === '--') return -1;
	if (update?.type === 'AssignmentExpression' && update.left.type === 'Identifier' && update.left.name === counterName && (update.operator === '+=' || update.operator === '-=') && isSafeIntegerLiteral(update.right)) {
		const value = update.right.value;
		return update.operator === '+=' ? value : -value;
	}
	return null;
}

function forLoopIterations(initialValue, boundValue, deltaValue, operator) {
	const initial = BigInt(initialValue);
	const bound = BigInt(boundValue);
	const delta = BigInt(deltaValue);
	if (operator === '<') return initial >= bound ? 0n : divideCeiling(bound - initial, delta);
	if (operator === '<=') return initial > bound ? 0n : ((bound - initial) / delta) + 1n;
	const magnitude = -delta;
	if (operator === '>') return initial <= bound ? 0n : divideCeiling(initial - bound, magnitude);
	return initial < bound ? 0n : ((initial - bound) / magnitude) + 1n;
}

function divideCeiling(dividend, divisor) {
	return (dividend + divisor - 1n) / divisor;
}

function containsCounterMutation(node, counterName) {
	if (!node || typeof node !== 'object') return false;
	if (Array.isArray(node)) return node.some((child) => containsCounterMutation(child, counterName));
	if (node.type === 'AssignmentExpression' && node.left.type === 'Identifier' && node.left.name === counterName) return true;
	if (node.type === 'UpdateExpression' && node.argument.type === 'Identifier' && node.argument.name === counterName) return true;
	for (const [key, value] of Object.entries(node)) {
		if (key === 'loc' || key === 'start' || key === 'end' || key === 'type') continue;
		if (containsCounterMutation(value, counterName)) return true;
	}
	return false;
}

function validateLocalAssignmentTarget(node, state, value, context) {
	if (node.type !== 'Identifier' || !state.userDeclarations.has(node.name)) {
		throw arenaError('UNSAFE_MEMBER_ACCESS', 'only local variables may be assigned or updated', node);
	}
	if (resolveBinding(context.scope, node.name)?.callable) {
		throw arenaError('UNSUPPORTED_SYNTAX', 'callable local bindings cannot be reassigned or updated', node);
	}
	if (isFunctionNode(value) || (value?.type === 'Identifier' && resolveBinding(context.scope, value.name)?.callable)) {
		throw arenaError('UNSUPPORTED_SYNTAX', 'callable values cannot be assigned to local bindings', value);
	}
}

function createLexicalScope(parent, statements, state) {
	const scope = { parent, bindings: new Map() };
	for (const statement of statements) {
		if (statement?.type === 'FunctionDeclaration' && statement.id?.type === 'Identifier') {
			rejectReservedBinding(statement.id.name, statement.id);
			registerFunctionBinding(scope, statement.id.name, statement, state);
			continue;
		}
		if (statement?.type !== 'VariableDeclaration') continue;
		for (const declaration of statement.declarations) {
			if (declaration.id?.type !== 'Identifier') continue;
			rejectReservedBinding(declaration.id.name, declaration.id);
			if (statement.kind === 'const' && isFunctionNode(declaration.init)) {
				registerFunctionBinding(scope, declaration.id.name, declaration.init, state);
			} else {
				scope.bindings.set(declaration.id.name, Object.freeze({ callable: false, name: declaration.id.name }));
			}
		}
	}
	return scope;
}

function createParameterScope(parent, node, functionBinding) {
	const scope = { parent, bindings: new Map() };
	for (const parameter of node.params) {
		if (parameter.type === 'Identifier') {
			rejectReservedBinding(parameter.name, parameter);
			scope.bindings.set(parameter.name, Object.freeze({ callable: false, name: parameter.name }));
		}
	}
	if (node.id?.type === 'Identifier' && functionBinding) {
		rejectReservedBinding(node.id.name, node.id);
		scope.bindings.set(node.id.name, functionBinding);
	}
	return scope;
}

function registerFunctionBinding(scope, name, functionNode, state) {
	const binding = Object.freeze({ callable: true, name, functionNode });
	scope.bindings.set(name, binding);
	state.functionBindingsByNode.set(functionNode, binding);
	state.functionBindings.add(binding);
}

function resolveBinding(scope, name) {
	for (let current = scope; current; current = current.parent) {
		const binding = current.bindings.get(name);
		if (binding) return binding;
	}
	return null;
}

function rejectReservedBinding(name, node) {
	if (RESERVED_CAPABILITY_NAMES.has(name)) {
		throw arenaError('UNSUPPORTED_SYNTAX', `reserved Arena capability name ${name} cannot be shadowed`, node);
	}
}

function validateBindingPattern(pattern) {
	if (!pattern || typeof pattern !== 'object') return;
	if (pattern.type === 'Identifier') {
		rejectReservedBinding(pattern.name, pattern);
		return;
	}
	for (const value of Object.values(pattern)) {
		if (Array.isArray(value)) {
			for (const child of value) validateBindingPattern(child);
		} else if (value && typeof value === 'object' && typeof value.type === 'string') {
			validateBindingPattern(value);
		}
	}
}

function collectDeclarations(node, state) {
	if (!node || typeof node !== 'object') return;
	if (Array.isArray(node)) {
		for (const child of node) collectDeclarations(child, state);
		return;
	}
	switch (node.type) {
		case 'FunctionDeclaration':
			if (node.id) {
				state.userDeclarations.add(node.id.name);
			}
			for (const parameter of node.params) collectPatternNames(parameter, state);
			collectDeclarations(node.body, state);
			return;
		case 'FunctionExpression':
		case 'ArrowFunctionExpression':
			if (node.id) {
				state.userDeclarations.add(node.id.name);
			}
			for (const parameter of node.params) collectPatternNames(parameter, state);
			collectDeclarations(node.body, state);
			return;
		case 'VariableDeclaration':
			for (const declaration of node.declarations) {
				collectPatternNames(declaration.id, state);
				collectDeclarations(declaration.init, state);
			}
			return;
		default:
			for (const [key, value] of Object.entries(node)) {
				if (key === 'loc' || key === 'start' || key === 'end' || key === 'type') continue;
				collectDeclarations(value, state);
			}
	}
}

function collectPatternNames(pattern, state) {
	if (!pattern || typeof pattern !== 'object') return;
	if (Array.isArray(pattern)) {
		for (const child of pattern) collectPatternNames(child, state);
		return;
	}
	if (pattern.type === 'Identifier') {
		state.userDeclarations.add(pattern.name);
		return;
	}
	for (const [key, value] of Object.entries(pattern)) {
		if (key !== 'loc' && key !== 'start' && key !== 'end' && key !== 'type') collectPatternNames(value, state);
	}
}

function detectRecursion(state) {
	const colors = new Map();
	const stack = [];
	const visitFunction = (binding) => {
		const color = colors.get(binding);
		if (color === 'active') {
			const node = stack.at(-1)?.node ?? null;
			throw arenaError('RECURSION_FORBIDDEN', `local function call graph contains recursion through ${binding.name}`, node);
		}
		if (color === 'done') return;
		colors.set(binding, 'active');
		const edges = state.functionEdges.get(binding) ?? new Map();
		for (const [callee, node] of edges) {
			stack.push({ binding, node });
			visitFunction(callee);
			stack.pop();
		}
		colors.set(binding, 'done');
	};
	for (const binding of state.functionBindings) visitFunction(binding);
}

function literalObjectProperty(node, expectedName, errorCode) {
	if (!node || node.type !== 'ObjectExpression') return null;
	const names = new Set();
	let expectedValue = null;
	for (const property of node.properties) {
		if (property?.type !== 'Property' || property.kind !== 'init' || property.method || property.computed) {
			throw arenaError(errorCode, 'options must contain only plain literal properties', property ?? node);
		}
		const name = propertyName(property.key);
		if (name === null || FORBIDDEN_MEMBER_NAMES.has(name) || names.has(name)) {
			throw arenaError(errorCode, 'options cannot contain duplicate or forbidden property keys', property);
		}
		names.add(name);
		if (name !== expectedName || property.value.type !== 'Literal') {
			throw arenaError(errorCode, `options must contain exactly one literal ${expectedName} property`, property);
		}
		expectedValue = property.value.value;
	}
	return names.size === 1 && names.has(expectedName) ? expectedValue : null;
}

function isFunctionNode(node) {
	return node?.type === 'ArrowFunctionExpression' || node?.type === 'FunctionExpression' || node?.type === 'FunctionDeclaration';
}

function isSafeIntegerLiteral(node) {
	return node?.type === 'Literal' && typeof node.value === 'number' && Number.isSafeInteger(node.value);
}

function isSpecialProgramMember(node) {
	const path = staticMemberPath(node);
	return SPECIAL_PROGRAM_MEMBER_PATHS.some((specialPath) => pathEqual(path, specialPath));
}

function staticMemberPath(node) {
	const parts = [];
	let current = node;
	while (current?.type === 'MemberExpression') {
		if (current.computed || current.optional) return null;
		const name = propertyName(current.property);
		if (name === null) return null;
		parts.unshift(name);
		current = current.object;
	}
	if (current?.type !== 'Identifier') return null;
	parts.unshift(current.name);
	return parts;
}

function memberRoot(node) {
	let current = node;
	while (current?.type === 'MemberExpression') current = current.object;
	return current?.type === 'Identifier' ? current.name : null;
}

function propertyName(node) {
	if (node?.type === 'Identifier') return node.name;
	if (node?.type === 'Literal' && typeof node.value === 'string') return node.value;
	return null;
}

function pathEqual(actual, expected) {
	return actual?.length === expected.length && actual.every((part, index) => part === expected[index]);
}

function hasLocation(node) {
	return Number.isInteger(node?.start) && Number.isInteger(node?.end) && node.loc?.start;
}

function locationFromNode(node) {
	return Object.freeze({
		start: node.start,
		end: node.end,
		line: node.loc.start.line,
		column: node.loc.start.column,
	});
}

function parseErrorLocation(error) {
	if (!error?.loc) return null;
	return Object.freeze({
		start: Number.isInteger(error.pos) ? error.pos : null,
		end: Number.isInteger(error.pos) ? error.pos : null,
		line: error.loc.line,
		column: error.loc.column,
	});
}

function validateAstShape(ast, limits) {
	const stack = [{ node: ast, depth: 1 }];
	let nodeCount = 0;
	while (stack.length > 0) {
		const { node, depth } = stack.pop();
		if (!node || typeof node !== 'object') continue;
		if (Array.isArray(node)) {
			for (const child of node) stack.push({ node: child, depth });
			continue;
		}
		if (typeof node.type !== 'string') continue;
		nodeCount += 1;
		if (nodeCount > limits.astNodes || depth > MAX_ARENA_SCRIPT_AST_DEPTH) {
			throw arenaError('AST_TOO_LARGE', `syntax tree exceeds parser limits of ${limits.astNodes} nodes and ${MAX_ARENA_SCRIPT_AST_DEPTH} depth`, node);
		}
		for (const [key, child] of Object.entries(node)) {
			if (key !== 'loc' && key !== 'start' && key !== 'end' && key !== 'type') {
				stack.push({ node: child, depth: depth + 1 });
			}
		}
	}
}

function arenaError(code, message, node = null) {
	return new ArenaScriptError(code, `ArenaScript ${code}: ${message}`, node && hasLocation(node) ? locationFromNode(node) : null);
}

function createFrozenMap(map) {
	const locations = new Map();
	for (const [key, value] of map) locations.set(key, Object.freeze({ ...value }));
	const readonly = {
		get size() {
			return locations.size;
		},
		get(key) {
			return locations.get(key);
		},
		has(key) {
			return locations.has(key);
		},
		entries() {
			return locations.entries();
		},
		keys() {
			return locations.keys();
		},
		values() {
			return locations.values();
		},
		forEach(callback, thisArg = undefined) {
			locations.forEach((value, key) => callback.call(thisArg, value, key, readonly));
		},
		[Symbol.iterator]() {
			return locations.entries();
		},
	};
	return Object.freeze(readonly);
}

function deepFreeze(value, seen = new Set()) {
	if (value === null || (typeof value !== 'object' && typeof value !== 'function') || seen.has(value)) return value;
	seen.add(value);
	if (value instanceof Map) {
		for (const [key, entry] of value) {
			deepFreeze(key, seen);
			deepFreeze(entry, seen);
		}
		return Object.freeze(value);
	}
	for (const key of Reflect.ownKeys(value)) deepFreeze(value[key], seen);
	return Object.freeze(value);
}
