import { parse as parseAcorn } from 'acorn';

import { ArenaScriptError } from './errors.mjs';
import {
	DEFAULT_ARENA_SCRIPT_LIMITS,
	normalizeArenaScriptLimits,
} from './limits.mjs';
import { PLAYER_MEMBER_PRIMITIVES, SCRIPT_API_CALL_PATHS, PURE_API_PATHS } from './minecraft-api.mjs';
import { ACTION_FIELDS, OPTIONAL_ACTION_FIELDS } from '../constants.mjs';
import { FACT_DOMAIN } from './fact-domains.mjs';

const PLAYER_MEMBERS_BY_ACTION_TYPE = new Map(Object.entries(PLAYER_MEMBER_PRIMITIVES).map(([member, primitive]) => [primitive, member]));
PLAYER_MEMBERS_BY_ACTION_TYPE.set('mine', 'mine');
const ACTION_TOOL_DEFAULTS = Object.freeze({
	break_block: Object.freeze({ timeoutMs: 15_000 }),
	move_to: Object.freeze({ tolerance: 1, sprint: true }),
	navigate_to: Object.freeze({ tolerance: 1, sprint: true, timeoutMs: 30_000 }),
});

const ALLOWED_GLOBALS = new Set([
	'program',
	'player',
	'world',
	'inventory',
	'math',
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
	['program', 'parameters'],
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

/** Compile an authored expression as a no-action watcher using the same pure API checks. */
export function parseArenaScriptPrecondition(source) {
	if (typeof source !== 'string' || source.trim().length === 0 || Buffer.byteLength(source, 'utf8') > 4096) {
		throw arenaError('UNSUPPORTED_SYNTAX', 'precondition must be a nonblank expression within 4096 UTF-8 bytes');
	}
	const options = { ecmaVersion: 2024, sourceType: 'script', locations: true };
	let expression;
	try { expression = parseAcorn(source, options); }
	catch (error) { throw new ArenaScriptError('SYNTAX_ERROR', `ArenaScript syntax error: ${error.message}`, parseErrorLocation(error), { cause: error }); }
	if (expression.body.length !== 1 || expression.body[0].type !== 'ExpressionStatement') {
		throw arenaError('UNSUPPORTED_SYNTAX', 'precondition must contain exactly one expression');
	}
	// Source data is never interpolated into executable text. Only the expression
	// AST enters this fixed, effect-free program before normal static validation.
	const ast = parseAcorn('program.onUnhandledAttention("continue_and_notify"); program.watch(() => true, { mode: "boundary" }, async () => {});', options);
	const authored = expression.body[0].expression;
	const condition = ast.body[1].expression.arguments[0];
	// Queue authorization requires the exact boolean true. The ordinary watcher
	// evaluator still uses its existing truthiness rules for authored watchers.
	condition.body = { type: 'BinaryExpression', operator: '===', left: authored, right: condition.body,
		start: authored.start, end: authored.end, loc: authored.loc };
	validateAstShape(ast, DEFAULT_ARENA_SCRIPT_LIMITS);
	const analysis = validateProgram(ast, DEFAULT_ARENA_SCRIPT_LIMITS);
	return deepFreeze({ source, ast, ...analysis });
}

function validateProgram(ast, limits) {
	const state = {
		limits,
		nodeCount: 0,
		stepLocations: new Map(),
		policyCount: 0,
		actionToolAliasCount: 0,
		unhandledPolicy: null,
		survivalPolicy: 'pause_and_notify',
		hasReassessmentCondition: false,
		watcherCount: 0,
		watchers: [],
		primitiveCalls: new Set(),
		functionBindingsByNode: new WeakMap(),
		functionBindings: new Set(),
		functionEdges: new Map(),
		functionDependencies: new Map(),
		functionCalls: [],
		watcherActivationNode: watcherActivationNode(ast),
	};

	rewriteActionToolAliases(ast, state);
	validateWatcherPrologue(ast);
	const rootScope = createLexicalScope(null, ast.body, state);
	visit(ast, state, { functionBinding: null, functionNode: null, scope: rootScope, topLevelExpression: false, inFunction: false });
	if (state.policyCount === 0 && state.actionToolAliasCount === 0) {
		throw arenaError('MISSING_UNHANDLED_POLICY', 'add one top-level program.onUnhandledAttention("pause_and_notify") policy');
	}

	detectRecursion(state);
	validateFunctionCallDependencies(state);

	return {
		nodeCount: state.nodeCount,
		stepLocations: createFrozenMap(state.stepLocations),
		// Tool-shaped source with no explicit policy inherits the existing safest policy.
		unhandledPolicy: state.unhandledPolicy ?? 'pause_and_notify',
		survivalPolicy: state.survivalPolicy,
		hasReassessmentCondition: state.hasReassessmentCondition,
		watcherCount: state.watcherCount,
		watchers: state.watchers,
		primitiveCalls: Object.freeze([...state.primitiveCalls].sort()),
	};
}

function validateWatcherPrologue(ast) {
	let prologue = true;
	for (const statement of ast.body) {
		if (statement.type === 'EmptyStatement') continue;
		const path = statement.type === 'ExpressionStatement' && statement.expression.type === 'CallExpression'
			? staticMemberPath(statement.expression.callee)?.join('.') : null;
		if (path === 'program.onUnhandledAttention' || path === 'program.watch') {
			if (!prologue && path === 'program.watch') throw arenaError('UNSUPPORTED_SYNTAX', 'program.watch declarations must precede top-level execution', statement);
			if (!prologue && path === 'program.onUnhandledAttention' && statement.expression.arguments[1]?.properties?.some((property) => (property.key?.name ?? property.key?.value) === 'reassessWhen')) {
				throw arenaError('UNSUPPORTED_SYNTAX', 'reassessWhen declarations must precede top-level execution', statement);
			}
			continue;
		}
		if (statementHasTopLevelEffect(statement)) prologue = false;
	}
}

function statementHasTopLevelEffect(statement) {
	const stack = [statement];
	while (stack.length > 0) {
		const current = stack.pop();
		if (!current || typeof current !== 'object') continue;
		if (Array.isArray(current)) { stack.push(...current); continue; }
		if (isFunctionNode(current)) continue;
		if (current.type === 'AwaitExpression') return true;
		if (current.type === 'CallExpression') {
			const path = staticMemberPath(current.callee)?.join('.');
			if (path && !['program.onUnhandledAttention', 'program.watch'].includes(path) && !PURE_API_PATHS.has(path)) return true;
		}
		for (const [key, value] of Object.entries(current)) if (!['loc', 'start', 'end', 'type'].includes(key)) stack.push(value);
	}
	return false;
}

function watcherActivationNode(ast) {
	for (const statement of ast.body) {
		if (statementHasTopLevelEffect(statement)) return statement;
	}
	return { start: ast.end, loc: ast.loc };
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
			visit(node.body, state, { ...context, scope: createLexicalScope(context.scope, node.body, state, context.functionNode), topLevelExpression: false });
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
			if (!context.binding && ['program', 'player', 'world', 'inventory', 'math'].includes(node.name) && !context.capabilityMemberObject) {
				throw arenaError('UNSUPPORTED_SYNTAX', `Arena capability ${node.name} may only be used through an approved direct call`, node);
			}
			if (!context.binding && !context.property && !ALLOWED_GLOBALS.has(node.name)) {
				const binding = resolveBinding(context.scope, node.name);
				if (!binding) {
					throw arenaError('UNSAFE_MEMBER_ACCESS', `identifier ${node.name} is outside its lexical scope or used before declaration`, node);
				}
				if (context.inFunction && binding.ownerFunctionNode !== context.functionNode) {
					if (!binding.hoisted && context.functionBinding) recordFunctionDependency(state, context.functionBinding, binding, node);
				} else if (!bindingAvailableAtUse(binding, node)) {
					throw arenaError('UNSAFE_MEMBER_ACCESS', `identifier ${node.name} is outside its lexical scope or used before declaration`, node);
				}
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
				functionNode: node,
				scope: parameterScope,
				topLevelExpression: false,
				inFunction: true,
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
			{
				const loopScope = createLexicalScope(context.scope, [node.init], state, context.functionNode);
				visit(node.init, state, { ...context, scope: loopScope, topLevelExpression: false });
				visit(node.test, state, { ...context, scope: loopScope, topLevelExpression: false });
				visit(node.update, state, { ...context, scope: loopScope, topLevelExpression: false });
				visit(node.body, state, { ...context, scope: loopScope, topLevelExpression: false });
			}
			return;
		case 'WhileStatement':
		case 'DoWhileStatement':
		case 'ForInStatement':
			throw arenaError('UNBOUNDED_LOOP', 'use a literal-bounded for loop or program.repeatUntil(...,{maxIterations:N},...)', node);
		case 'ForOfStatement': {
			if (node.await || node.left.type !== 'VariableDeclaration' || !['const', 'let'].includes(node.left.kind)
				|| node.left.declarations.length !== 1 || node.left.declarations[0].id.type !== 'Identifier') {
				throw arenaError('UNBOUNDED_LOOP', 'for-of requires one local const or let and a bounded factual or literal array', node);
			}
			const loopScope = createLexicalScope(context.scope, [node.left], state, context.functionNode);
			visit(node.left, state, { ...context, scope: loopScope, topLevelExpression: false });
			visit(node.right, state, { ...context, topLevelExpression: false });
			visit(node.body, state, { ...context, scope: loopScope, topLevelExpression: false });
			return;
		}
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
			validateLocalAssignmentTarget(node.left, node.right, context);
			visit(node.left, state, { ...context, topLevelExpression: false });
			visit(node.right, state, { ...context, topLevelExpression: false });
			return;
		case 'UpdateExpression':
			if (!['++', '--'].includes(node.operator)) {
				throw arenaError('UNSUPPORTED_SYNTAX', `update operator ${node.operator} is not allowed`, node);
			}
			validateLocalAssignmentTarget(node.argument, null, context);
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
			throw arenaError('UNSUPPORTED_SYNTAX', 'use a supported call such as await player.mine({...}); tool names are not functions', node.callee);
		}
	} else if (node.callee.type === 'MemberExpression') {
		if (node.callee.computed || node.callee.optional) {
			throw arenaError('UNSAFE_MEMBER_ACCESS', 'computed member call targets are not allowed', node.callee);
		}
		if (path?.[0] && !ALLOWED_GLOBALS.has(path[0])) {
			throw arenaError(resolveBinding(context.scope, path[0]) ? 'UNSUPPORTED_SYNTAX' : 'UNSAFE_MEMBER_ACCESS', 'unapproved member call targets are not allowed', node.callee);
		}
		if (path?.[0] && resolveBinding(context.scope, path[0])) {
			throw arenaError('UNSUPPORTED_SYNTAX', `approved Arena API root ${path[0]} cannot resolve to a local binding`, node.callee);
		}
		if (!path || !APPROVED_API_CALL_PATHS.has(path.join('.'))) {
			throw arenaError('UNSUPPORTED_SYNTAX', 'direct API calls only; use await tryResult(player.moveTo({...})) instead of .catch(...)', node.callee);
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
		if (![1, 2].includes(node.arguments.length) || node.arguments[0]?.type !== 'Literal' || typeof node.arguments[0].value !== 'string' || !UNHANDLED_POLICIES.has(node.arguments[0].value)) {
			throw arenaError('UNSUPPORTED_SYNTAX', 'onUnhandledAttention requires one supported literal policy', node);
		}
		state.unhandledPolicy = node.arguments[0].value;
		if (node.arguments[1] !== undefined) {
			const options = node.arguments[1];
			if (options.type !== 'ObjectExpression' || options.properties.length === 0) throw arenaError('UNSUPPORTED_SYNTAX', 'attention options require survival or reassessWhen', options);
			const names = new Set();
			for (const property of options.properties) {
				const name = property.type === 'Property' ? propertyName(property.key) : null;
				if (!['survival', 'reassessWhen'].includes(name) || property.computed || property.method || property.kind !== 'init' || names.has(name)) throw arenaError('UNSUPPORTED_SYNTAX', 'attention options allow only unique survival and reassessWhen properties', property);
				names.add(name);
				if (name === 'survival') {
					if (property.value.type !== 'Literal' || !UNHANDLED_POLICIES.has(property.value.value)) throw arenaError('UNSUPPORTED_SYNTAX', 'survival requires a supported literal policy', property);
					state.survivalPolicy = property.value.value;
				} else {
					if (!isFunctionNode(property.value)) throw arenaError('UNSUPPORTED_SYNTAX', 'reassessWhen requires a factual condition function', property);
					validatePureCondition(property.value);
					recordCallbackCalls(state, state.watcherActivationNode, context, property.value);
					state.hasReassessmentCondition = true;
				}
			}
		}
	}

	if (pathEqual(path, ['program', 'repeatUntil'])) {
		validateRepeatUntil(node, state, context);
	}
	if (pathEqual(path, ['program', 'parameters']) && node.arguments.length !== 0) {
		throw arenaError('UNSUPPORTED_SYNTAX', 'program.parameters requires no arguments', node);
	}
	if (pathEqual(path, ['program', 'watch'])) {
		validateWatcher(node, state, context);
	}
	const primitive = path?.[0] === 'player' ? PLAYER_MEMBER_PRIMITIVES[path[1]] : null;
	if (primitive === 'attack' || primitive === 'use_ranged' || primitive === 'interact_entity'
		|| primitive === 'fight_target' || primitive === 'flee_from') {
		validateExactTargetCall(node);
	}
	if (primitive === 'navigate_to' || primitive === 'move_to') {
		validateNavigateTarget(node, context.scope);
	}
	if (primitive === 'break_block') {
		validateMineTarget(node);
	}
	if (path?.[0] === 'player' && Object.hasOwn(PLAYER_MEMBER_PRIMITIVES, path[1])) {
		validatePlayerPrimitiveArity(node, path[1]);
		state.primitiveCalls.add(PLAYER_MEMBER_PRIMITIVES[path[1]]);
	}
	if (functionBinding && context.functionBinding) {
		let edges = state.functionEdges.get(context.functionBinding);
			if (!edges) {
				edges = new Map();
				state.functionEdges.set(context.functionBinding, edges);
			}
		edges.set(functionBinding, node);
	}
	if (functionBinding) state.functionCalls.push(Object.freeze({ binding: functionBinding, node, ownerFunctionNode: context.functionNode }));
}

/** Rewrite natural action-tool spellings before static checks inspect their call paths. */
function rewriteActionToolAliases(ast, state) {
	const rootScope = createActionAliasScope(null, ast.body);
	const errors = [];
	const rewrite = (node, scope) => {
		if (!node || typeof node !== 'object') return;
		if (Array.isArray(node)) {
			for (const child of node) rewrite(child, scope);
			return;
		}
		if (node.type === 'ExpressionStatement' && isNoopActionCatchStatement(node.expression, scope)) {
			rewriteNoopActionCatch(node.expression.argument);
		}
		if (node.type === 'CallExpression' && node.callee?.type === 'Identifier'
			&& isActionToolAliasName(node.callee.name) && !resolveBinding(scope, node.callee.name)) {
			try { rewriteActionToolCall(node, state); }
			catch (error) { errors.push(error); }
		}
		if (node.type === 'Program') {
			for (const statement of node.body) rewrite(statement, rootScope);
			return;
		}
		if (node.type === 'BlockStatement') {
			const blockScope = createActionAliasScope(scope, node.body);
			for (const statement of node.body) rewrite(statement, blockScope);
			return;
		}
		if (isFunctionNode(node)) {
			const functionScope = createActionAliasParameterScope(scope, node);
			rewrite(node.body, functionScope);
			return;
		}
		if (node.type === 'ForStatement') {
			const loopScope = createActionAliasScope(scope, [node.init]);
			for (const child of [node.init, node.test, node.update, node.body]) rewrite(child, loopScope);
			return;
		}
		if (node.type === 'ForOfStatement') {
			const loopScope = createActionAliasScope(scope, [node.left]);
			for (const child of [node.left, node.right, node.body]) rewrite(child, loopScope);
			return;
		}
		for (const [key, child] of Object.entries(node)) {
			if (key === 'loc' || key === 'start' || key === 'end' || key === 'type') continue;
			rewrite(child, scope);
		}
	};
	rewrite(ast, rootScope);
	if (errors.length > 0) throw errors[0];
}

function createActionAliasScope(parent, statements) {
	const scope = { parent, bindings: new Map() };
	for (const statement of statements ?? []) {
		if (statement?.type === 'FunctionDeclaration' && statement.id?.type === 'Identifier') scope.bindings.set(statement.id.name, true);
		if (statement?.type !== 'VariableDeclaration') continue;
		for (const declaration of statement.declarations) {
			if (declaration.id?.type === 'Identifier') scope.bindings.set(declaration.id.name, true);
		}
	}
	return scope;
}

function createActionAliasParameterScope(parent, node) {
	const scope = { parent, bindings: new Map() };
	for (const parameter of node.params) if (parameter.type === 'Identifier') scope.bindings.set(parameter.name, true);
	if (node.id?.type === 'Identifier') scope.bindings.set(node.id.name, true);
	return scope;
}

function validatePlayerPrimitiveArity(node, memberName) {
	if (ACTION_FIELDS[PLAYER_MEMBER_PRIMITIVES[memberName]]?.length === 0) {
		if (node.arguments.length !== 0) {
			throw arenaError('INVALID_ARENA_SCRIPT_COMMAND', `player.${memberName} requires no arguments`, node);
		}
		return;
	}
	if (node.arguments.length !== 1) {
		throw arenaError('INVALID_ARENA_SCRIPT_COMMAND', `player.${memberName} requires exactly one action argument`, node);
	}
}

function validateMineTarget(node) {
	const argument = node.arguments[0];
	if (!argument || argument.type !== 'ObjectExpression') {
		throw arenaError('INVALID_ARENA_SCRIPT_COMMAND', 'player.mine needs x,y,z and an observed non-air expectedBlockId from world.blocks', node);
	}
	const expected = argument.properties.find((property) => propertyName(property.key) === 'expectedBlockId');
	if (!expected) {
		throw arenaError('INVALID_ARENA_SCRIPT_COMMAND', 'player.mine needs an observed non-air expectedBlockId from the same world.blocks row, e.g. {x:b.x,y:b.y,z:b.z,expectedBlockId:b.blockId}', argument);
	}
	if (expected.value?.type === 'Literal' && (typeof expected.value.value !== 'string' || expected.value.value.trim() === '' || /(?:^|:)air$/u.test(expected.value.value))) {
		throw arenaError('INVALID_ARENA_SCRIPT_COMMAND', 'player.mine expectedBlockId must identify a non-air block', expected.value);
	}
}

function rewriteActionToolCall(node, state) {
	let actionTypeValue;
	let actionArguments;
	const aliasName = node.callee.name;
	if (node.callee.name !== 'act') {
		const primitive = PLAYER_MEMBER_PRIMITIVES[node.callee.name];
		if (node.arguments.length === 0 && ACTION_FIELDS[primitive]?.length === 0) {
			actionTypeValue = node.callee.name;
			actionArguments = null;
		} else if (node.arguments.length === 1 && node.arguments[0]?.type === 'ObjectExpression') {
			actionTypeValue = node.callee.name;
			actionArguments = node.arguments[0];
		} else {
			const fields = node.callee.name === 'mine' || node.callee.name === 'break_block' ? '{x,y,z,expectedBlockId}' : '{...action fields...}';
			const hint = ACTION_FIELDS[primitive]?.length === 0 ? `${node.callee.name}() takes no arguments` : `${node.callee.name} needs one action object, e.g. ${node.callee.name}(${fields})`;
			throw arenaError('UNSUPPORTED_SYNTAX', hint, node);
		}
	} else if (node.arguments.length === 2 && node.arguments[0]?.type === 'Literal' && typeof node.arguments[0].value === 'string') {
		actionTypeValue = node.arguments[0].value;
		actionArguments = node.arguments[1];
	} else if (node.arguments.length === 1 && node.arguments[0]?.type === 'ObjectExpression') {
		const properties = node.arguments[0].properties;
		const actionType = properties.find((property) => propertyName(property.key) === 'actionType');
		const argumentsProperty = properties.find((property) => propertyName(property.key) === 'arguments');
		if (properties.length === 1 && actionType?.kind === 'init' && !actionType.method && !actionType.computed
			&& actionType.value?.type === 'Literal' && typeof actionType.value.value === 'string') {
			throw arenaError('UNSUPPORTED_SYNTAX', missingActionToolArgumentsHint(actionType.value.value), node);
		}
		if (properties.length !== 2 || properties.some((property) => property.kind !== 'init' || property.method || property.computed)
			|| !actionType || !argumentsProperty || actionType.value?.type !== 'Literal' || typeof actionType.value.value !== 'string') {
			throw arenaError('UNSUPPORTED_SYNTAX', 'act() needs {actionType:"mine",arguments:{...}} or act("mine",{...}); actionType must be a literal', node);
		}
		actionTypeValue = actionType.value.value;
		actionArguments = argumentsProperty.value;
	} else {
		throw arenaError('UNSUPPORTED_SYNTAX', 'act() needs {actionType:"mine",arguments:{...}} or act("mine",{...}); actionType must be a literal', node);
	}
	if (actionArguments !== null && actionArguments?.type !== 'ObjectExpression') {
		throw arenaError('UNSUPPORTED_SYNTAX', 'act arguments must be an object literal, e.g. act("mine",{x:b.x,y:b.y,z:b.z,expectedBlockId:b.blockId})', node);
	}
	const member = PLAYER_MEMBERS_BY_ACTION_TYPE.get(actionTypeValue);
	if (!member) {
		throw arenaError('UNSUPPORTED_SYNTAX', `act actionType "${actionTypeValue}" has no ArenaScript primitive; use a supported player.<action> call`, node);
	}
	state.actionToolAliasCount += 1;
	const primitive = PLAYER_MEMBER_PRIMITIVES[member];
	if (actionArguments !== null) validateActionToolAliasFields(aliasName, primitive, actionArguments, node);
	appendActionToolDefaults(actionArguments, ACTION_TOOL_DEFAULTS[PLAYER_MEMBER_PRIMITIVES[member]]);
	if (aliasName === 'mine') node.arenaActionToolAlias = 'mine';
	const { start, end, loc } = node.callee;
	node.callee = {
		type: 'MemberExpression',
		object: { type: 'Identifier', name: 'player', start, end, loc },
		property: { type: 'Identifier', name: member, start, end, loc },
		computed: false,
		optional: false,
		start,
		end,
		loc,
	};
	node.arguments = actionArguments === null ? [] : [actionArguments];
}

function validateActionToolAliasFields(aliasName, primitive, argument, node) {
	const nativeFields = new Set(ACTION_FIELDS[primitive] ?? []);
	if (aliasName === 'mine') nativeFields.add('autoAim');
	for (const property of argument.properties) {
		const name = propertyName(property.key);
		if (!nativeFields.has(name)) {
			throw arenaError('UNSUPPORTED_SYNTAX', `${aliasName} does not accept field "${name}"; use only ${[...nativeFields].join(', ')}`, property ?? node);
		}
	}
}

function appendActionToolDefaults(argument, defaults) {
	for (const [name, value] of Object.entries(defaults ?? {})) {
		if (argument.properties.some((property) => propertyName(property.key) === name)) continue;
		argument.properties.push({
			type: 'Property',
			kind: 'init',
			method: false,
			shorthand: false,
			computed: false,
			key: { type: 'Identifier', name },
			value: { type: 'Literal', value },
		});
	}
}

function missingActionToolArgumentsHint(actionType) {
	if (actionType === 'mine' || actionType === 'break_block') {
		return 'mine needs x,y,z,expectedBlockId; use a world.blocks row: act("mine",{x:b.x,y:b.y,z:b.z,expectedBlockId:b.blockId})';
	}
	const member = PLAYER_MEMBERS_BY_ACTION_TYPE.get(actionType);
	const primitive = member === undefined ? null : PLAYER_MEMBER_PRIMITIVES[member];
	if (primitive !== null && ACTION_FIELDS[primitive].length === 0) return `act actionType "${actionType}" needs an empty arguments object: {}`;
	const fields = primitive === null ? [] : ACTION_FIELDS[primitive].filter((field) =>
		!(OPTIONAL_ACTION_FIELDS[primitive] ?? []).includes(field) && !Object.hasOwn(ACTION_TOOL_DEFAULTS[primitive] ?? {}, field));
	return `act actionType "${actionType}" needs ${fields.length > 0 ? fields.join(', ') : 'its required fields'} in an arguments object`;
}

function isNoopActionCatchStatement(statement, scope) {
	if (statement?.type !== 'AwaitExpression') return false;
	const call = statement.argument;
	return call?.type === 'CallExpression' && isNoopActionCatchCall(call, scope);
}

function isNoopActionCatchCall(node, scope) {
	const callee = node?.callee;
	const handler = node?.arguments?.[0];
	const actionCall = callee?.type === 'MemberExpression' && !callee.computed && !callee.optional
		&& propertyName(callee.property) === 'catch' ? callee.object : null;
	return node.arguments.length === 1 && actionCall?.type === 'CallExpression'
		&& actionCall.callee?.type === 'Identifier' && isActionToolAliasName(actionCall.callee.name)
		&& !resolveBinding(scope, actionCall.callee.name)
		&& handler?.type === 'ArrowFunctionExpression' && !handler.async && handler.params.length === 0
		&& handler.body?.type === 'BlockStatement' && handler.body.body.length === 0;
}

function isActionToolAliasName(name) {
	return name === 'act' || PLAYER_MEMBERS_BY_ACTION_TYPE.has(name);
}

function rewriteNoopActionCatch(node) {
	// The discarded no-op handler leaves the normalized action result on the existing tryResult path.
	const { start, end, loc } = node.callee;
	const actionCall = node.callee.object;
	node.callee = { type: 'Identifier', name: 'tryResult', start, end, loc };
	node.arguments = [actionCall];
}

function validateExactTargetCall(node) {
	const argument = node.arguments[0];
	if (!argument || argument.type !== 'ObjectExpression') return;
	const names = argument.properties.map((property) => propertyName(property.key));
	if (names.includes('targetSelector')) {
		throw arenaError('UNSUPPORTED_SYNTAX', 'exact target actions require targetId, not targetSelector', argument);
	}
	if (!names.includes('targetId')) {
		throw arenaError('UNSUPPORTED_SYNTAX', 'exact target actions require targetId', argument);
	}
	const target = argument.properties.find((property) => propertyName(property.key) === 'targetId');
	if (target?.value?.type === 'Literal' && typeof target.value.value === 'string' && target.value.value.startsWith('nearest_')) {
		throw arenaError('UNSUPPORTED_SYNTAX', 'nearest target selectors are not valid target ids', target.value);
	}
}

function validateNavigateTarget(node, scope) {
	const argument = node.arguments[0];
	if (!argument) return;
	const coordinates = argument.type === 'ObjectExpression'
		? argument.properties
			.filter((property) => ['x', 'y', 'z'].includes(propertyName(property.key)))
			.map((property) => property.value)
		: [argument];
	const unsafeCoordinate = coordinates.find((coordinate) => isObservedItemCoordinate(coordinate, scope));
	if (unsafeCoordinate) {
		throw arenaError('UNSUPPORTED_SYNTAX', 'player.navigateTo cannot target floating item coordinates', unsafeCoordinate);
	}
}

function isObservedItemCoordinate(node, scope) {
	if (node?.type !== 'MemberExpression' || node.computed || node.optional) return false;
	if (!['x', 'y', 'z'].includes(propertyName(node.property)) || node.object.type !== 'Identifier') return false;
	return resolveBinding(scope, node.object.name)?.observedItemCandidate === true;
}

function isDirectObservedItemCandidate(node) {
	if (node?.type !== 'CallExpression' || !pathEqual(staticMemberPath(node.callee), ['world', 'nearest'])) return false;
	const candidates = node.arguments[0];
	return candidates?.type === 'CallExpression' && pathEqual(staticMemberPath(candidates.callee), ['world', 'items']);
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
	if (root && !ALLOWED_GLOBALS.has(root) && !resolveBinding(context.scope, root)) {
		throw arenaError('UNSAFE_MEMBER_ACCESS', `member root ${root} is outside the ArenaScript environment`, node);
	}
}

function validateRepeatUntil(node, state, context) {
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
	recordCallbackCalls(state, node, context, node.arguments[0], node.arguments[2]);
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
	recordCallbackCalls(state, state.watcherActivationNode, context, node.arguments[0], node.arguments[2]);
	const options = node.arguments[1];
	if (options?.type !== 'ObjectExpression' || options.properties.some((p) => p.type !== 'Property' || p.computed || p.kind !== 'init' || p.value.type !== 'Literal'
		|| !['mode', 'after'].includes(p.key.name ?? p.key.value)) || new Set(options.properties.map((p) => p.key.name ?? p.key.value)).size !== options.properties.length) {
		throw arenaError('UNSUPPORTED_SYNTAX', 'watch options are literal mode and optional after properties', options);
	}
	const mode = options.properties.find((p) => (p.key.name ?? p.key.value) === 'mode')?.value.value;
	const after = options.properties.find((p) => (p.key.name ?? p.key.value) === 'after')?.value.value;
	if (after !== undefined && !['resume', 'reconsider'].includes(after)) throw arenaError('UNSUPPORTED_SYNTAX', 'watch after must be resume or reconsider', options);
	if (!['boundary', 'interrupt'].includes(mode)) {
		throw arenaError('UNSUPPORTED_SYNTAX', 'watcher mode must be the boundary or interrupt literal', node.arguments[1]);
	}
	validatePureCondition(node.arguments[0]);
	validateWatcherHandler(node.arguments[2], state, context.scope);
	state.watchers.push(Object.freeze({
		id: `watcher-${state.watcherCount - 1}`,
		mode,
		...(after === undefined ? {} : { after }),
		factDependencyMask: watcherFactDependencyMask(node.arguments[0]),
	}));
}

function watcherFactDependencyMask(condition) {
	let mask = 0;
	let dependsOnRuntimeState = false;
	const visit = (node) => {
		if (!node || typeof node !== 'object' || dependsOnRuntimeState) return;
		if (Array.isArray(node)) {
			for (const child of node) visit(child);
			return;
		}
		if (isFunctionNode(node)) {
			if (node.params.length > 0) dependsOnRuntimeState = true;
			visit(node.body);
			return;
		}
		if (node.type === 'CallExpression') {
			const path = staticMemberPath(node.callee)?.join('.') ?? null;
			switch (path) {
				case 'player.state': mask |= FACT_DOMAIN.player; break;
				case 'world.items': mask |= FACT_DOMAIN.worldItems; break;
				case 'world.entities': mask |= FACT_DOMAIN.worldEntities; break;
				case 'world.blocks': mask |= FACT_DOMAIN.worldBlocks; break;
				case 'inventory.count': mask |= FACT_DOMAIN.inventoryItems; break;
				case 'inventory.countTag': mask |= FACT_DOMAIN.inventoryTagCounts; break;
				case 'inventory.slots': mask |= FACT_DOMAIN.inventoryItems; break;
				case 'inventory.state': mask |= FACT_DOMAIN.inventoryState; break;
				case 'world.state': mask |= FACT_DOMAIN.worldState; break;
				case 'world.menu': mask |= FACT_DOMAIN.menu; break;
				case 'world.nearest': mask |= FACT_DOMAIN.player; break;
				case 'program.parameters': break;
				default: if (!path?.startsWith('math.')) { dependsOnRuntimeState = true; return; }
			}
			visit(node.arguments);
			return;
		}
		if (node.type === 'MemberExpression') {
			visit(node.object);
			if (node.computed) visit(node.property);
			return;
		}
		if (node.type === 'Property') {
			if (node.computed) visit(node.key);
			visit(node.value);
			return;
		}
		if (node.type === 'Identifier' || node.type === 'VariableDeclarator' || node.type === 'FunctionDeclaration') {
			dependsOnRuntimeState = true;
			return;
		}
		for (const [key, value] of Object.entries(node)) {
			if (key !== 'loc' && key !== 'start' && key !== 'end' && key !== 'type') visit(value);
		}
	};
	visit(condition);
	return dependsOnRuntimeState ? null : mask;
}

const WATCHER_FORBIDDEN_PATHS = new Set([
	'player.chat',
	'program.checkpoint',
	'program.finish',
	'program.onUnhandledAttention',
	'program.repeatUntil',
	'program.watch',
]);

/** Reject watcher handlers that can speak or mutate program/brain lifecycle. */
function validateWatcherHandler(node, state, parentScope) {
	const visitedFunctions = new Set();
	const visitFunction = (functionNode, definingScope) => {
		if (visitedFunctions.has(functionNode)) return;
		visitedFunctions.add(functionNode);
		const functionBinding = state.functionBindingsByNode.get(functionNode) ?? null;
		const functionScope = createParameterScope(definingScope, functionNode, functionBinding);
		if (functionNode.body.type === 'BlockStatement') visitNode(functionNode.body, createLexicalScope(functionScope, functionNode.body.body, state));
		else visitNode(functionNode.body, functionScope);
	};
	const visitNode = (current, scope) => {
		if (!current || typeof current !== 'object') return;
		if (Array.isArray(current)) {
			for (const child of current) visitNode(child, scope);
			return;
		}
		if (current.type === 'FunctionDeclaration' || current.type === 'FunctionExpression' || current.type === 'ArrowFunctionExpression') {
			visitFunction(current, scope);
			return;
		}
		if (current.type === 'CallExpression') {
			const path = staticMemberPath(current.callee)?.join('.') ?? null;
			if (path && WATCHER_FORBIDDEN_PATHS.has(path)) {
				throw arenaError('UNSUPPORTED_SYNTAX', `watcher handlers cannot call ${path}`, current);
			}
			if (current.callee.type === 'Identifier') {
				const binding = resolveBinding(scope, current.callee.name);
				if (binding?.callable) visitFunction(binding.functionNode, binding.scope ?? scope);
			}
		}
		if (current.type === 'BlockStatement') {
			const blockScope = createLexicalScope(scope, current.body, state);
			for (const child of current.body) visitNode(child, blockScope);
			return;
		}
		for (const [key, value] of Object.entries(current)) {
			if (key === 'loc' || key === 'start' || key === 'end' || key === 'type') continue;
			visitNode(value, scope);
		}
	};
	visitFunction(node, parentScope);
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
			if (!PURE_API_PATHS.has(path)) {
				throw arenaError('UNSUPPORTED_SYNTAX', 'watcher and repeatUntil conditions can call only factual Arena APIs', current);
			}
		}
		if (current.type === 'MemberExpression') {
			const path = staticMemberPath(current)?.join('.');
			if ([...SCRIPT_API_CALL_PATHS].filter((apiPath) => !PURE_API_PATHS.has(apiPath)).includes(path)
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

function validateLocalAssignmentTarget(node, value, context) {
	if (node.type !== 'Identifier' || !resolveBinding(context.scope, node.name)) {
		throw arenaError('UNSAFE_MEMBER_ACCESS', 'only local variables may be assigned or updated', node);
	}
	if (resolveBinding(context.scope, node.name)?.callable) {
		throw arenaError('UNSUPPORTED_SYNTAX', 'callable local bindings cannot be reassigned or updated', node);
	}
	if (isFunctionNode(value) || (value?.type === 'Identifier' && resolveBinding(context.scope, value.name)?.callable)) {
		throw arenaError('UNSUPPORTED_SYNTAX', 'callable values cannot be assigned to local bindings', value);
	}
}

function createLexicalScope(parent, statements, state, ownerFunctionNode = null) {
	const scope = { parent, bindings: new Map() };
	for (const statement of statements) {
		if (statement?.type === 'FunctionDeclaration' && statement.id?.type === 'Identifier') {
			rejectReservedBinding(statement.id.name, statement.id);
			registerFunctionBinding(scope, statement.id.name, statement, state, { hoisted: true, ownerFunctionNode });
			continue;
		}
		if (statement?.type !== 'VariableDeclaration') continue;
		for (const declaration of statement.declarations) {
			if (declaration.id?.type !== 'Identifier') continue;
			rejectReservedBinding(declaration.id.name, declaration.id);
			if (statement.kind === 'const' && isFunctionNode(declaration.init)) {
				registerFunctionBinding(scope, declaration.id.name, declaration.init, state, { availableAt: declaration.end, ownerFunctionNode });
			} else {
				scope.bindings.set(declaration.id.name, Object.freeze({
					callable: false,
					name: declaration.id.name,
					availableAt: declaration.end,
					ownerFunctionNode,
					observedItemCandidate: statement.kind === 'const' && isDirectObservedItemCandidate(declaration.init),
				}));
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
			scope.bindings.set(parameter.name, Object.freeze({ callable: false, name: parameter.name, hoisted: true, ownerFunctionNode: node }));
		}
	}
	if (node.id?.type === 'Identifier' && functionBinding) {
		rejectReservedBinding(node.id.name, node.id);
		if (!scope.bindings.has(node.id.name)) scope.bindings.set(node.id.name, functionBinding);
	}
	return scope;
}

function registerFunctionBinding(scope, name, functionNode, state, { hoisted = false, availableAt = null, ownerFunctionNode = null } = {}) {
	const binding = Object.freeze({ callable: true, name, functionNode, scope, hoisted, availableAt, ownerFunctionNode });
	scope.bindings.set(name, binding);
	state.functionBindingsByNode.set(functionNode, binding);
	state.functionBindings.add(binding);
}

function callbackBinding(state, functionNode, scope, ownerFunctionNode) {
	const existing = state.functionBindingsByNode.get(functionNode);
	if (existing) return existing;
	const binding = Object.freeze({
		callable: true,
		name: `callback@${functionNode.start}`,
		functionNode,
		scope,
		hoisted: true,
		availableAt: null,
		ownerFunctionNode,
	});
	state.functionBindingsByNode.set(functionNode, binding);
	state.functionBindings.add(binding);
	return binding;
}

function recordCallbackCalls(state, callNode, context, ...callbacks) {
	for (const callback of callbacks) {
		const binding = callbackBinding(state, callback, context.scope, context.functionNode);
		state.functionCalls.push(Object.freeze({ binding, node: callNode, ownerFunctionNode: context.functionNode }));
		if (context.functionBinding) {
			let edges = state.functionEdges.get(context.functionBinding);
			if (!edges) state.functionEdges.set(context.functionBinding, edges = new Map());
			edges.set(binding, callNode);
		}
	}
}

function resolveBinding(scope, name) {
	for (let current = scope; current; current = current.parent) {
		const binding = current.bindings.get(name);
		if (binding) return binding;
	}
	return null;
}

function bindingAvailableAtUse(binding, node) {
	return binding.hoisted === true || (Number.isInteger(binding.availableAt) && node.start >= binding.availableAt);
}

function recordFunctionDependency(state, functionBinding, dependency, node) {
	let dependencies = state.functionDependencies.get(functionBinding);
	if (!dependencies) {
		dependencies = new Map();
		state.functionDependencies.set(functionBinding, dependencies);
	}
	if (!dependencies.has(dependency)) dependencies.set(dependency, node);
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

function validateFunctionCallDependencies(state) {
	for (const call of state.functionCalls) {
		const pending = [call.binding];
		const visited = new Set();
		while (pending.length > 0) {
			const binding = pending.pop();
			if (visited.has(binding)) continue;
			visited.add(binding);
			for (const [dependency] of state.functionDependencies.get(binding) ?? []) {
				if (dependency.ownerFunctionNode === call.ownerFunctionNode && !bindingAvailableAtUse(dependency, call.node)) {
					throw arenaError('UNSAFE_MEMBER_ACCESS', `local function ${call.binding.name} is called before dependency ${dependency.name} is initialized`, call.node);
				}
			}
			for (const [callee] of state.functionEdges.get(binding) ?? []) pending.push(callee);
		}
	}
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
