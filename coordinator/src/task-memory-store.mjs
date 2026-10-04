import { createHash } from 'node:crypto';
import { AtomicAgentStore } from './observed-memory-store.mjs';

const STATIONS = new Set(['minecraft:crafting_table', 'minecraft:furnace', 'minecraft:blast_furnace', 'minecraft:smoker', 'minecraft:chest', 'minecraft:barrel', 'minecraft:bed']);
const KINDS = new Set(['place', 'route', 'progress', 'lesson']);
const MAX_ENTRIES = 256;
const MAX_TRAIL = 128;

// A world retains shared lookup semantics, but an agent's recovery history and
// authored capacity belong in its own atomic record. Stable partition keys avoid
// snapshot garbage; the small manifest publishes new owners after their writes.
const taskPartitions = {
	split(state) {
		const parts = new Map();
		const owner = (agentId) => {
			if (!parts.has(agentId)) parts.set(agentId, { version: 1, worldId: state.worldId, dimension: state.dimension, agentId,
				agent: state.agents[agentId] ?? null, entries: [] });
			return parts.get(agentId);
		};
		for (const agentId of Object.keys(state.agents)) owner(agentId);
		for (const entry of state.entries) owner(entry.agentId).entries.push(entry);
		return { manifest: { version: 2, worldId: state.worldId, dimension: state.dimension, revision: state.revision,
			owners: [...parts.keys()], assets: state.assets }, parts };
	},
	keys(manifest) {
		if (manifest.version !== 2) return null; // Read legacy files without rewriting on load.
		if (!Array.isArray(manifest.owners) || new Set(manifest.owners).size !== manifest.owners.length) throw new Error('INVALID_TASK_MEMORY');
		for (const agentId of manifest.owners) requireAgent({ agentId });
		return manifest.owners;
	},
	join(manifest, parts) {
		const state = { version: 1, worldId: manifest.worldId, dimension: manifest.dimension, revision: manifest.revision,
			agents: {}, entries: [], assets: manifest.assets };
		for (const [agentId, part] of parts) {
			if (!part || part.version !== 1 || part.worldId !== state.worldId || part.dimension !== state.dimension || part.agentId !== agentId
				|| !Array.isArray(part.entries) || part.entries.some(entry => entry.agentId !== agentId || !Number.isSafeInteger(entry.order) || !Number.isSafeInteger(entry.updatedRevision))) throw new Error('INVALID_TASK_MEMORY');
			if (part.agent !== null) state.agents[agentId] = part.agent;
			state.entries.push(...part.entries);
		}
		state.entries.sort((a, b) => a.order - b.order);
		// An interrupted manifest replacement may leave an existing owner's newer
		// atomic file. Keep update ordering monotonic when that record is recovered.
		state.revision = state.entries.reduce((revision, entry) => Math.max(revision, entry.updatedRevision), state.revision);
		return state;
	},
};

/** Durable task evidence. It describes options; it never selects or executes gameplay. */
export class TaskMemoryStore {
	#disk; #worlds = new Map(); #loads = new Map(); #writes = new Map(); #errors = new Map(); #dirty = new Set(); #authored = new Map();
	constructor({ directory = null } = {}) { this.#disk = new AtomicAgentStore({ directory, namespace: 'task-world', partition: taskPartitions }); }
	async #world(scope) {
		const key = scopeKey(scope);
		if (this.#worlds.has(key)) return this.#worlds.get(key);
		if (!this.#loads.has(key)) {
			const pending = this.#disk.read(key).then((saved) => {
				if (saved !== null) validateSavedState(saved, scope);
				const state = saved ?? { version: 1, revision: 0, worldId: scope.worldId, dimension: scope.dimension, agents: {}, entries: [], assets: [] };
				// Legacy records used insertion order. Preserve it for pagination while
				// giving replacements a separate durable summary-selection revision.
				state.entries.forEach((entry, index) => { entry.order ??= index; entry.updatedRevision ??= 0; });
				this.#worlds.set(key, state); return state;
			}).finally(() => { if (this.#loads.get(key) === pending) this.#loads.delete(key); });
			this.#loads.set(key, pending);
		}
		return this.#loads.get(key);
	}
	#save(scope, state) {
		const key = scopeKey(scope);
		state.revision++;
		this.#dirty.add(key);
		// Coalesce discoveries from one observation batch without delaying actions on disk I/O.
		if (!this.#writes.has(key)) {
			const write = new Promise((resolve) => setImmediate(resolve)).then(async () => {
				this.#dirty.delete(key);
				// AtomicAgentStore captures state synchronously at write(). Only these
				// exact authored values depend on this snapshot's outcome.
				const authored = state.entries.filter((value) => this.#authored.has(value));
				let failure;
				try { await this.#disk.write(key, state); this.#errors.delete(key); }
				catch (error) { this.#errors.set(key, error.message); failure = new Error(`TASK_MEMORY_WRITE_FAILED: ${error.message}`); }
				for (const value of authored) {
					const pending = this.#authored.get(value);
					this.#authored.delete(value);
					if (failure) pending.reject(failure); else pending.resolve();
				}
			})
				.finally(() => {
					this.#writes.delete(key);
					// Mutations arriving after the disk snapshot must persist without
					// relying on another observation or an orderly shutdown.
					if (this.#dirty.has(key)) this.#save(scope, state);
				});
			this.#writes.set(key, write);
		}
	}
	async flush() {
		await Promise.all(this.#loads.values());
		do {
			await Promise.all(this.#writes.values());
		} while (this.#writes.size > 0);
		if (this.#errors.size) throw new Error(`TASK_MEMORY_WRITE_FAILED: ${[...this.#errors.values()][0]}`);
	}
	async observe(scope, observation = {}) {
		requireAgent(scope);
		const state = await this.#world(scope);
		const agent = state.agents[scope.agentId] ??= { trail: [], omittedWaypoints: 0, deaths: [], lastLive: null };
		const death = observation.death;
		const position = vector(death ?? observation.position ?? observation.player?.position ?? observation.player);
		const tick = observation.world?.gameTime ?? observation.worldTick ?? null;
		const at = observation.observedAtEpochMs ?? death?.diedAtEpochMs ?? null;
		let changed = false;
		if (death && position) {
			const key = `${death.diedAtEpochMs ?? ''}:${position.x},${position.y},${position.z}:${death.cause ?? ''}`;
			const existing = agent.deaths.find((d) => d.key === key);
			if (!existing) {
				agent.deaths.push({ key, position, cause: death.cause ?? 'unknown', diedAtEpochMs: death.diedAtEpochMs ?? at,
					lostInventory: stacks(observation.lastLiveInventory ?? agent.lastLive?.inventory),
					outboundTrail: agent.trail.map((p) => ({ ...p })), omittedWaypoints: agent.omittedWaypoints,
					reverseVerified: false, availability: 'unverified', source: 'observed_death' });
				if (agent.deaths.length > 32) agent.deaths.splice(0, agent.deaths.length - 32);
				agent.trail = []; agent.omittedWaypoints = 0; agent.lastLive = null; changed = true;
			} else if (!existing.lostInventory.length && stacks(observation.lastLiveInventory).length) {
				existing.lostInventory = stacks(observation.lastLiveInventory); changed = true;
			}
		} else if (observation.ready !== false && observation.player?.dead !== true && position) {
			const inventory = !Array.isArray(observation.inventory?.items) ? agent.lastLive?.inventory ?? [] : stacks(observation.inventory);
			if (JSON.stringify(agent.lastLive?.inventory) !== JSON.stringify(inventory)) changed = true;
			agent.lastLive = { position, inventory, tick, observedAtEpochMs: at };
			const previous = agent.trail.at(-1);
			if (!previous || distanceSquared(previous, position) >= 4) {
				agent.trail.push({ ...position, tick }); changed = true;
				if (agent.trail.length > MAX_TRAIL) { agent.trail.splice(1, 1); agent.omittedWaypoints++; }
			}
		}
		for (const block of [...(observation.blocks ?? []), ...(observation.landmarks ?? []), ...(observation.nearbyContainers ?? [])]) {
			const position = vector(block.position ?? block);
			if (!position || typeof block.blockId !== 'string' || block.visible === false) continue;
			const key = `${scope.agentId}:${position.x},${position.y},${position.z}`;
			let asset = state.assets.find((a) => a.key === key);
			if (asset && block.blockId !== asset.blockId) { asset.availability = 'observed_changed'; asset.lastSeenTick = tick; changed = true; }
			if (!STATIONS.has(block.blockId) && !block.blockId?.endsWith('_bed')) continue;
			if (!asset) {
				asset = { key, blockId: block.blockId, position, seenBy: [scope.agentId], source: 'observed_block', availability: 'last_observed', lastSeenTick: tick, observedAtEpochMs: at };
				state.assets.push(asset); changed = true;
				if (state.assets.length > 128) state.assets.shift();
			}
			if (asset.blockId !== block.blockId || asset.availability !== 'last_observed') { asset.blockId = block.blockId; asset.availability = 'last_observed'; changed = true; }
			asset.lastSeenTick = tick; asset.observedAtEpochMs = at;
		}
		if (changed) this.#save(scope, state);
	}
	async remember(scope, entry) {
		requireAgent(scope);
		entry = validateTaskEntry(entry);
		const state = await this.#world(scope);
		const value = { ...entry, agentId: scope.agentId, goalRevision: scope.goalRevision, source: 'model_authored', historical: true };
		for (;;) {
			const index = state.entries.findIndex((e) => e.agentId === scope.agentId && e.key === entry.key);
			const full = state.entries.filter((e) => e.agentId === scope.agentId).length >= MAX_ENTRIES;
			const retired = index < 0 && full
				? state.entries.findIndex((e) => e.agentId === scope.agentId && e.status === 'retired') : -1;
			if (index < 0 && full && retired < 0) throw new Error('TASK_MEMORY_FULL: replace or retire an existing entry');
			// Do not overwrite (or evict) an authored version before its snapshot
			// settles. Recheck after waiting: another same-key caller may go first.
			const previous = this.#authored.get(state.entries[index < 0 ? retired : index]);
			if (previous) { await previous.promise.catch(() => {}); continue; }
			value.order = index < 0 ? state.entries.reduce((maximum, entry) => Math.max(maximum, entry.order), -1) + 1 : state.entries[index].order;
			value.updatedRevision = state.revision + 1;
			if (retired >= 0) state.entries.splice(retired, 1);
			if (index < 0) state.entries.push(value); else state.entries[index] = value;
			break;
		}
		const pending = Promise.withResolvers();
		this.#authored.set(value, pending);
		this.#save(scope, state);
		await pending.promise;
		return structuredClone(publicEntry(value));
	}
	async query(scope, { kind = 'all', text = '', offset = 0, limit = 20, dimension = scope.dimension } = {}) {
		requireAgent(scope);
		// A mental lookup may recall another dimension in this same world. It
		// grants no observation or travel, and never writes outside the live scope.
		scope = { ...scope, dimension };
		if (!['all', 'deaths', 'assets', 'trail', ...KINDS].includes(kind) || typeof text !== 'string' || text.length > 256 || !Number.isSafeInteger(offset) || offset < 0 || !Number.isSafeInteger(limit) || limit < 1 || limit > 64) throw new TypeError('Invalid task memory query');
		const state = await this.#world(scope);
		const own = state.agents[scope.agentId];
		const records = [...state.entries.filter((e) => e.agentId === scope.agentId || e.shared === true).map(publicEntry),
			...state.assets.filter((a) => a.seenBy.includes(scope.agentId)).map((a) => ({ kind: 'assets', ...a })),
			...(own?.deaths ?? []).map((d) => ({ kind: 'deaths', ...d })),
			...(kind === 'trail' ? [{ kind: 'trail', waypoints: own?.trail ?? [], omittedWaypoints: own?.omittedWaypoints ?? 0, reverseVerified: false, source: 'observed_positions' }] : [])]
			.filter((e) => (kind === 'all' || e.kind === kind) && JSON.stringify(e).toLowerCase().includes(text.toLowerCase()));
		return { worldId: scope.worldId, dimension: scope.dimension, historical: true, offset, entries: structuredClone(records.slice(offset, offset + limit)), total: records.length, nextOffset: offset + limit < records.length ? offset + limit : null };
	}
	async summary(scope) {
		requireAgent(scope);
		const state = await this.#world(scope);
		const own = state.agents[scope.agentId];
		const entries = state.entries.filter((e) => (e.agentId === scope.agentId || e.shared === true) && e.status !== 'retired')
			.sort((a, b) => a.updatedRevision - b.updatedRevision || a.order - b.order);
		const deaths = own?.deaths ?? [];
		// Keep earlier equipment losses alongside the latest death, rather than
		// letting repeated empty-handed deaths hide the first useful recovery site.
		const selectedDeaths = [...new Map([...deaths.filter((d) => d.lostInventory.length).slice(-3), ...deaths.slice(-2)].map((d) => [d.key, d])).values()];
		const result = { worldId: scope.worldId, dimension: scope.dimension, revision: state.revision, currentGoalRevision: scope.goalRevision, historical: true,
			progress: entries.filter((e) => e.kind === 'progress').sort((a,b) => Number(a.goalRevision === scope.goalRevision) - Number(b.goalRevision === scope.goalRevision)).slice(-2).map(compactEntry),
			routes: entries.filter((e) => e.kind === 'route').slice(-4).map(compactEntry),
			places: entries.filter((e) => e.kind === 'place').slice(-4).map(compactEntry),
			lessons: entries.filter((e) => e.kind === 'lesson').slice(-3).map(compactEntry),
			assets: state.assets.filter((a) => a.seenBy.includes(scope.agentId)).slice(-6).map(({ seenBy, ...a }) => a),
			deaths: selectedDeaths.map(({ outboundTrail, lostInventory, key, ...d }) => ({ ...d, cause: d.cause.slice(0, 128), lostInventory: lostInventory.slice(0, 8), omittedInventoryStacks: Math.max(0, lostInventory.length - 8), outboundWaypointCount: outboundTrail.length })),
			totals: { entries: entries.length, assets: state.assets.filter((a) => a.seenBy.includes(scope.agentId)).length, deaths: deaths.length },
			query: 'Use taskMemory query for route waypoints, earlier records and pagination. Reobserve routes and drops before acting.' };
		while (Buffer.byteLength(JSON.stringify(result)) > 6000) {
			const longest = ['lessons', 'places', 'routes', 'assets', 'progress', 'deaths'].find((k) => result[k].length > 1);
			if (!longest) {
				const remaining = ['lessons', 'places', 'routes', 'assets', 'progress', 'deaths'].find((k) => result[k].length);
				if (!remaining) break; result[remaining].shift();
			} else result[longest].shift();
		}
		return result;
	}
}

export function validateTaskEntry(entry) {
	if (entry === null || typeof entry !== 'object' || Array.isArray(entry)) throw new TypeError('Task entry must be an object');
	const allowed = ['kind', 'key', 'label', 'summary', 'position', 'from', 'to', 'waypoints', 'status', 'shared'];
	if (Object.keys(entry).some((k) => !allowed.includes(k)) || !KINDS.has(entry.kind)) throw new TypeError('Unsupported task entry');
	for (const [key, max] of [['key', 128], ['label', 128], ['summary', 1024]]) {
		if (typeof entry[key] !== 'string' || !entry[key].trim() || entry[key].length > max) throw new TypeError(`Task entry requires ${key} up to ${max} characters`);
	}
	if (entry.shared !== undefined && typeof entry.shared !== 'boolean') throw new TypeError('shared must be boolean');
	if (entry.status !== undefined && !['active', 'retired'].includes(entry.status)) throw new TypeError('status must be active or retired');
	for (const key of ['from', 'to']) if (entry[key] !== undefined && (typeof entry[key] !== 'string' || !entry[key].trim() || entry[key].length > 128)) throw new TypeError(`Invalid route ${key}`);
	if (entry.position !== undefined && !vector(entry.position)) throw new TypeError('Invalid task position');
	if (entry.waypoints !== undefined && (!Array.isArray(entry.waypoints) || entry.waypoints.length > 64 || entry.waypoints.some((p) => !vector(p)))) throw new TypeError('Invalid task waypoints');
	if (entry.kind === 'place' && !vector(entry.position)) throw new TypeError('Places require a position');
	if (entry.kind === 'route' && (!['from', 'to'].every((k) => typeof entry[k] === 'string' && entry[k].trim() && entry[k].length <= 128)
		|| !Array.isArray(entry.waypoints) || entry.waypoints.length < 2 || entry.waypoints.length > 64 || entry.waypoints.some((p) => !vector(p)))) throw new TypeError('Routes require from, to and 2..64 waypoints');
	return { ...entry, ...(entry.position ? { position: vector(entry.position) } : {}), ...(entry.waypoints ? { waypoints: entry.waypoints.map(vector) } : {}) };
}

function scopeKey(scope) {
	if (!scope || ![scope.worldId, scope.dimension].every((v) => typeof v === 'string' && v.length > 0 && v.length <= 256)
		|| scope.agentId !== undefined && (typeof scope.agentId !== 'string' || !scope.agentId || ['__proto__', 'constructor', 'prototype'].includes(scope.agentId))) throw new TypeError('Task memory requires an observed world and dimension');
	return createHash('sha256').update(JSON.stringify([scope.worldId, scope.dimension])).digest('hex');
}
function vector(p) { return p && ['x', 'y', 'z'].every((k) => typeof p[k] === 'number' && Number.isFinite(p[k]) && Math.abs(p[k]) <= (k === 'y' ? 2048 : 30_000_000)) ? { x: p.x, y: p.y, z: p.z } : null; }
function stacks(inventory) { return (Array.isArray(inventory) ? inventory : inventory?.items ?? []).filter((i) => typeof i?.itemId === 'string' && i.itemId.length <= 256 && Number.isSafeInteger(i.count) && i.count > 0).slice(0, 64).map(({ itemId, count }) => ({ itemId, count })); }
function requireAgent(scope) { if (typeof scope?.agentId !== 'string' || !scope.agentId || scope.agentId.length > 256 || ['__proto__', 'constructor', 'prototype'].includes(scope.agentId)) throw new TypeError('Task memory requires an agent identity'); }
function publicEntry({ order, updatedRevision, ...entry }) { return entry; }
function compactEntry({ waypoints, summary, order, updatedRevision, ...entry }) { return { ...entry, summary: summary.slice(0, 256), ...(waypoints ? { waypointCount: waypoints.length } : {}) }; }
function validateSavedState(saved, scope) {
	if (saved.version !== 1 || saved.worldId !== scope.worldId || saved.dimension !== scope.dimension || !Number.isSafeInteger(saved.revision) || saved.revision < 0 || !Array.isArray(saved.entries) || !Array.isArray(saved.assets) || saved.assets.length > 128 || !saved.agents || typeof saved.agents !== 'object' || Array.isArray(saved.agents)) throw new Error('INVALID_TASK_MEMORY');
	const counts = new Map();
	for (const e of saved.entries) {
		const { agentId, goalRevision, source, historical, order, updatedRevision, ...entry } = e;
		requireAgent({ agentId }); validateTaskEntry(entry);
		if ([order, updatedRevision].some(value => value !== undefined && (!Number.isSafeInteger(value) || value < 0))) throw new Error('INVALID_TASK_MEMORY');
		counts.set(agentId, (counts.get(agentId) ?? 0) + 1);
		if (counts.get(agentId) > MAX_ENTRIES) throw new Error('INVALID_TASK_MEMORY');
	}
	for (const a of saved.assets) if (!vector(a.position) || typeof a.blockId !== 'string' || !Array.isArray(a.seenBy) || a.seenBy.some((agentId) => { try { requireAgent({ agentId }); return false; } catch { return true; } })) throw new Error('INVALID_TASK_MEMORY');
	for (const [agentId, agent] of Object.entries(saved.agents)) {
		requireAgent({ agentId });
		if (!Array.isArray(agent.trail) || agent.trail.length > MAX_TRAIL || agent.trail.some((p) => !vector(p)) || !Array.isArray(agent.deaths) || agent.deaths.length > 32 || agent.deaths.some((d) => !vector(d.position) || typeof d.key !== 'string' || typeof d.cause !== 'string' || !Array.isArray(d.outboundTrail) || d.outboundTrail.length > MAX_TRAIL || d.outboundTrail.some((p) => !vector(p)) || !Array.isArray(d.lostInventory) || d.lostInventory.length > 64)) throw new Error('INVALID_TASK_MEMORY');
	}
}
function distanceSquared(a, b) { return ['x', 'y', 'z'].reduce((n, k) => n + (a[k] - b[k]) ** 2, 0); }
