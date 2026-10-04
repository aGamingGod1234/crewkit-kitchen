import assert from 'node:assert/strict';
import { EventEmitter, once } from 'node:events';
import test from 'node:test';
import { LiveTaskViews } from '../src/live-task-view.mjs';
import { goalSpecFingerprint, parseGoalSpec } from '../src/goal-spec.mjs';
import { createBridgeAuthenticationProof, MultiplexedServerBridge, validateProtocolV2Payload } from '../src/protocol-v2.mjs';

// Regression adapted from the verified f070 in-memory send probe.
// In-memory socket handshake pattern adapted from protocol-v2.test.mjs.
// The producer, goal-spec parser, payload validators and bridge.send are real code.
// No OS socket, provider, game or directory-backed LiveTaskViews is started.
test('long task goals remain deliverable without changing task identity or tool results', async () => {
  const secret = 'f070-fixture-only-'.repeat(3);
  const serverId = 'f070-server';
  const agentId = 'f070-agent';
  const envelope = (type, messageId, payload) => ({ protocolVersion: 2, serverInstanceId: serverId, agentId: 'server', type, messageId, payload });
  class MemorySocket extends EventEmitter {
    writes = [];
    destroyed = false;
    write(encoded) {
      const message = JSON.parse(String(encoded));
      this.writes.push(message);
      if (message.type === 'auth_challenge') {
        const serverNonce = Buffer.alloc(32, 7).toString('base64url');
        const clientNonce = message.payload.clientNonce;
        this.emit('data', JSON.stringify(envelope('auth_response', 'f070-auth', {
          replyTo: message.messageId, clientNonce, serverNonce,
          proof: createBridgeAuthenticationProof(secret, 'server', { clientNonce, serverNonce, serverInstanceId: serverId }),
        })) + '\n');
      }
      return true;
    }
    setNoDelay() {}
    pause() {}
    resume() {}
    destroy() { this.destroyed = true; this.emit('close'); }
  }
  const socket = new MemorySocket();
  const bridge = new MultiplexedServerBridge({ port: 25570, secret }, {
    socketFactory: () => socket,
    schedule: () => 1, cancelSchedule: () => {},
    scheduleDeadline: () => 2, cancelDeadline: () => {},
  });
  const fixtureErrors = [];
  bridge.on('protocolError', error => fixtureErrors.push(error.message));
  bridge.on('transportError', error => fixtureErrors.push(error.message));
  const cases = [
    ['ascii-511', 'g'.repeat(511)], ['ascii-512', 'g'.repeat(512)],
    ['surrogate-boundary', 'g'.repeat(510) + '\u{1f331}xx'], ['ascii-513', 'g'.repeat(513)], ['ascii-4096', 'g'.repeat(4096)],
    ['bmp-512', '\u754c'.repeat(512)], ['bmp-513', '\u754c'.repeat(513)],
    ['emoji-512-units', '\u{1f331}'.repeat(256)], ['emoji-514-units', '\u{1f331}'.repeat(257)],
    ['short-current-long-original', 'g'.repeat(513), 'Continue mining'],
    ['long-current-short-original', 'Find iron', 'g'.repeat(513)],
  ];
  const results = [];
  try {
    bridge.start();
    socket.emit('connect');
    const hello = socket.writes.find(message => message.type === 'hello');
    assert.ok(hello, 'In-memory authenticated handshake reached hello');
    const ready = once(bridge, 'ready');
    socket.emit('data', JSON.stringify(envelope('hello_ack', 'f070-ready', {
      replyTo: hello.messageId, authenticated: true,
      registry: [{ schemaVersion: 1, agentId, model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast', gameMode: 'survival', skinVariant: 'teal', state: 'IDLE', goalRevision: 0, queue: [], createdAtEpochMs: 1, updatedAtEpochMs: 1 }],
    })) + '\n');
    await ready;
    assert.equal(bridge.ready, true);
    assert.deepEqual(bridge.knownAgentIds, [agentId]);
    for (const [name, original, current = original] of cases) {
      const fields = { originalRequest: original, predicate: { type: 'operator_confirmed' }, createdAtTick: 1 };
      const spec = parseGoalSpec({ ...fields, fingerprint: goalSpecFingerprint(fields) });
      const control = validateProtocolV2Payload('goal_control', { operation: 'start', goalRevision: 1, updatedAtEpochMs: 1000, goal: current, goalSpec: spec });
      assert.equal(control.goal, current);
      const record = { agentId, currentGoal: control.goal, currentGoalSpec: control.goalSpec, goalRevision: 1, provider: 'codex', model: 'gpt-5.6-sol', reasoningEffort: 'high', serviceTier: 'fast' };
      const views = new LiveTaskViews({ now: () => 1000 });
      views.begin(record, { fresh: true });
      await views.observe(record, { ready: true, world: { worldId: 'f070-world' }, player: {}, inventory: { items: [] } });
      await views.operate(record, { operation: 'replace', plan: { steps: [{ id: 'iron', label: 'Find iron', kind: 'milestone', status: 'active', dependsOn: [], detail: 'Reach the cave', evidence: null }] } });
      views.event(record, 'live_tool', 'minecraft.observe');
      views.event(record, 'live_usage', JSON.stringify({ inputTokens: 100, cachedInputTokens: 20, outputTokens: 10, totalTokens: 110, reportedAtEpochMs: 1000 }));
      const snapshot = views.snapshot(record);
      assert.ok(snapshot.goal.length <= 512);
      if (original.length <= 512) assert.equal(snapshot.goal, original, name);
      else {
        assert.ok(snapshot.goal.endsWith('\u2026'), 'Truncated display must be explicit');
        assert.ok(original.startsWith(snapshot.goal.slice(0, -1)), 'Display keeps the original prefix');
        assert.ok(snapshot.goal.isWellFormed(), 'Display cannot split a surrogate pair');
      }
      assert.equal((await views.operate(record, { operation: 'read' })).goal, original);
      assert.equal(snapshot.plan.steps.length, 1);
      assert.equal(snapshot.events.length, 1);
      assert.equal(snapshot.usage.totalTokens, 110);
      const snapshotBytes = Buffer.byteLength(JSON.stringify(snapshot));
      assert.ok(snapshotBytes < 28672);
      const before = socket.writes.length;
      const failures = [];
      for (let attempt = 0; attempt < 2; attempt++) {
        try { await bridge.send('task_view', agentId, views.snapshot(record)); failures.push(null); }
        catch (error) { failures.push({ code: error.code, message: error.message }); }
      }
      assert.deepEqual(failures, [null, null]);
      assert.equal(socket.writes.length, before + 2);
      assert.equal(socket.writes.at(-1).payload.goal, snapshot.goal);
      assert.equal(socket.writes.at(-1).payload.plan.steps.length, 1);
      assert.equal((await views.operate(record, { operation: 'read' })).goal, original, 'Display projection cannot alter task state');
      assert.equal(record.currentGoalSpec.fingerprint, spec.fingerprint);
      assert.equal(record.currentGoalSpec.originalRequest, original);
      await views.flush();
      results.push({ name, originalUtf16Units: original.length, currentUtf16Units: current.length,
        snapshotBytes, goalControlAccepted: true, populatedPlanEventsUsage: true,
        bridgeSendAccepted: true, failures,
        fullOriginalAndFingerprintPreserved: true });
    }
    assert.throws(() => validateProtocolV2Payload('goal_control', { operation: 'start', goalRevision: 1, updatedAtEpochMs: 1000, goal: 'g'.repeat(266881) }), /266880/);
    assert.throws(() => validateProtocolV2Payload('goal_control', { operation: 'queue', goalRevision: 1, updatedAtEpochMs: 1000, goal: 'g'.repeat(4097) }), /4096/);
    assert.deepEqual(fixtureErrors, []);
  } finally {
    bridge.stop();
  }
  assert.equal(socket.destroyed, true);
  assert.equal(results.length, cases.length);
});
