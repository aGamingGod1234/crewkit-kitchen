#!/usr/bin/env node
// Turns coordinator trace files into the per-action latency chain:
//   event -> model -> tool request -> dispatch -> bridge send -> Java start -> Java end -> result to model -> next request
// and reports medians, p90 and how much of the run the body sat idle.
//
// Usage: node scripts/trace-latency.mjs <coordinator.jsonl>... [--agent <id>] [--rows <n>] [--json]
// Pass the public trace (coordinator.jsonl, plus .1 if rotated), not the private mirror, or every row counts twice.
// Rows written before `at`/`mono` existed carry no timestamps: the chain stages then read "n/a" and the report
// falls back to the duration fields that were always recorded (decision segments, model calls, queue waits).
import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

const ACTION_KINDS = new Set(['action', 'sequence', 'startAction', 'replaceAction', 'program', 'queueProgram']);

export function parseTrace(text) {
	const rows = [];
	for (const line of text.split(/\r?\n/)) {
		if (line.length === 0) continue;
		try {
			const row = JSON.parse(line);
			if (row !== null && typeof row === 'object' && typeof row.event === 'string') rows.push(row);
		} catch { /* a torn last line of a live file */ }
	}
	return rows;
}

export function stats(values) {
	const sorted = values.filter((value) => Number.isFinite(value)).sort((left, right) => left - right);
	if (sorted.length === 0) return { n: 0, median: null, p90: null, mean: null };
	const rank = (fraction) => sorted[Math.min(sorted.length - 1, Math.ceil(fraction * sorted.length) - 1)];
	const middle = sorted.length >> 1;
	const median = sorted.length % 2 === 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
	return { n: sorted.length, median, p90: rank(0.9), mean: sorted.reduce((sum, value) => sum + value, 0) / sorted.length };
}

/** Milliseconds from row a to row b: monotonic when both rows share a process clock, wall clock otherwise. */
function between(a, b) {
	if (a === undefined || b === undefined || a === null || b === null) return null;
	const wall = Number.isFinite(a.at) && Number.isFinite(b.at) ? b.at - a.at : null;
	if (Number.isFinite(a.mono) && Number.isFinite(b.mono)) {
		const mono = b.mono - a.mono;
		if (wall === null || Math.abs(mono - wall) <= 50) return mono;
	}
	return wall;
}

function diff(later, earlier) {
	return Number.isFinite(later) && Number.isFinite(earlier) && later > 0 && earlier > 0 ? later - earlier : null;
}

/** Builds one record per dispatched action, with every stage that its trace rows allow. */
export function buildChains(rows, { agent = null } = {}) {
	const timestamped = rows.some((row) => Number.isFinite(row.at));
	const byAgent = new Map();
	const agentState = (id) => {
		let state = byAgent.get(id);
		if (state === undefined) {
			state = { ready: null, built: null, sent: null, turnStart: null, lastResult: null, lastRequest: null, lastCall: null, lastCompletedAt: null, actions: [], requests: [], requestActions: new Map() };
			byAgent.set(id, state);
		}
		return state;
	};
	const byAction = new Map();
	for (const row of rows) {
		const id = row.agentId;
		if (typeof id !== 'string' || (agent !== null && id !== agent)) continue;
		const state = agentState(id);
		switch (row.event) {
			case 'native_event_ready':
				if (row.mode === 'turn') { state.ready = row; state.turnStart = { ready: row, built: null, sent: null }; state.lastResult = null; }
				break;
			case 'native_input_built': state.built = row; if (state.turnStart !== null) state.turnStart.built = row; break;
			case 'native_provider_turn_sent': state.sent = row; if (state.turnStart !== null) state.turnStart.sent = row; break;
			case 'native_model_call': state.lastCall = row; break;
			case 'native_tool_queue_timing': {
				const previousResult = state.lastResult;
				const request = { row, call: state.lastCall, boundary: previousResult ?? state.sent ?? state.ready, executed: null, delivered: null, actions: 0 };
				state.lastRequest = request;
				state.requests.push(request);
				state.lastResult = null;
				state.sent = null;
				state.ready = null;
				state.built = null;
				state.lastCall = null;
				break;
			}
			// The coordinator finished the tool call; the adapter then formats the result and writes it to the provider.
			case 'native_tool_executed':
			case 'native_provider_tool_result_sent': {
				state.lastResult = row;
				const request = state.requests.findLast((entry) => entry.row.callId === row.callId);
				if (request !== undefined) request[row.event === 'native_tool_executed' ? 'executed' : 'delivered'] = row;
				break;
			}
			case 'native_tool_dispatch_started': {
				const request = state.lastRequest !== null && (row.callId == null || row.callId === state.lastRequest.row.callId) ? state.lastRequest : null;
				const action = { agentId: id, actionId: row.actionId, actionType: row.actionType, request, step: request === null ? null : request.actions++,
					dispatch: row, journal: null, sent: null, progress: null, completed: null, postAction: null, returned: null, delivered: null, next: null,
					// Startup marks wait for the first action the turn dispatches, even when earlier requests were reads.
					turnStart: state.turnStart };
				state.turnStart = null;
				state.actions.push(action);
				byAction.set(row.actionId, action);
				break;
			}
			case 'native_tool_journal_written': { const action = byAction.get(row.actionId); if (action !== undefined) action.journal = row; break; }
			case 'native_tool_command_sent': { const action = byAction.get(row.actionId); if (action !== undefined) action.sent = row; break; }
			case 'native_tool_action_progress': { const action = byAction.get(row.actionId); if (action !== undefined && action.progress === null) action.progress = row; break; }
			case 'native_tool_post_action_sampled': { const action = byAction.get(row.actionId); if (action !== undefined) action.postAction = row; break; }
			case 'native_tool_action_completed': { const action = byAction.get(row.actionId); if (action !== undefined) action.completed = row; break; }
			default: break;
		}
	}
	const chains = [];
	for (const state of byAgent.values()) {
		for (let index = 0; index < state.actions.length; index += 1) {
			const action = state.actions[index];
			const request = action.request;
			if (request !== null) { action.returned = request.executed; action.delivered = request.delivered ?? request.executed; }
			const nextRequest = request === null ? null : state.requests[state.requests.indexOf(request) + 1] ?? null;
			action.next = nextRequest;
			chains.push(stagesOf(action, nextRequest));
		}
	}
	return { chains, timestamped, agents: byAgent };
}

function stagesOf(action, nextRequest) {
	const { request, dispatch, journal, sent, completed, returned, delivered, turnStart } = action;
	const t = {};
	// Only the first action a tool request dispatches pays for the model and the hop to the tool server; only the first of a turn pays for its startup.
	const lead = request !== null && action.step === 0;
	const call = request?.call ?? null;
	if (turnStart !== null) {
		t.eventToTurn = between(turnStart.ready, turnStart.sent);
		t.inputBuild = turnStart.built?.buildMs ?? null;
	}
	if (lead) {
		t.model = between(request.boundary, request.row);
		t.prefill = call?.firstEventMs ?? null;
		t.generate = call?.streamMs ?? null;
		const hop = between(call, request.row);
		t.modelToTool = hop !== null && hop >= -5 ? Math.max(0, hop) : null;
		t.requestToDispatch = between(request.row, dispatch);
	}
	t.journal = journal?.journalMs ?? null;
	t.dispatchToSent = between(dispatch, sent);
	t.send = sent?.sendMs ?? null;
	const unstarted = completed !== null && Number.isFinite(completed.javaAcceptedAtEpochMs) && !(completed.javaStartedAtEpochMs > 0);
	if (completed !== null) {
		t.sentToAccept = diff(completed.javaAcceptedAtEpochMs, sent?.at);
		t.acceptToStart = diff(completed.javaStartedAtEpochMs, completed.javaAcceptedAtEpochMs);
		t.run = completed.javaStartedAtEpochMs > 0 ? diff(completed.javaEndedAtEpochMs, completed.javaStartedAtEpochMs) : null;
		t.endToCoordinator = diff(completed.at, completed.javaEndedAtEpochMs);
		t.sentToDone = between(sent, completed);
		t.javaElapsed = completed.javaElapsedMs ?? null;
		// Java accepted the command but it ended without ever executing (cancelled, preempted, stale): not body time.
		if (unstarted) t.unstartedWait = diff(completed.javaEndedAtEpochMs, sent?.at) ?? between(sent, completed);
	}
	t.postAction = action.postAction?.waitMs ?? null;
	if (completed !== null && delivered !== null && delivered !== undefined) {
		t.resultToModel = between(completed, delivered);
		if (returned !== null && returned !== delivered) t.resultDelivery = between(returned, delivered);
	}
	t.nextThink = delivered !== null && delivered !== undefined && nextRequest !== null ? between(delivered, nextRequest.row) : null;
	const javaStart = completed?.javaStartedAtEpochMs > 0 ? completed.javaStartedAtEpochMs : null;
	return { agentId: action.agentId, actionId: action.actionId, actionType: action.actionType, state: completed?.state ?? null, step: action.step, unstarted,
		callId: request?.row.callId ?? null, requestAt: request?.row.at ?? null, sentAt: sent?.at ?? null, startedAt: javaStart, endedAt: completed?.javaEndedAtEpochMs ?? completed?.at ?? null, stages: t, ticks: completed === null ? null : { started: completed.javaStartedTick ?? null, ended: completed.javaEndedTick ?? null } };
}

/** Share of each agent's active window in which no action was executing (the body idled waiting on the model or the coordinator). */
export function bodyIdle(chains) {
	const perAgent = new Map();
	for (const chain of chains) {
		if (chain.sentAt === null || chain.endedAt === null || chain.unstarted) continue;
		const start = chain.startedAt ?? chain.sentAt;
		if (chain.endedAt < start) continue;
		const list = perAgent.get(chain.agentId) ?? [];
		list.push([start, chain.endedAt]);
		perAgent.set(chain.agentId, list);
	}
	const result = [];
	for (const [agentId, spans] of perAgent) {
		spans.sort((left, right) => left[0] - right[0]);
		let busy = 0;
		let cursor = -Infinity;
		for (const [start, end] of spans) {
			const from = Math.max(start, cursor);
			if (end > from) busy += end - from;
			cursor = Math.max(cursor, end);
		}
		const total = cursor - spans[0][0];
		if (total > 0) result.push({ agentId, actions: spans.length, windowMs: total, busyMs: busy, idleShare: 1 - busy / total });
	}
	return result;
}

const STAGE_LABELS = [
	['eventToTurn', 'event ready -> provider turn sent'],
	['inputBuild', 'input build'],
	['model', 'model: boundary -> tool request'],
	['prefill', '  of which prefill (first stream event)'],
	['generate', '  of which generation'],
	['modelToTool', 'model end -> coordinator tool request'],
	['requestToDispatch', 'tool request -> dispatch start'],
	['journal', 'dispatch journal write'],
	['dispatchToSent', 'dispatch start -> bridge send done'],
	['send', '  of which bridge send'],
	['sentToAccept', 'bridge send -> Java accepts command'],
	['acceptToStart', 'Java accept -> action starts (tick wait)'],
	['run', 'action runs (Java start -> end)'],
	['endToCoordinator', 'Java end -> coordinator receives result'],
	['postAction', 'post-action observation wait'],
	['resultToModel', 'result received -> handed to provider'],
	['resultDelivery', '  of which format + provider send'],
	['unstartedWait', 'bridge send -> end, action never started'],
	['nextThink', 'result handed to provider -> next tool request'],
];

export function summarize(rows, options = {}) {
	const { chains, timestamped } = buildChains(rows, options);
	const stages = STAGE_LABELS.map(([key, label]) => ({ key, label, ...stats(chains.map((chain) => chain.stages[key]).filter((value) => value !== null && value !== undefined)) }));
	const recorded = recordedDurations(rows, options.agent ?? null);
	const unstartedChains = chains.filter((chain) => chain.unstarted);
	const unstartedWaits = unstartedChains.map((chain) => chain.stages.unstartedWait).filter(Number.isFinite);
	return { timestamped, rowCount: rows.length, actions: chains.length, stages, idle: bodyIdle(chains), unstarted: { count: unstartedChains.length, waitMs: unstartedWaits.reduce((sum, value) => sum + value, 0) }, recorded, chains };
}

/** Duration fields every trace has always carried; the only source for traces that predate timestamps. */
function recordedDurations(rows, agent) {
	const pick = (event, field, filter = () => true) => rows.filter((row) => row.event === event && (agent === null || row.agentId === agent) && filter(row)).map((row) => row[field]);
	const entries = [
		['decision segment: provider start -> tool request', pick('native_decision_timing', 'segmentDurationMs', (row) => row.boundary === 'tool_request')],
		['first tool request of a turn', pick('native_first_tool_requested', 'elapsedMs')],
		['model call: first stream event after request', pick('native_model_call', 'firstEventMs')],
		['model call: stream after first event', pick('native_model_call', 'streamMs')],
		['model call: total', pick('native_model_call', 'totalMs')],
		['coordinator tool queue wait', pick('native_tool_queue_timing', 'queueWaitMs')],
		['dispatch journal write', pick('native_tool_journal_written', 'journalMs')],
		['bridge send', pick('native_tool_command_sent', 'sendMs')],
		['Java action age at completion', pick('native_tool_action_completed', 'javaElapsedMs')],
		['post-action observation wait', pick('native_tool_post_action_sampled', 'waitMs')],
	];
	return entries.map(([label, values]) => ({ label, ...stats(values.filter((value) => Number.isFinite(value))) }));
}

const fmt = (value) => value === null || value === undefined ? 'n/a' : value >= 100 ? String(Math.round(value)) : String(Math.round(value * 10) / 10);

export function renderReport(summary, { rows = 15 } = {}) {
	const out = [];
	out.push(`rows: ${summary.rowCount}  dispatched actions: ${summary.actions}  timestamps: ${summary.timestamped ? 'yes' : 'NO (trace predates at/mono; chain stages need a fresh trace)'}`);
	out.push('');
	out.push('Chain stages (ms)');
	out.push(table(['stage', 'n', 'median', 'p90'], summary.stages.map((stage) => [stage.label, String(stage.n), fmt(stage.median), fmt(stage.p90)])));
	out.push('');
	out.push('Recorded durations (always present)');
	out.push(table(['duration', 'n', 'median', 'p90'], summary.recorded.map((entry) => [entry.label, String(entry.n), fmt(entry.median), fmt(entry.p90)])));
	out.push('');
	if (summary.idle.length === 0) out.push('Body idle share: n/a (needs a Java start/end or command-sent/completed pair with timestamps)');
	else {
		out.push('Body idle share (time with no action running, first start to last end)');
		out.push(table(['agent', 'actions', 'window s', 'busy s', 'idle'], summary.idle.map((entry) => [entry.agentId.slice(0, 8), String(entry.actions), fmt(entry.windowMs / 1000), fmt(entry.busyMs / 1000), `${Math.round(entry.idleShare * 100)}%`])));
	}
	if (summary.unstarted.count > 0) out.push(`${summary.unstarted.count} action(s) were accepted by Java but never started; their ${fmt(summary.unstarted.waitMs / 1000)} s of waiting is excluded from the busy and idle times above.`);
	const sample = summary.chains.slice(-rows);
	if (sample.length > 0) {
		out.push('');
		out.push(`Last ${sample.length} actions (ms)`);
		const columns = [['model', 'model'], ['modelToTool', 'm>tool'], ['requestToDispatch', 'req>disp'], ['dispatchToSent', 'disp>sent'], ['sentToAccept', 'sent>acc'], ['acceptToStart', 'acc>start'], ['run', 'run'], ['resultToModel', 'res>model'], ['nextThink', 'next']];
		out.push(table(['action', 'type', 'state', ...columns.map(([, name]) => name)], sample.map((chain) => [`#${chain.actionId.split(':')[2] ?? chain.actionId}`, chain.actionType ?? '', chain.state ?? '', ...columns.map(([key]) => fmt(chain.stages[key]))])));
	}
	return out.join('\n');
}

function table(header, body) {
	const matrix = [header, ...body];
	const widths = header.map((_, column) => Math.max(...matrix.map((line) => line[column].length)));
	return matrix.map((line) => line.map((cell, column) => column === 0 ? cell.padEnd(widths[column]) : cell.padStart(widths[column])).join('  ')).join('\n');
}

async function main(argv) {
	const files = [];
	const options = { agent: null, rows: 15, json: false };
	for (let index = 0; index < argv.length; index += 1) {
		const arg = argv[index];
		if (arg === '--agent') options.agent = argv[++index];
		else if (arg === '--rows') options.rows = Number(argv[++index]);
		else if (arg === '--json') options.json = true;
		else files.push(arg);
	}
	if (files.length === 0) {
		console.error('usage: node scripts/trace-latency.mjs <coordinator.jsonl>... [--agent <id>] [--rows <n>] [--json]');
		return 2;
	}
	const rows = [];
	for (const file of files) rows.push(...parseTrace(await readFile(file, 'utf8')));
	const summary = summarize(rows, { agent: options.agent });
	if (options.json) {
		const { chains, ...rest } = summary;
		console.log(JSON.stringify({ ...rest, chains }, null, 2));
	} else console.log(renderReport(summary, { rows: options.rows }));
	return 0;
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) process.exitCode = await main(process.argv.slice(2));
