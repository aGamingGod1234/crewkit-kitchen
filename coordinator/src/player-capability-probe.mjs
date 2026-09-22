import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { randomUUID } from 'node:crypto';
import net from 'node:net';
import { MultiplexedServerBridge, validateProtocolV2Payload } from './protocol-v2.mjs';
import { HeadlessRconClient } from './headless-rcon.mjs';
import { JsonlDecoder } from './jsonl.mjs';
import { NativeToolRuntime } from './native-tool-runtime.mjs';
import { normalizeMinecraftToolCall } from './native-minecraft-tools.mjs';

const PROFILE = Object.freeze({ provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'priority' });
const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED']);

export function parseProbeArguments(argv, env = process.env) {
	const allowed = new Set(['rcon-host', 'rcon-port', 'rcon-password-file', 'bridge-port', 'bridge-secret-file', 'run-directory', 'agent-name', 'timeout-ms']);
	const args = {};
	for (let index = 0; index < argv.length; index += 2) {
		const key = argv[index]?.replace(/^--/, '');
		if (!argv[index]?.startsWith('--') || !allowed.has(key) || argv[index + 1] === undefined || Object.hasOwn(args, key)) throw new Error('INVALID_PROBE_ARGUMENTS');
		args[key] = argv[index + 1];
	}
	const host = args['rcon-host'] ?? '127.0.0.1';
	if (host !== '127.0.0.1') throw new Error('PROBE_LOOPBACK_REQUIRED');
	const agentName = args['agent-name'] ?? 'CapabilityProbe';
	if (!/^[A-Za-z][A-Za-z0-9_]{0,15}$/.test(agentName)) throw new Error('INVALID_PROBE_AGENT_NAME');
	const requiredPath = (argument, environment) => {
		const value = args[argument] ?? env[environment];
		if (typeof value !== 'string' || value.length === 0) throw new Error(`MISSING_${argument.toUpperCase().replaceAll('-', '_')}`);
		return resolve(value);
	};
	const integer = (value, minimum, maximum, field) => {
		const result = Number(value);
		if (!Number.isSafeInteger(result) || result < minimum || result > maximum) throw new Error(`INVALID_${field}`);
		return result;
	};
	return {
		host, agentName,
		rconPort: integer(args['rcon-port'] ?? env.ARENA_PROBE_RCON_PORT, 1, 65535, 'RCON_PORT'),
		bridgePort: integer(args['bridge-port'] ?? env.ARENA_PROBE_BRIDGE_PORT, 1, 65535, 'BRIDGE_PORT'),
		rconPasswordFile: requiredPath('rcon-password-file', 'ARENA_PROBE_RCON_PASSWORD_FILE'),
		bridgeSecretFile: requiredPath('bridge-secret-file', 'ARENA_PROBE_BRIDGE_SECRET_FILE'),
		runDirectory: requiredPath('run-directory', 'ARENA_PROBE_RUN_DIRECTORY'),
		timeoutMs: integer(args['timeout-ms'] ?? 30000, 1000, 600000, 'TIMEOUT'),
	};
}

export function inputFrame(overrides = {}) {
	return { forward: 0, strafe: 0, jump: false, sneak: false, sprint: false, attack: false, use: false, yaw: -90, pitch: 0, selectedSlot: 0, hand: 'main', ticks: 5, ...overrides };
}

export function fixtureAction(record, observation, ordinal, actionType, args) {
	const traceId = `mechanics-fixture-${ordinal}`;
	return validateProtocolV2Payload('action_command', {
		traceId, actionId: traceId, goalRevision: record.goalRevision, actionType, arguments: typeof args === 'function' ? args(observation) : args,
		provenance: {
			provider: record.provider ?? PROFILE.provider, model: record.model, reasoningEffort: record.reasoningEffort,
			serviceTier: record.serviceTier ?? PROFILE.serviceTier, traceId, programId: 'provider-free-mechanics-fixture',
			programVersion: 1, sourceStepId: `fixture-step-${ordinal}`, eventSequence: observation.eventSequence,
		},
	});
}

export function observedHandArguments(observation, hand = 'main') {
	if (!['main', 'off'].includes(hand)) throw new Error('INVALID_FIXTURE_HAND');
	const expectedItemId = observation?.interaction?.[hand === 'main' ? 'mainHandItemId' : 'offHandItemId'];
	if (observation?.ready !== true || typeof expectedItemId !== 'string' || !/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(expectedItemId)) throw new Error('FRESH_HAND_OBSERVATION_REQUIRED');
	return { hand, expectedItemId };
}

export function menuClickArguments(page, slotIndex, clickType = 'PICKUP', button = 0) {
	const menu = page?.menu;
	const stack = page?.entries?.find((entry) => entry.slot === slotIndex);
	if (!menu || !stack || !Number.isSafeInteger(menu.containerId) || !Number.isSafeInteger(menu.stateId)) throw new Error('MENU_INSPECTION_REQUIRED');
	return { menuId: menu.type, containerId: menu.containerId, stateId: menu.stateId, slot: slotIndex, button, clickType, expectedItemId: stack.itemId, expectedCount: stack.count, ...(typeof stack.fingerprint === 'string' ? { expectedFingerprint: stack.fingerprint } : {}) };
}

export function fixtureSetupCommands() {
	return [
		'forceload add -16 -16 48 32', 'difficulty peaceful', 'time set day', 'weather clear',
		'fill -4 64 -4 40 80 12 minecraft:air', 'fill -4 63 -4 40 63 12 minecraft:stone',
		'setblock 2 64 0 minecraft:chest[facing=west]', 'item replace block 2 64 0 container.0 with minecraft:stone 4',
		'fill 11 64 -1 17 68 5 minecraft:stone', 'fill 12 64 0 16 68 4 minecraft:air',
		'fill 12 64 0 16 67 4 minecraft:water', 'fill 24 64 0 24 72 2 minecraft:stone',
		'fill 23 64 1 23 71 1 minecraft:ladder[facing=west]',
		'fill 26 64 -1 39 65 11 minecraft:stone', 'fill 27 64 0 38 65 10 minecraft:air',
		'fill 27 64 0 38 64 10 minecraft:water',
	];
}

export function probeFailureMessage(error, secrets = []) {
	let message = String(error?.message ?? 'PROBE_FAILED');
	for (const secret of secrets) if (typeof secret === 'string' && secret.length > 0) message = message.split(secret).join('[REDACTED]');
	return message.replace(/[\u0000-\u001f\u007f]/g, ' ').slice(0, 1024);
}

export function probeAuditEntry(direction, envelope, phase, secrets = []) {
	const safeText = (value) => probeFailureMessage({ message: value }, secrets).slice(0, 96);
	const type = typeof envelope?.type === 'string' ? safeText(envelope.type) : 'unknown';
	if (type.startsWith('auth_') || type === 'hello' || type === 'hello_ack') return { direction, phase, type, payloadRedacted: true };
	const fieldPaths = [];
	const visit = (value, prefix, depth) => {
		if (value === null || typeof value !== 'object' || depth > 4 || fieldPaths.length >= 96) return;
		if (Array.isArray(value)) { if (value.length > 0) visit(value[0], `${prefix}[0]`, depth + 1); return; }
		for (const key of Object.keys(value)) {
			if (fieldPaths.length >= 96) break;
			const path = `${prefix}.${safeText(key)}`;
			fieldPaths.push(path.slice(0, 160));
			visit(value[key], path, depth + 1);
		}
	};
	visit(envelope?.payload, 'payload', 0);
	return { direction, phase, type, fieldPaths };
}

/** This executes scripted mechanics against real Minecraft. It never starts or evaluates an AI provider. */
export async function runPlayerCapabilityProbe(config) {
	const startedAt = Date.now();
	const report = { schemaVersion: 1, status: 'FAILED', kind: 'production_bridge_mechanics', providerUsed: false, aiPerformanceMeasured: false, fixtureAuthorship: 'deterministic test script with declared registered profile provenance', fixtureSetupUsesOperatorCommands: true, checks: [], actions: [], fixtureCommands: [], cleanup: {} };
	const secret = (await readFile(config.bridgeSecretFile, 'utf8')).trim();
	const password = (await readFile(config.rconPasswordFile, 'utf8')).trim();
	const secrets = [secret, password], bridgeAudit = [];
	const audit = (direction, envelope, phase = 'validated') => {
		bridgeAudit.push({ elapsedMs: Date.now() - startedAt, ...probeAuditEntry(direction, envelope, phase, secrets) });
		if (bridgeAudit.length > 128) bridgeAudit.shift();
	};
	const bridge = new MultiplexedServerBridge({ port: config.bridgePort, secret }, {
		audit,
		socketFactory: () => {
			const socket = net.createConnection({ host: config.host, port: config.bridgePort }), decoder = new JsonlDecoder();
			socket.on('data', (chunk) => {
				try { for (const envelope of decoder.push(chunk)) audit('server_to_coordinator', envelope, 'received_before_validation'); }
				catch (error) { audit('server_to_coordinator', { type: 'unparsed_frame', payload: { [safeError(error)]: null } }, 'received_before_validation'); }
			});
			return socket;
		},
	});
	const rcon = new HeadlessRconClient({ host: config.host, port: config.rconPort, password });
	const inbox = new ProbeInbox(bridge, Math.min(config.timeoutMs, 30000));
	let record = null, observation = null, ordinal = 0;
	let predicate = { type: 'survive_duration', ticks: 12000 };
	let failure = null, stage = 'connect_rcon', rconResponsive = true;
	const command = async (text) => {
		const startedAt = Date.now(), operation = /\bcodex (summon|start|stop)\b/.exec(text)?.[0] ?? text.split(' ')[0];
		try {
			const result = await rcon.command(text);
			if (/Unknown or incomplete command|Incorrect argument|Unknown player|Unknown agent|Unknown entity|Unable to summon|No entity was found|Invalid .*name|position is not loaded|outside of the world/i.test(result.text)) throw new Error(`FIXTURE_COMMAND_REJECTED:${operation.replace(' ', '_')}`);
			report.fixtureCommands.push({ stage, operation, status: 'PASSED', elapsedMs: Date.now() - startedAt });
			return result.text;
		} catch (error) {
			if (error.code === 'RCON_TIMEOUT' || error.code === 'RCON_CLOSED') rconResponsive = false;
			report.fixtureCommands.push({ stage, operation, status: 'FAILED', elapsedMs: Date.now() - startedAt, reasonCode: safeError(error) });
			throw error;
		}
	};
	bridge.on('goal_spec_request', (event) => {
		if (record?.agentId !== event.agentId) return;
		const pending = bridge.send('goal_spec_proposal', record.agentId, { requestId: event.payload.requestId, summary: 'Verify the declared mechanics fixture predicate.', predicate });
		event.waitUntil?.(pending); pending.catch((error) => inbox.fail(error));
	});
	bridge.on('goal_control', (event) => {
		if (record?.agentId === event.agentId) record = { ...record, goalRevision: event.payload.goalRevision };
	});
	bridge.on('goal_spec_result', (event) => {
		if (record?.agentId !== event.agentId) return;
		if (event.payload.status === 'rejected') inbox.fail(new Error(`GOAL_PROPOSAL_${event.payload.reasonCode}`));
	});
	bridge.on('observation', (event) => {
		if (record?.agentId === event.agentId && event.payload.ready) observation = event.payload;
	});
	const observe = async () => {
		const sequence = observation?.eventSequence ?? 0;
		const waiting = inbox.wait('observation', (event) => event.agentId === record.agentId && event.payload.ready && event.payload.goalRevision === record.goalRevision && event.payload.eventSequence > sequence);
		await bridge.send('request_observation', record.agentId, { goalRevision: record.goalRevision });
		observation = (await waiting).payload;
		return observation;
	};
	const inspect = async (section, options = {}) => {
		const requestId = `fixture-inspect-${randomUUID()}`;
		const waiting = inbox.wait('inspection_result', (event) => event.agentId === record.agentId && event.payload.requestId === requestId);
		await bridge.send('inspection_request', record.agentId, { requestId, goalRevision: record.goalRevision, query: { section, limit: 16, offset: 0, ...options } });
		const payload = (await waiting).payload;
		if (payload.error) throw new Error(`INSPECTION_${payload.error.code}`);
		return payload.result;
	};
	const action = async (actionType, args) => {
		const baseline = await observe();
		const payload = fixtureAction(record, baseline, ++ordinal, actionType, args);
		const waiting = inbox.wait('action_result', (event) => event.agentId === record.agentId && event.payload.actionId === payload.actionId && TERMINAL.has(event.payload.state));
		await bridge.send('action_command', record.agentId, payload);
		const result = (await waiting).payload;
		await bridge.send('action_result_ack', record.agentId, { goalRevision: record.goalRevision, actionId: payload.actionId });
		report.actions.push({ actionId: result.actionId, actionType, state: result.state, reasonCode: result.reasonCode, elapsedMs: result.elapsedMs, beforeEventSequence: payload.provenance.eventSequence,
			...(['interact_block', 'interact_entity'].includes(actionType) ? { hand: payload.arguments.hand, expectedItemId: payload.arguments.expectedItemId, observedItemId: observedHandArguments(baseline, payload.arguments.hand).expectedItemId } : {}),
			...(result.state !== 'SUCCEEDED' ? { message: probeFailureMessage(result, secrets) } : {}),
		});
		if (result.state !== 'SUCCEEDED') throw new Error(`ACTION_${actionType}_${result.reasonCode}`);
		return result;
	};
	const check = async (name, run) => {
		stage = name;
		const start = Date.now();
		try { const evidence = await run(); report.checks.push({ name, status: 'PASSED', elapsedMs: Date.now() - start, evidence }); }
		catch (error) { report.checks.push({ name, status: 'FAILED', elapsedMs: Date.now() - start, reasonCode: safeError(error) }); throw error; }
	};
	const closeMenu = async () => {
		const page = await inspect('menu');
		await action('menu_close', { menuId: page.menu.type, containerId: page.menu.containerId, stateId: page.menu.stateId });
	};
	const killGoal = async (entityType) => {
		await command(`codex stop ${config.agentName}`);
		predicate = { type: 'entity_killed_by_agent', entityType, afterGoalStart: true };
		const activated = inbox.wait('goal_control', (event) => event.agentId === record.agentId && ['start', 'replace'].includes(event.payload.operation));
		report.goalSetupResponse = await command(`codex start ${config.agentName} Kill ${entityType}`);
		const goal = await activated;
		assertFact(goal.payload.goal === `Kill ${entityType}` && goal.payload.goalSpec?.predicate?.entityType === entityType, 'NEW_KILL_GOAL_NOT_TRANSMITTED');
		record.goalRevision = goal.payload.goalRevision;
		await bridge.send('agent_ready', record.agentId, { goalRevision: record.goalRevision, reconciled: false });
		return { completed: inbox.wait('goal_control', (event) => event.agentId === record.agentId && event.payload.operation === 'complete') };
	};
	try {
		await rcon.connect();
		stage = 'connect_production_bridge';
		const ready = inbox.wait('ready'); bridge.start(); const connected = await ready;
		assertFact(connected.registry.length === 0, 'ISOLATED_EMPTY_AGENT_REGISTRY_REQUIRED');
		await bridge.send('catalog_snapshot', 'server', { refreshedAtEpochMs: Date.now(), models: [{ ...PROFILE, id: PROFILE.model, displayName: 'Mechanics fixture identity, no provider', reasoningEfforts: ['high'], serviceTiers: ['priority', 'fast'] }].map(({ reasoningEffort, serviceTier, ...model }) => model) });
		const [forceLoad, ...setup] = fixtureSetupCommands();
		stage = 'load_fixture_chunks';
		await command(forceLoad);
		let chunksReady = false;
		for (let attempt = 0; attempt < 100; attempt++) {
			const response = await command('execute if loaded -4 64 -4 if loaded 40 64 12 run time query gametime');
			if (/time is \d+/i.test(response)) { chunksReady = true; break; }
			await new Promise((resolve) => setTimeout(resolve, 100));
		}
		assertFact(chunksReady, 'FIXTURE_CHUNKS_NOT_LOADED');
		stage = 'build_mechanics_fixture';
		for (const entry of setup) await command(entry);
		stage = 'summon_fixture_agent';
		const registered = inbox.wait('agent_registered');
		await command(`execute in minecraft:overworld positioned 0.5 64 0.5 run codex summon ${PROFILE.model} ${PROFILE.reasoningEffort} ${config.agentName}`);
		const event = await registered;
		record = { ...(event.payload.record ?? event.payload), agentId: event.agentId };
		report.registeredProfile = { provider: record.provider, model: record.model, reasoningEffort: record.reasoningEffort, serviceTier: record.serviceTier };
		await bridge.send('agent_ready', record.agentId, { goalRevision: record.goalRevision, reconciled: false });
		stage = 'start_fixture_agent';
		const activated = inbox.wait('goal_control', (event) => event.agentId === record.agentId && event.payload.operation === 'start');
		await command(`codex start ${config.agentName} Stay alive for 600 seconds.`);
		const goal = await activated; record.goalRevision = goal.payload.goalRevision;
		await bridge.send('agent_ready', record.agentId, { goalRevision: record.goalRevision, reconciled: false });
		const player = record.entityUuid ?? config.agentName;
		await command(`tp ${player} 0.5 64 0.5 -90 0`);
		await command(`clear ${player}`);
		await check('fresh observation and focused inventory inspection', async () => {
			const first = await observe(), second = await observe(), inventory = await inspect('inventory');
			assertFact(second.eventSequence > first.eventSequence, 'OBSERVATION_NOT_FRESH');
			assertFact(typeof second.world?.worldId === 'string', 'WORLD_ID_MISSING');
			assertFact(Array.isArray(inventory.entries) && inventory.coverage?.source !== undefined, 'INSPECTION_PAGE_MISSING');
			return { firstSequence: first.eventSequence, secondSequence: second.eventSequence, inventoryReturned: inventory.entries.length, coverage: inventory.coverage };
		});
		await check('installed recipes expose bounded pages and concrete crafting displays', async () => {
			const page = await inspect('recipes', { limit: 4 });
			assertFact(page.coverage?.source === 'installed_recipe_registry' && page.entries?.length > 0 && page.entries.length <= 4, 'RECIPE_REGISTRY_PAGE_MISSING');
			const detail = await inspect('recipes', { recipeId: 'minecraft:oak_planks', limit: 4 });
			const display = detail.entries?.find((entry) => entry.result?.alternatives?.some((item) => item.itemId === 'minecraft:oak_planks' && item.count === 4));
			assertFact(detail.recipe?.recipeId === 'minecraft:oak_planks' && display?.ingredients?.length > 0, 'RECIPE_DISPLAY_MISSING');
			assertFact(detail.craftabilityChecked === false && detail.displayItemsAreExamples === true, 'RECIPE_KNOWLEDGE_SCOPE_MISSING');
			return { registryReturned: page.entries.length, coverage: page.coverage, recipeId: detail.recipe.recipeId, layout: display.layout, result: display.result.alternatives, craftabilityChecked: detail.craftabilityChecked };
		});
		await check('mechanics inspection reports installed version and current player attributes', async () => {
			const mechanics = await inspect('mechanics');
			assertFact(mechanics.source === 'installed_version_and_current_player_attributes' && typeof mechanics.minecraftVersion === 'string', 'MECHANICS_VERSION_MISSING');
			assertFact(mechanics.configuredTicksPerSecond > 0 && mechanics.blockInteractionRange > 0 && mechanics.entityInteractionRange > 0, 'MECHANICS_RANGES_MISSING');
			assertFact(mechanics.attributes?.some((entry) => typeof entry.id === 'string' && Number.isFinite(entry.currentValue)) && typeof mechanics.input?.yaw === 'string', 'MECHANICS_INPUT_FACTS_MISSING');
			return { minecraftVersion: mechanics.minecraftVersion, configuredTicksPerSecond: mechanics.configuredTicksPerSecond, blockInteractionRange: mechanics.blockInteractionRange, entityInteractionRange: mechanics.entityInteractionRange, attributesReturned: mechanics.attributes.length, input: mechanics.input };
		});
		await check('bounded control sequence changes authoritative position', async () => {
			const before = (await observe()).position;
			await action('control_sequence', { frames: [inputFrame({ forward: 1, ticks: 6, branches: [{ condition: 'on_ground', value: true, nextFrame: 1 }] }), inputFrame({ forward: 1, ticks: 8 })], maxTicks: 40 });
			const after = (await observe()).position;
			assertFact(after.x - before.x > 0.2, 'CONTROL_DID_NOT_MOVE');
			return { before, after };
		});
		await check('mined overhead drop is collected while falling through vanilla pickup', async () => {
			await command(`tp ${player} 0.5 64 -2.5 0 -60`);
			await command(`clear ${player}`);
			await command('setblock 1 67 -2 minecraft:oak_log');
			await action('look_at', { x: 1.5, y: 67.5, z: -1.5 });
			await action('break_block', { x: 1, y: 67, z: -2, expectedBlockId: 'minecraft:oak_log', timeoutMs: 15000 });
			const drops = await inspect('entities', { entityType: 'minecraft:item' });
			const drop = drops.entries.find(entry => entry.itemId === 'minecraft:oak_log');
			assertFact(drop?.uuid && drop.position.y > 65, 'FALLING_DROP_FIXTURE_MISSING');
			const result = await action('pick_up_item', { targetSelector: drop.uuid });
			const inventory = await inspect('inventory');
			assertFact(inventory.entries.some(entry => entry.itemId === 'minecraft:oak_log' && entry.count === 1), 'FALLING_DROP_NOT_COLLECTED');
			await command(`clear ${player}`);
			return { dropPosition: drop.position, reasonCode: result.reasonCode, elapsedMs: result.elapsedMs, collectedCount: 1 };
		});
		await check('grounded drop under a canopy is collected from a supported adjacent position', async () => {
			await command(`tp ${player} 0.5 64 -1.5 -90 0`);
			const before = await inspect('inventory');
			const beforeCount = before.entries.filter(entry => entry.itemId === 'minecraft:oak_log').reduce((sum, entry) => sum + entry.count, 0);
			await command('setblock 3 64 -2 minecraft:stone');
			await command('setblock 3 66 -2 minecraft:stone');
			await command('summon minecraft:item 3.5 65 -1.5 {Item:{id:"minecraft:oak_log",count:1},PickupDelay:0s,Motion:[0d,0d,0d]}');
			await action('look_at', { x: 3.5, y: 65.1, z: -1.5 });
			const drops = await inspect('entities', { entityType: 'minecraft:item' });
			const drop = drops.entries.find(entry => entry.itemId === 'minecraft:oak_log');
			assertFact(drop?.uuid, 'CANOPY_DROP_FIXTURE_MISSING');
			const result = await action('pick_up_item', { targetSelector: drop.uuid });
			const after = (await inspect('observation')).observation;
			const inventory = await inspect('inventory');
			const afterCount = inventory.entries.filter(entry => entry.itemId === 'minecraft:oak_log').reduce((sum, entry) => sum + entry.count, 0);
			report.canopyPickup = { position: after.position, beforeCount, afterCount };
			assertFact(afterCount === beforeCount + 1, 'CANOPY_DROP_NOT_COLLECTED');
			assertFact(after.position.y < 65, 'PICKUP_DID_NOT_USE_ADJACENT_GROUND');
			await command(`clear ${player}`);
			await command('setblock 3 64 -2 minecraft:air');
			await command('setblock 3 66 -2 minecraft:air');
			return { reasonCode: result.reasonCode, elapsedMs: result.elapsedMs, position: after.position, beforeCount, afterCount, collectedCount: afterCount - beforeCount };
		});
		await check('storage menu pickup preserves cursor then deposits exact stack', async () => {
			await command(`tp ${player} 0.5 64 0.5 -90 0`);
			await action('interact_block', (fresh) => ({ x: 2, y: 64, z: 0, face: 'west', ...observedHandArguments(fresh) }));
			const before = await inspect('menu');
			await action('menu_click', menuClickArguments(before, 0));
			const carried = await inspect('menu');
			assertFact(carried.menu.cursor.itemId === 'minecraft:stone' && carried.menu.cursor.count === 4, 'CURSOR_PICKUP_NOT_PRESERVED');
			await action('menu_click', menuClickArguments(carried, 1));
			const after = await inspect('menu');
			assertFact(after.menu.cursor.count === 0 && after.entries.find((entry) => entry.slot === 1)?.count === 4, 'CURSOR_DEPOSIT_NOT_CONFIRMED');
			await closeMenu();
			return { menuType: before.menu.type, containerId: before.menu.containerId, beforeStateId: before.menu.stateId, afterStateId: after.menu.stateId, depositedCount: 4, cursorCount: after.menu.cursor.count };
		});
		await check('consuming crafting menu creates planks through ordinary clicks', async () => {
			await command(`clear ${player}`); await command(`item replace entity ${player} hotbar.0 with minecraft:oak_log 1`);
			const source = await inspect('menu', { offset: 32 });
			const sourceSlot = source.entries.find((entry) => entry.itemId === 'minecraft:oak_log');
			assertFact(sourceSlot !== undefined, 'CRAFT_INPUT_MISSING');
			await action('menu_click', menuClickArguments(source, sourceSlot.slot));
			await action('menu_click', menuClickArguments(await inspect('menu'), 1));
			const crafting = await inspect('menu');
			assertFact(crafting.entries.find((entry) => entry.slot === 0)?.itemId === 'minecraft:oak_planks', 'VANILLA_RECIPE_OUTPUT_MISSING');
			await action('menu_click', menuClickArguments(crafting, 0, 'QUICK_MOVE'));
			const after = await observe();
			const planks = (after.inventory?.items ?? []).filter((item) => item.itemId === 'minecraft:oak_planks').reduce((sum, item) => sum + item.count, 0);
			const logs = (after.inventory?.items ?? []).filter((item) => item.itemId === 'minecraft:oak_log').reduce((sum, item) => sum + item.count, 0);
			assertFact(planks === 4 && logs === 0, 'CRAFT_CONSUMPTION_MISMATCH');
			await closeMenu();
			return { planks, logs, resultClick: 'QUICK_MOVE' };
		});
		await check('water input ascends under vanilla physics', async () => {
			await command(`tp ${player} 14.5 64 2.5 0 0`);
			await action('control', inputFrame({ yaw: 0, ticks: 2 }));
			const before = await observe(); assertFact(before.player.inWater, 'WATER_FIXTURE_NOT_ENTERED');
			await action('control_sequence', { frames: [inputFrame({ yaw: 0, jump: true, ticks: 40 })], maxTicks: 45 });
			const after = await observe(); assertFact(after.position.y > before.position.y + 0.5, 'WATER_ASCENT_NOT_CONFIRMED');
			return { before: before.position, after: after.position };
		});
		await check('ladder input climbs without navigation assistance', async () => {
			await command(`tp ${player} 23.5 64 1.5 -90 0`);
			const before = await observe();
			await action('control_sequence', { frames: [inputFrame({ forward: 1, jump: true, ticks: 30 })], maxTicks: 35 });
			const after = await observe(); assertFact(after.position.y > before.position.y + 0.5, 'CLIMB_NOT_CONFIRMED');
			return { before: before.position, after: after.position, onClimbable: after.player.onClimbable };
		});
		await check('mounted boat responds to player control frames under vanilla physics', async () => {
			await command(`clear ${player}`);
			await command('summon minecraft:oak_boat 29.5 65 5.5 {Tags:["capability_probe_boat"],Rotation:[-90.0f,0.0f]}');
			await command(`tp ${player} 27.5 65 5.5 -90 0`);
			await action('control', inputFrame({ ticks: 2 }));
			const entities = await inspect('entities');
			const boat = entities.entries?.find((entry) => entry.type === 'minecraft:oak_boat');
			assertFact(typeof boat?.uuid === 'string', 'BOAT_FIXTURE_NOT_VISIBLE');
			await action('interact_entity', (fresh) => ({ targetId: boat.uuid, ...observedHandArguments(fresh) }));
			await action('control', inputFrame({ ticks: 5 }));
			const before = await observe();
			assertFact(before.player.passenger === true && before.player.vehicle?.uuid === boat.uuid, 'BOAT_MOUNT_NOT_CONFIRMED');
			await action('control_sequence', { frames: [inputFrame({ forward: 1, ticks: 20 })], maxTicks: 25 });
			const after = await observe(), distance = Math.hypot(after.position.x - before.position.x, after.position.z - before.position.z);
			assertFact(after.player.passenger === true && after.player.vehicle?.uuid === boat.uuid && distance > 0.5, 'BOAT_INPUT_DID_NOT_PROPEL');
			await action('dismount', {});
			assertFact((await observe()).player.passenger === false, 'BOAT_DISMOUNT_NOT_CONFIRMED');
			return { boatType: boat.type, mountedVehicle: boat.uuid, before: before.position, after: after.position, horizontalDistance: distance, controlTicks: 20, measurementUsesOperatorCommands: false };
		});
		await check('recipe output filters return the requested installed recipe', async () => {
			const page = await inspect('recipes', { outputItemId: 'minecraft:blaze_powder' });
			assertFact(page.entries?.some((entry) => entry.recipeId === 'minecraft:blaze_powder'), 'OUTPUT_FILTER_MISSING_RECIPE');
			return page;
		});
		await check('background program reacts through the production bridge after returning its handle', async () => {
			await command(`tp ${player} 0.5 64 0.5 -90 0`);
			await command(`effect give ${player} minecraft:instant_health 1 4 true`);
			const commands = [];
			const samples = [];
			const programEvents = [];
			const runtime = new NativeToolRuntime({ bridge: { send: async (type, agentId, payload) => {
				if (type === 'action_command' || type === 'action_cancel') commands.push({ type, actionId: payload.actionId, actionType: payload.actionType });
				return bridge.send(type, agentId, payload);
			} }, onProgramEvent: (_record, event) => programEvents.push(event), requestObservation: async () => {
				// Match the coordinator's passive inspection path. request_observation
				// explicitly wakes the planner and would interrupt its own routine.
				return inspect('observation');
			} });
			const onObservation = event => {
				if (event.agentId === record.agentId) samples.push({ sequence: event.payload.eventSequence, health: event.payload.player?.health, attention: event.payload.attention, trigger: event.payload.trigger, changedFacts: event.payload.changedFacts });
				if (event.agentId === record.agentId && event.payload.ready) runtime.updateObservation(record, event.payload, {
					eventSequence: event.payload.eventSequence, attention: event.payload.attention === true, changedFacts: event.payload.changedFacts,
				});
			};
			const onResult = event => {
				if (event.agentId !== record.agentId || !commands.some(entry => entry.actionId === event.payload.actionId)) return;
				runtime.onActionResult(record, event.payload);
				bridge.send('action_result_ack', record.agentId, { goalRevision: record.goalRevision, actionId: event.payload.actionId }).catch(error => inbox.fail(error));
			};
			bridge.on('observation', onObservation);
			bridge.on('action_result', onResult);
			let toolOrdinal = 0;
			const tool = (name, args) => runtime.execute({ agentId: record.agentId, goalRevision: record.goalRevision,
				turnId: 'background-mechanics', callId: `background-${++toolOrdinal}`, tool: normalizeMinecraftToolCall(name, args) }, record);
			try {
				const initial = await inspect('observation');
				const before = initial.observation;
				runtime.updateObservation(record, before, { eventSequence: initial.eventSequence });
				assertFact(before.player.health === 20, 'BACKGROUND_FIXTURE_HEALTH');
				const handle = await tool('runProgram', { background: true, timeoutMs: 10000, observationIntervalMs: 100,
					source: `program.onUnhandledAttention("continue_and_notify");
					program.watch(() => player.state().health < 20, { mode: "interrupt" }, async () => {
						await player.control(${JSON.stringify(inputFrame({ forward: 1, ticks: 10 }))});
					}); await player.wait(8000);` });
				assertFact(handle.state === 'RUNNING', 'BACKGROUND_HANDLE_NOT_RUNNING');
				// Damage is a declared fixture stimulus, after the native tool already returned.
				await command(`damage ${player} 2 minecraft:generic`);
				let result;
				const deadline = Date.now() + 12000;
				do {
					await new Promise(resolve => setTimeout(resolve, 100));
					result = await tool('programStatus', { programId: handle.programId });
				} while (['PREPARING', 'RUNNING', 'CANCELLING'].includes(result.state) && Date.now() < deadline);
				const after = await observe();
				const distance = Math.hypot(after.position.x - before.position.x, after.position.z - before.position.z);
				report.backgroundProgram = { handle, state: result.state, reasonCode: result.reasonCode, trigger: result.trigger, actionFailure: result.actionFailure, actions: result.actions, samples,
					receipts: result.receipts, commands, before: { position: before.position, health: before.player.health },
					after: { position: after.position, health: after.player.health }, distance };
				assertFact(result.reasonCode === 'PROGRAM_IDLE' && distance > 0.5, 'BACKGROUND_REACTION_NOT_CONFIRMED');
				assertFact(commands.some(entry => entry.type === 'action_cancel') && result.receipts?.some(receipt => receipt.actionType === 'control' && receipt.state === 'SUCCEEDED'), 'BACKGROUND_REACTION_RECEIPTS_MISSING');
				// Hold reconsideration while authorised actions continue, then answer
				// through the same decision tool available to the selected model.
				// Use a clear lane; the first movement ends against the storage fixture.
				await command(`tp ${player} 0.5 64 -2.5 -90 0`);
				const continuingBefore = (await inspect('observation')).observation;
				const commandOffset = commands.length;
				const continuous = await tool('runProgram', { background: true, timeoutMs: 10000, observationIntervalMs: 100,
					source: `program.onUnhandledAttention("continue_and_notify"); await player.wait(800);
					await player.control(${JSON.stringify(inputFrame({ forward: 1, ticks: 10 }))});
					await player.control(${JSON.stringify(inputFrame({ forward: 1, ticks: 10 }))}); await player.wait(8000);` });
				// Exceed the first hit so vanilla hurt immunity cannot swallow this
				// stimulus; do not race an instant-heal effect against the damage.
				await command(`damage ${player} 6 minecraft:generic`);
				const continuedDeadline = Date.now() + 7000;
				while (commands.slice(commandOffset).filter(entry => entry.type === 'action_command').length < 4 && Date.now() < continuedDeadline) {
					await new Promise(resolve => setTimeout(resolve, 50));
				}
				const pending = await tool('programStatus', { programId: continuous.programId });
				report.continuousProgram = { pending, providerUsed: false, decisionDriver: 'declared delayed-response fixture' };
				assertFact(pending.decision && pending.engineState === 'ACTIVE', 'CONTINUOUS_DECISION_NOT_PENDING');
				assertFact(commands.slice(commandOffset).filter(entry => entry.type === 'action_command').length === 4, 'CONTINUOUS_PROGRESS_BLOCKED_ON_MODEL');
				const stopped = await tool('respondProgram', { programId: continuous.programId, goalRevision: continuous.goalRevision,
					decisionId: pending.decision.decisionId, directive: 'pause' });
				const continuingAfter = (await inspect('observation')).observation;
				const continuedDistance = Math.hypot(continuingAfter.position.x - continuingBefore.position.x, continuingAfter.position.z - continuingBefore.position.z);
				report.continuousProgram = { pending, reasonCode: stopped.reasonCode, distance: continuedDistance, receipts: stopped.receipts,
					before: continuingBefore.position, after: continuingAfter.position,
					providerUsed: false, decisionDriver: 'declared delayed-response fixture' };
				assertFact(stopped.reasonCode === 'MODEL_PAUSED' && continuedDistance > 1, 'CONTINUOUS_PAUSE_OR_MOVEMENT_FAILED');
				assertFact(programEvents.some(event => event.programId === continuous.programId && event.event === 'program_attention'), 'CONTINUOUS_ATTENTION_NOT_DELIVERED');
				return { handle, reasonCode: result.reasonCode, distance, receipts: result.receipts, continuation: report.continuousProgram, providerUsed: false };
			} finally {
				await runtime.disposeAll();
				bridge.off('observation', onObservation);
				bridge.off('action_result', onResult);
			}
		});
		await check('measured planning lead advises once while a real bridge action keeps running', async () => {
			await command(`tp ${player} 0.5 64 0.5 -90 0`);
			const events = [], sent = [], actionIds = new Set();
			const runtime = new NativeToolRuntime({
				bridge: { send: async (type, agentId, payload) => {
					if (type === 'action_command' || type === 'action_cancel') {
						sent.push({ type, actionId: payload.actionId, actionType: payload.actionType });
						if (type === 'action_command') actionIds.add(payload.actionId);
					}
					return bridge.send(type, agentId, payload);
				} },
				planningLeadTime: () => 3000,
				onProgramEvent: (_record, event) => events.push(event),
				requestObservation: async () => inspect('observation'),
			});
			const onObservation = event => {
				if (event.agentId === record.agentId && event.payload.ready) runtime.updateObservation(record, event.payload, {
					eventSequence: event.payload.eventSequence, attention: event.payload.attention === true, changedFacts: event.payload.changedFacts,
				});
			};
			const onResult = event => {
				if (event.agentId !== record.agentId || !actionIds.has(event.payload.actionId)) return;
				runtime.onActionResult(record, event.payload);
				bridge.send('action_result_ack', record.agentId, { goalRevision: record.goalRevision, actionId: event.payload.actionId }).catch(error => inbox.fail(error));
			};
			bridge.on('observation', onObservation);
			bridge.on('action_result', onResult);
			let toolOrdinal = 0;
			const tool = (name, args) => runtime.execute({ agentId: record.agentId, goalRevision: record.goalRevision,
				turnId: 'planning-due-mechanics', callId: `planning-due-${++toolOrdinal}`, tool: normalizeMinecraftToolCall(name, args) }, record);
			try {
				const initial = await inspect('observation');
				runtime.updateObservation(record, initial.observation, { eventSequence: initial.eventSequence });
				const beforePosition = initial.observation.position;
				const handle = await tool('runProgram', { background: true, timeoutMs: 4000,
					source: `program.onUnhandledAttention("continue_and_notify");
					await player.control(${JSON.stringify(inputFrame({ forward: 1, ticks: 120 }))});
					await player.wait(1000);` });
				const deadlineEpochMs = handle.deadlineEpochMs;
				const dueDeadline = Date.now() + 3000;
				while (!events.some(event => event.programId === handle.programId && event.event === 'program_planning_due') && Date.now() < dueDeadline) {
					await new Promise(resolve => setTimeout(resolve, 50));
				}
				const dueEvents = events.filter(event => event.programId === handle.programId && event.event === 'program_planning_due');
				assertFact(dueEvents.length === 1, 'PLANNING_DUE_EVENT_NOT_ONCE');
				const due = dueEvents[0];
				const status = await tool('programStatus', { programId: handle.programId });
				assertFact(due.status?.engineState === 'ACTIVE' && due.status?.decision === undefined, 'PLANNING_DUE_SYNTHETIC_DECISION');
				assertFact(due.status?.planningLeadMs === 3000 && due.status?.deadlineEpochMs === deadlineEpochMs && status.deadlineEpochMs === deadlineEpochMs, 'PLANNING_DUE_DEADLINE_CHANGED');
				assertFact(due.status?.action?.state === 'RUNNING', 'PLANNING_DUE_BODY_NOT_RUNNING');
				assertFact(sent.filter(entry => entry.type === 'action_cancel').length === 0, 'PLANNING_DUE_CANCELLED_BODY');
				await new Promise(resolve => setTimeout(resolve, 200));
				const afterPosition = (await inspect('observation')).observation.position;
				const movementDistance = Math.hypot(afterPosition.x - beforePosition.x, afterPosition.z - beforePosition.z);
				assertFact(movementDistance > 0.2, 'PLANNING_DUE_MOTION_STOPPED');
				assertFact(events.filter(event => event.programId === handle.programId && event.event === 'program_planning_due').length === 1, 'PLANNING_DUE_REPEATED');
				const cancelled = await tool('cancelProgram', { programId: handle.programId, goalRevision: record.goalRevision });
				assertFact(sent.some(entry => entry.type === 'action_cancel' && entry.actionId === sent.find(command => command.type === 'action_command')?.actionId), 'PLANNING_DUE_CANCEL_NOT_BRIDGED');
				assertFact(cancelled.state === 'CANCELLED' && cancelled.reasonCode === 'MODEL_CANCELLED', 'PLANNING_DUE_CANCEL_NOT_RELEASED');
				const bodyResult = cancelled.receipts?.find(receipt => receipt.state === 'CANCELLED');
				assertFact(bodyResult?.state === 'CANCELLED', 'PLANNING_DUE_BODY_RECEIPT_MISSING');
				return { programId: handle.programId, planningLeadMs: 3000, deadlineEpochMs, dueEvents: dueEvents.length,
					bodyAction: due.status.action.actionType, bodyActionStateAtAdvisory: due.status.action.state, movementDistance,
					beforePosition, afterPosition, fixtureFloor: 'known clear y=63 floor at x=-4..40,z=-4..12', cancelledThroughActualBridge: true, providerUsed: false,
					fixtureLatency: 'declared synthetic planning-lead measurement; physical motion and cancellation used the live bridge' };
			} finally {
				await runtime.disposeAll();
				bridge.off('observation', onObservation);
				bridge.off('action_result', onResult);
			}
		});
		await check('single-use mode consumes exactly one instantaneous item', async () => {
			await command(`tp ${player} 0.5 64 0.5 -90 -60`);
			await command(`clear ${player}`);
			await command(`item replace entity ${player} hotbar.0 with minecraft:snowball 5`);
			await action('control', inputFrame({ pitch: -60, ticks: 1 }));
			const receipt = await action('use_item', { durationMs: 1000, mode: 'once' });
			const remaining = (await observe()).inventory.items.filter((item) => item.itemId === 'minecraft:snowball').reduce((sum, item) => sum + item.count, 0);
			assertFact(remaining === 4, 'SINGLE_USE_CONSUMPTION_MISMATCH');
			return { remaining, receipt };
		});
		await check('filtered entities and explicit bow aim work through the production bridge', async () => {
			await command('summon minecraft:pig 8.5 64 0.5 {NoAI:1b,Tags:["precision_probe"]}');
			await command(`tp ${player} 0.5 64 0.5 -90 0`);
			await command(`clear ${player}`);
			await command(`item replace entity ${player} hotbar.0 with minecraft:bow`);
			await command(`item replace entity ${player} hotbar.1 with minecraft:arrow 8`);
			const page = await inspect('entities', { entityType: 'minecraft:pig' });
			assertFact(page.entries?.length > 0 && page.entries.every((entry) => entry.type === 'minecraft:pig'), 'ENTITY_FILTER_MISMATCH');
			const targetId = page.entries[0].uuid;
			const fixed = await action('use_ranged', { targetId, drawDurationMs: 1000, timeoutMs: 3000, aimX: 8.5, aimY: 66, aimZ: 0.5 });
			const tracked = await action('use_ranged', { targetId, drawDurationMs: 1000, timeoutMs: 3000, trackTarget: true });
			return { targetId, fixed, tracked };
		});
		await check('observed dragon parts resolve to real melee targets', async () => {
			await command('fill -4 69 -14 20 82 12 minecraft:air');
			await command('fill 6 68 -11 10 68 -8 minecraft:stone');
			await command('summon minecraft:ender_dragon 8 70 0 {DragonPhase:7,Tags:["dragon_probe"]}');
			await new Promise((resolve) => setTimeout(resolve, 300));
			await command(`tp ${player} 8.5 69 -9.5 0 0`);
			await command(`effect give ${player} minecraft:resistance 60 4 true`);
			await command(`item replace entity ${player} hotbar.0 with minecraft:diamond_sword`);
			await action('control', inputFrame({ yaw: 0, ticks: 1 }));
			await command('data merge entity @e[tag=dragon_probe,limit=1] {NoAI:1b}');
			await action('look_at', { x: 8.5, y: 70, z: 0.5 });
			const dragonObserver = await observe();
			report.dragonObserver = { position: dragonObserver.position, view: dragonObserver.view,
				health: dragonObserver.player?.health, suffocating: dragonObserver.player?.suffocating,
				rayTarget: dragonObserver.interaction?.rayTarget };
			report.dragonPosition = await command('data get entity @e[tag=dragon_probe,limit=1] Pos');
			const page = await inspect('entities', { entityType: 'minecraft:ender_dragon' });
			report.dragonFixture = page;
			const head = page.entries?.find((entry) => entry.partName === 'head');
			assertFact(head?.uuid && head?.parentId && head.pickable, 'DRAGON_HEAD_NOT_OBSERVED');
			const point = head.position;
			await command(`tp ${player} ${point.x} ${point.y} ${point.z - 2} 0 0`);
			const before = await command('data get entity @e[tag=dragon_probe,limit=1] Health');
			const receipt = await action('attack', { targetId: head.uuid, timeoutMs: 3000 });
			const after = await command('data get entity @e[tag=dragon_probe,limit=1] Health');
			assertFact(before !== after, 'DRAGON_HEALTH_UNCHANGED');
			const { completed } = await killGoal('minecraft:ender_dragon');
			await command('data merge entity @e[tag=dragon_probe,limit=1] {Health:1.0f}');
			await new Promise((resolve) => setTimeout(resolve, 650));
			const lethalAt = Date.now();
			await action('attack', { targetId: head.uuid, timeoutMs: 3000 });
			const lethalRevision = record.goalRevision;
			await command(`codex stop ${config.agentName}`);
			const resumed = inbox.wait('goal_control', (event) => event.agentId === record.agentId && event.payload.operation === 'resume');
			await command(`codex resume ${config.agentName}`);
			const resumedGoal = await resumed;
			record.goalRevision = resumedGoal.payload.goalRevision;
			assertFact(record.goalRevision > lethalRevision, 'DRAGON_RESUME_DID_NOT_ADVANCE_REVISION');
			await bridge.send('agent_ready', record.agentId, { goalRevision: record.goalRevision, reconciled: false });
			await command('save-all flush');
			await completed;
			const completionDelayMs = Date.now() - lethalAt;
			assertFact(completionDelayMs >= 8000, 'DRAGON_CREDIT_WAS_PREMATURE');
			return { head, before, after, receipt, completionDelayMs, pauseResumeDuringDeath: true, savedDuringDeath: true };
		});
		await check('a bed explosion receives causal agent kill credit', async () => {
			await command('execute in minecraft:the_nether run forceload add 0 0');
			for (let i = 0; i < 100; i++) {
				if (/time is \d+/i.test(await command('execute in minecraft:the_nether if loaded 0 200 0 run time query gametime'))) break;
				await new Promise((resolve) => setTimeout(resolve, 100));
			}
			await command('execute in minecraft:the_nether run fill 0 199 0 10 199 10 minecraft:obsidian');
			await command(`execute in minecraft:the_nether run tp ${player} 2.5 200 4.5 -90 20`);
			await command(`effect give ${player} minecraft:resistance 60 4 true`);
			await command(`item replace entity ${player} hotbar.0 with minecraft:air`);
			await command('execute in minecraft:the_nether run setblock 4 200 4 minecraft:red_bed[facing=east,part=foot]');
			await command('execute in minecraft:the_nether run setblock 5 200 4 minecraft:red_bed[facing=east,part=head]');
			await command('execute in minecraft:the_nether run summon minecraft:pig 5.5 200 5.5 {NoAI:1b,Health:1.0f,Tags:["bed_probe"]}');
			const { completed } = await killGoal('minecraft:pig');
			await action('interact_block', (fresh) => ({ x: 4, y: 200, z: 4, face: 'west', ...observedHandArguments(fresh) }));
			await completed;
			return { completionVerified: true, damageSource: 'bad_respawn_point', fixtureDimension: 'minecraft:the_nether' };
		});
		report.status = 'PASSED';
	} catch (error) { failure = error; report.failure = safeError(error); report.failureMessage = probeFailureMessage(error, secrets); report.failureStage = stage; }
	finally {
		stage = 'cleanup_fixture';
		if (record) { try { if (!rconResponsive) throw new Error('RCON_UNRESPONSIVE'); await command(`codex stop ${config.agentName}`); report.cleanup.agentStopped = true; } catch { report.cleanup.agentStopped = false; } }
		try { if (!rconResponsive) throw new Error('RCON_UNRESPONSIVE'); await command('forceload remove -16 -16 48 32'); report.cleanup.fixtureChunksReleased = true; } catch { report.cleanup.fixtureChunksReleased = false; }
		try { if (rconResponsive) await command('execute in minecraft:the_nether run forceload remove 0 0'); } catch { /* Isolated world teardown also releases this fixture. */ }
		bridge.stop(); inbox.close(); await rcon.close();
		report.cleanup.bridgeClosed = true; report.cleanup.rconClosed = true;
		report.elapsedMs = Date.now() - startedAt;
		await mkdir(config.runDirectory, { recursive: true });
		report.bridgeAuditFile = 'player-capability-bridge-audit.json';
		await writeFile(join(config.runDirectory, report.bridgeAuditFile), `${JSON.stringify(bridgeAudit, null, 2)}\n`, 'utf8');
		await writeFile(join(config.runDirectory, 'player-capability-report.json'), `${JSON.stringify(report, null, 2)}\n`, 'utf8');
	}
	return { report, exitCode: failure === null && report.status === 'PASSED' ? 0 : 1 };
}

class ProbeInbox {
	#events = []; #waiting = []; #failure = null; #timeout;
	constructor(bridge, timeout) {
		this.#timeout = timeout;
		bridge.on('message', (event) => this.#accept(event));
		bridge.on('ready', (event) => this.#accept({ type: 'ready', ...event }));
		bridge.on('protocolError', (error) => this.fail(error));
		bridge.on('transportError', (error) => this.fail(error));
	}
	wait(type, predicate = () => true) {
		if (this.#failure) return Promise.reject(this.#failure);
		const index = this.#events.findIndex((event) => event.type === type && predicate(event));
		if (index >= 0) return Promise.resolve(this.#events.splice(index, 1)[0]);
		const pending = new Promise((resolve, reject) => {
			const waiter = { type, predicate, resolve, reject, timer: null };
			waiter.timer = setTimeout(() => { this.#waiting = this.#waiting.filter((entry) => entry !== waiter); reject(new Error(`PROBE_TIMEOUT_${type}`)); }, this.#timeout);
			this.#waiting.push(waiter);
		});
		pending.catch(() => {});
		return pending;
	}
	fail(error) { this.#failure = error; for (const waiter of this.#waiting.splice(0)) { clearTimeout(waiter.timer); waiter.reject(error); } }
	close() { this.fail(new Error('PROBE_CLOSED')); this.#events = []; }
	#accept(event) {
		const index = this.#waiting.findIndex((waiter) => waiter.type === event.type && waiter.predicate(event));
		if (index >= 0) { const [waiter] = this.#waiting.splice(index, 1); clearTimeout(waiter.timer); waiter.resolve(event); }
		else { this.#events.push(event); if (this.#events.length > 256) this.#events.shift(); }
	}
}

function assertFact(condition, code) { if (!condition) throw new Error(code); }
function safeError(error) { return typeof error?.code === 'string' ? error.code.slice(0, 128) : String(error?.message ?? 'PROBE_FAILED').replace(/[^A-Za-z0-9_:.-]/g, '_').slice(0, 160); }

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
	try { const { report, exitCode } = await runPlayerCapabilityProbe(parseProbeArguments(process.argv.slice(2))); process.stdout.write(`${JSON.stringify({ status: report.status, kind: report.kind, checks: report.checks.length, failure: report.failure ?? null })}\n`); process.exitCode = exitCode; }
	catch (error) { process.stderr.write(`${safeError(error)}\n`); process.exitCode = 1; }
}
