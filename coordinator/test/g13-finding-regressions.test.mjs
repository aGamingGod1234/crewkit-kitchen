import test from 'node:test';
import assert from 'node:assert/strict';
import { AgentRegistry } from '../src/agent-registry.mjs';
import { createProtocolV2Envelope, validateProtocolV2Envelope } from '../src/protocol-v2.mjs';
import { buildNativeEventInput } from '../src/dynamic-main.mjs';

const agentId = '00000000-0000-4000-8000-000000000013';
const death = {
  cause: 'fall', dimensionId: 'minecraft:the_end', x: 240.5, y: 42, z: -170.5,
  respawnDimensionId: 'minecraft:the_nether', respawnX: 830.5, respawnY: 72, respawnZ: -400.5,
  respawnYaw: 37.5, respawnPitch: -12.25, respawnForced: true, gameMode: 'spectator', diedAtEpochMs: 1002,
};
const record = {
  schemaVersion: 1, agentId, provider: 'codex', model: 'gpt-6-astra', reasoningEffort: 'high',
  serviceTier: 'priority', gameMode: 'survival', skinVariant: 'variant-0', state: 'DEAD',
  currentGoal: 'Find diamonds', goalRevision: 1, queue: [], death, createdAtEpochMs: 1000, updatedAtEpochMs: 1002,
};
function wire(type, payload) {
  return validateProtocolV2Envelope(createProtocolV2Envelope({
    serverInstanceId: 'g13-offline', agentId, type, messageId: 'g13-message', payload,
  }), { direction: type === 'goal_spec_proposal' ? 'coordinator_to_server' : 'server_to_coordinator' }).payload;
}

test('revised DEAD registration preserves facts and admits the next respawn at the new revision', () => {
  for (const resumeGoal of [false, true]) {
    const registry = new AgentRegistry();
    registry.register(wire('agent_registered', record));
    const currentGoal = resumeGoal ? 'Find diamonds\n\nSteering instruction: Use safe tunnel' : record.currentGoal;
    const revised = registry.register(wire('agent_registered', { ...record, currentGoal, goalRevision: 2, updatedAtEpochMs: 1003 }));
    assert.equal(revised.state, 'DEAD');
    assert.deepEqual(revised.death, death);
    assert.equal(revised.currentGoal, currentGoal);
    assert.equal(registry.size, 1);
    assert.throws(() => registry.applyGoalControl(agentId, { operation: 'respawn', goalRevision: 1, updatedAtEpochMs: 1004, resumeGoal }), { code: 'STALE_GOAL_REVISION' });
    const respawned = registry.applyGoalControl(agentId, wire('goal_control', { operation: 'respawn', goalRevision: 2, updatedAtEpochMs: 1004, resumeGoal }));
    assert.equal(respawned.state, resumeGoal ? 'STARTING' : 'PAUSED');
    assert.equal(respawned.death, null);
  }
});

test('goal summary wire contract accepts the receiver boundary in UTF-16 code units', () => {
  const proposal = { requestId: '00000000-0000-4000-8000-000000000089', predicate: { type: 'operator_confirmed' } };
  for (const summary of ['x'.repeat(256), 'x'.repeat(257), 'x'.repeat(300), 'x'.repeat(512), '😀'.repeat(256)]) {
    assert.equal(wire('goal_spec_proposal', { ...proposal, summary }).summary, summary);
  }
  for (const summary of ['x'.repeat(513), '😀'.repeat(257), '   ', 42]) {
    assert.throws(() => wire('goal_spec_proposal', { ...proposal, summary }));
  }
});

test('all schema-bounded persisted steering survives fresh registration and native input', () => {
  const original = 'P'.repeat(4096);
  const instructions = Array.from({ length: 64 }, (_, i) => (`constraint-${i} `).repeat(500).slice(0, 4096));
  const currentGoal = original + '\n\nSteering instructions (oldest first; later instructions supersede conflicting earlier instructions):'
    + instructions.map((instruction, i) => `\n${i + 1}. ${instruction}`).join('');
  const registry = new AgentRegistry();
  const restored = registry.register(wire('agent_registered', { ...record, currentGoal }));
  assert.equal(restored.currentGoal, currentGoal);
  const eventInput = buildNativeEventInput(restored, { conversation: {
    mode: 'unread', baseSequence: 0, nextSequence: 1,
    entries: [{ sequence: 1, kind: 'player_message', text: 'Keep the village intact' }],
  } });
  const input = JSON.parse(eventInput.slice(eventInput.indexOf('\n') + 1));
  assert.equal(input.goal, currentGoal);
  assert.equal(input.conversation.entries[0].text, 'Keep the village intact');
  const controlled = registry.applyGoalControl(agentId, wire('goal_control', {
    operation: 'steer', goalRevision: 2, updatedAtEpochMs: 1003, goal: currentGoal,
  }));
  assert.equal(controlled.currentGoal, currentGoal);
  assert.throws(() => wire('goal_control', { operation: 'queue', goalRevision: 2, updatedAtEpochMs: 1004, goal: 'x'.repeat(4097) }));
  assert.throws(() => wire('agent_registered', { ...record, currentGoal: 'x'.repeat(266_881) }));
});
