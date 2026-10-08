/**
 * One deterministic clock for everything a replay trial measures in time: world-tick pacing and
 * provider delays. With wall-clock timers the number of world ticks that fit inside a provider
 * delay depends on machine load, so a loaded runner records or replays a different prompt.
 * Here time only moves when the tick pacer advances it, and every wake-up is followed by event
 * loop rounds until the observed bridges stop producing traffic.
 */
export function createVirtualClock({ quietRounds = 3, maxRounds = 1_000 } = {}) {
	let current = 0;
	let nextId = 0;
	let registrations = 0;
	const sleepers = new Map();
	const bridges = new Set();

	const timer = {
		setTimeout(callback, milliseconds) {
			const id = ++nextId;
			registrations += 1;
			sleepers.set(id, { at: current + milliseconds, callback });
			return id;
		},
		clearTimeout(id) { sleepers.delete(id); },
	};

	const traffic = () => {
		let total = registrations + sleepers.size;
		for (const bridge of bridges) total += bridge.events.length + bridge.sent.length;
		return total;
	};

	async function idle() {
		let quiet = 0;
		let last = traffic();
		for (let round = 0; quiet < quietRounds; round += 1) {
			if (round >= maxRounds) throw new Error('virtual clock did not become idle');
			await new Promise((resolve) => setImmediate(resolve));
			const now = traffic();
			quiet = now === last ? quiet + 1 : 0;
			last = now;
		}
	}

	async function advance(milliseconds) {
		await idle();
		const target = current + milliseconds;
		for (;;) {
			let due = null;
			for (const [id, sleeper] of sleepers) {
				if (sleeper.at <= target && (due === null || sleeper.at < due.at || (sleeper.at === due.at && id < due.id))) due = { id, ...sleeper };
			}
			if (due === null) break;
			sleepers.delete(due.id);
			current = Math.max(current, due.at);
			due.callback();
			await idle();
		}
		current = target;
	}

	function sleep(milliseconds, signal) {
		if (milliseconds === 0) return Promise.resolve();
		const cancelled = () => signal.reason ?? Object.assign(new Error('replay decision was cancelled'), { code: 'PLAN_CANCELLED' });
		if (signal?.aborted) return Promise.reject(cancelled());
		return new Promise((resolve, reject) => {
			const abort = () => { timer.clearTimeout(id); reject(cancelled()); };
			const id = timer.setTimeout(() => { signal?.removeEventListener('abort', abort); resolve(); }, milliseconds);
			signal?.addEventListener('abort', abort, { once: true });
		});
	}

	return {
		timer,
		sleep,
		advance,
		now: () => current,
		/** Bridge traffic is how the clock tells that the coordinator has finished reacting. */
		observe(bridge) { bridges.add(bridge); },
		/** Options for runLatencyMatrix and generateReplayRecordings that put a trial on this clock. */
		trialOptions: () => ({ virtualTickPacing: { now: () => current, sleep: advance } }),
	};
}
