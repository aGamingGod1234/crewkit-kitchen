import { PLAYER_MEMBER_PRIMITIVES } from './minecraft-api.mjs';

const QUERY_CALLS = new Set(['world.inspect', 'world.remember', 'world.queryMemory', 'world.taskMemory']);
const FUNCTION_NODES = new Set(['FunctionDeclaration', 'FunctionExpression', 'ArrowFunctionExpression']);
const REPEATING_NODES = new Set(['ForStatement', 'ForInStatement', 'ForOfStatement', 'WhileStatement', 'DoWhileStatement', 'LabeledStatement',
	'SwitchStatement', 'TryStatement', 'ThrowStatement', 'BreakStatement', 'ContinueStatement', 'ReturnStatement', 'ClassDeclaration', 'ClassExpression']);

/**
 * How many commands the statements after `index` will issue, or null when that cannot be known from the
 * source alone (loops, user functions, early exits). The statement at `index` must itself be exactly one
 * command, so a loop that is half done is never mistaken for finished work.
 */
export function remainingTopLevelCommands(statements, index) {
	if (straightLineCommands(statements[index]) !== 1) return null;
	let total = 0;
	for (let position = index + 1; position < statements.length; position += 1) {
		const count = straightLineCommands(statements[position]);
		if (count === null) return null;
		total += count;
	}
	return total;
}

/** Commands issued by straight-line code in this subtree (the longer branch of an if), or null when unknowable. */
export function straightLineCommands(node) {
	if (node === null || typeof node !== 'object') return 0;
	if (Array.isArray(node)) return sum(node);
	if (REPEATING_NODES.has(node.type)) return null;
	// A declared function runs nothing until called, and a call to it is rejected below.
	if (node.type === 'FunctionDeclaration') return 0;
	if (FUNCTION_NODES.has(node.type)) return null;
	if (node.type === 'IfStatement') return branches(node.test, [node.consequent, node.alternate]);
	if (node.type === 'ConditionalExpression') return branches(node.test, [node.consequent, node.alternate]);
	if (node.type === 'LogicalExpression') return branches(node.left, [node.right]);
	if (node.type === 'CallExpression') return callCommands(node);
	return sum(Object.entries(node).filter(([key]) => key !== 'loc').map(([, value]) => value));
}

function callCommands(node) {
	const path = memberPath(node.callee);
	if (path === null) return null;
	const joined = path.join('.');
	// Registering a watcher issues nothing; its handler runs only when its condition holds.
	if (joined === 'program.watch') return 0;
	if (joined === 'program.repeatUntil' || joined === 'program.checkpoint' || joined === 'program.finish') return null;
	const inner = sum(node.arguments);
	if (inner === null) return null;
	if (joined === 'tryResult' || joined.startsWith('math.') || joined === 'program.parameters' || joined === 'program.onUnhandledAttention') return inner;
	const [root, member] = path;
	if (root === 'player' && member !== 'state' && Object.hasOwn(PLAYER_MEMBER_PRIMITIVES, member)) return inner + 1;
	if (QUERY_CALLS.has(joined)) return inner + 1;
	// Pure fact reads add no command. Anything else is a call this walk cannot size.
	return path.length === 2 && ['player', 'world', 'inventory'].includes(root) ? inner : null;
}

function branches(test, arms) {
	const tested = straightLineCommands(test);
	if (tested === null) return null;
	let longest = 0;
	for (const arm of arms) {
		const count = straightLineCommands(arm);
		if (count === null) return null;
		longest = Math.max(longest, count);
	}
	return tested + longest;
}

function sum(nodes) {
	let total = 0;
	for (const node of nodes) {
		const count = straightLineCommands(node);
		if (count === null) return null;
		total += count;
	}
	return total;
}

function memberPath(callee) {
	if (callee?.type === 'Identifier') return [callee.name];
	const names = [];
	let current = callee;
	for (; current?.type === 'MemberExpression'; current = current.object) {
		if (current.computed || current.property?.type !== 'Identifier') return null;
		names.unshift(current.property.name);
	}
	if (current?.type !== 'Identifier') return null;
	names.unshift(current.name);
	return names;
}
