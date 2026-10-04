import assert from 'node:assert/strict';
import test from 'node:test';
import { EventEmitter } from 'node:events';
import { readFile } from 'node:fs/promises';
import { HeadlessRconClient } from '../src/headless-rcon.mjs';
import { summarizeProviderAttestation } from '../src/headless-world.mjs';
import { normalizeHeadlessMatrix, normalizeHeadlessScenario, runHeadlessScenario } from '../src/headless-matrix.mjs';

const profile = { provider: 'codex', model: 'fixture', reasoningEffort: 'low', serviceTier: 'fast' };
const packet = (id, type, text = '') => {
  const body = Buffer.from(text);
  const data = Buffer.alloc(body.length + 14);
  data.writeInt32LE(body.length + 10); data.writeInt32LE(id, 4); data.writeInt32LE(type, 8); body.copy(data, 12);
  return data;
};

// The server processes requests in order. Type 0 is an inert unsupported-request
// reply on Minecraft, emitted only after every preceding command-response packet.
class Socket extends EventEmitter {
  readyState = 'open'; destroyed = false; markerRequests = 0;
  constructor(parts, { fragmented = false, omitMarker = false } = {}) { super(); Object.assign(this, { parts, fragmented, omitMarker }); }
  write(data) {
    const id = data.readInt32LE(4), type = data.readInt32LE(8);
    if (type === 3) queueMicrotask(() => this.emit('data', packet(id, 2)));
    else if (type === 0) {
      this.markerRequests++;
      if (!this.omitMarker) setImmediate(() => this.emit('data', packet(id, 0, 'Unknown request 0')));
    } else setImmediate(() => {
      for (const part of this.parts) {
        const reply = packet(id, 0, part);
        if (this.fragmented) { this.emit('data', reply.subarray(0, 3)); this.emit('data', reply.subarray(3)); }
        else this.emit('data', reply);
      }
    });
  }
  destroy() { this.destroyed = true; queueMicrotask(() => this.emit('close')); }
}

for (const fragmented of [false, true]) test(`RCON retains multipart trailing witness (fragmented=${fragmented})`, async () => {
  const parts = ['a'.repeat(4096), 'minecraft:oak_log'];
  const socket = new Socket(parts, { fragmented });
  const client = new HeadlessRconClient({ port: 25575, socketFactory: () => socket });
  try {
    await client.connect();
    const result = await client.command('data get entity fixture Inventory');
    assert.equal(result.text, parts.join(''));
    assert.equal(result.complete, true); assert.equal(result.truncated, false);
    assert.equal(socket.markerRequests, 1);
  } finally { await client.close(); }
  assert.equal(client.pending.size, 0); assert.equal(socket.destroyed, true);
});

test('RCON bounds the aggregate and exposes truncation, including UTF-8 boundaries', async () => {
  const socket = new Socket(['abc', '😀def']);
  const client = new HeadlessRconClient({ port: 25575, maxResponseBytes: 5, socketFactory: () => socket });
  try {
    await client.connect(); const result = await client.command('list');
    assert.equal(result.text, 'abc'); assert.equal(result.truncated, true); assert.equal(result.complete, false);
    assert.ok(Buffer.byteLength(result.text) <= 5);
  } finally { await client.close(); }
});

test('RCON missing completion marker times out rather than returning partial evidence', async () => {
  const socket = new Socket(['partial'], { omitMarker: true });
  const client = new HeadlessRconClient({ port: 25575, commandTimeoutMs: 30, socketFactory: () => socket });
  try { await client.connect(); await assert.rejects(client.command('list'), { code: 'RCON_TIMEOUT' }); }
  finally { await client.close(); }
});

test('RCON serializes concurrent requests and closes queued work without issuing it', async () => {
  const socket = new Socket(['ok']);
  const client = new HeadlessRconClient({ port: 25575, socketFactory: () => socket });
  await client.connect();
  assert.deepEqual((await Promise.all([client.command('one'), client.command('two')])).map(row => row.text), ['ok', 'ok']);
  const queued = client.command('never sent');
  await client.close();
  await assert.rejects(queued, { code: 'RCON_CLOSED' });
  assert.equal(socket.markerRequests, 2);
});

test('scenario factual inventory evaluation receives the trailing RCON witness and rejects incomplete evidence', async () => {
  for (const limit of [65536, 4100]) {
    let audit = '';
    const config = JSON.parse(await readFile(new URL('../config/speed-headless-matrix.json', import.meta.url)));
    const scenario = normalizeHeadlessScenario(config.scenarios[0]);
    const body = 'a'.repeat(4096) + 'minecraft:oak_log';
    class ScenarioSocket extends Socket {
      write(data) {
        if (data.readInt32LE(8) === 2) {
          const command = data.subarray(12, -2).toString();
          let text = 'ok';
          if (command.includes('summon-configured')) {
            const name = command.split(' ').at(-1);
            audit = JSON.stringify(envelope('agent_registered', { agentId: 'owned-agent', name,
              provider: scenario.provider, model: scenario.model, reasoningEffort: scenario.reasoningEffort, serviceTier: scenario.serviceTier }));
            text = `Created ${name}. It is ready for a task.`;
          } else if (command.startsWith('codex status ')) text = 'state=COMPLETED';
          else if (command.endsWith(' Pos')) text = 'Fixture has the following entity data: [0.5d, 201.0d, 0.5d]';
          else if (command.endsWith(' Inventory')) text = body;
          this.parts = [text.slice(0, 4096), ...(text.length > 4096 ? [text.slice(4096)] : [])];
        }
        super.write(data);
      }
    }
    const socket = new ScenarioSocket([]);
    const client = new HeadlessRconClient({ port: 25575, maxResponseBytes: limit, socketFactory: () => socket });
    await client.connect();
    const report = await runHeadlessScenario({ scenario, runDirectory: 'virtual-g09', rcon: client,
      fileSize: async () => 0, readFile: async file => file.endsWith('protocol.jsonl') ? audit : '', writeFile: async () => {},
      poll: async () => { throw Error('unexpected poll'); },
    });
    assert.equal(report.classification, limit === 65536 ? 'PASSED' : 'ERROR');
    if (limit !== 65536) assert.match(report.diagnostics, /incomplete/);
    assert.equal(report.cleanup.status, 'CLEAN'); assert.equal(client.pending.size, 0); assert.equal(socket.destroyed, true);
  }
});

test('provider attestation accepts only the one-way Codex tier alias and keeps raw evidence', () => {
  const summarize = (requested, effective, evidence = 'provider_reported') => summarizeProviderAttestation([
    { executionSettings: { effective: { ...requested, ...effective }, evidence: { model: evidence, reasoningEffort: evidence, serviceTier: evidence } } },
  ], requested);
  const accepted = summarize(profile, { serviceTier: 'priority' });
  assert.deepEqual(accepted.mismatches, []); assert.equal(accepted.effective.serviceTier, 'priority');
  for (const requested of [{ ...profile, provider: 'cursor' }, { ...profile, serviceTier: 'priority' }]) {
    assert.deepEqual(summarize(requested, { serviceTier: requested.provider === 'cursor' ? 'priority' : 'fast' }).mismatches, ['serviceTier']);
  }
  for (const field of ['model', 'reasoningEffort', 'serviceTier']) assert.deepEqual(summarize(profile, { [field]: 'wrong' }).mismatches, [field]);
  assert.deepEqual(summarize(profile, { serviceTier: 'wrong' }, 'submitted').mismatches, []);
});

const envelope = (type, payload, agentId = 'owned-agent') => ({ envelope: { type, agentId, payload } });
async function recoveryCase({ count = 514, state, resultIndex = count - 1, padding = 0, wrongAgent = false, expire = false } = {}) {
  let audit = '', reads = 0, clock = 100;
  const scenario = normalizeHeadlessScenario({ id: 'join-fixture', ...profile, task: 'Move', timeoutMs: 10000,
    assert: [{ type: 'action', actionType: 'move', args: { x: 1 }, resultState: 'SUCCEEDED' }] });
  const report = await runHeadlessScenario({ scenario, runDirectory: 'virtual-g09', now: () => clock,
    fileSize: async () => 0, writeFile: async () => {}, poll: async () => { throw Error('unexpected poll'); },
    readFile: async file => { if (!file.endsWith('protocol.jsonl')) return ''; reads++; if (expire) clock = 20100; return audit; },
    rcon: { close: async () => {}, command: async command => {
      if (command.includes('summon-configured')) {
        const rows = [envelope('agent_registered', { agentId: 'owned-agent', name: command.split(' ').at(-1), ...profile })];
        for (let i = 0; i < count; i++) rows.push(envelope('action_command', { actionId: `action-${i}`, actionType: 'move', arguments: { x: 1 } }));
        for (let i = 0; i < padding; i++) rows.push(envelope('observation', { tick: i }));
        if (state) rows.push(envelope('action_result', { actionId: `action-${resultIndex}`, state }, wrongAgent ? 'unrelated' : 'owned-agent'));
        audit = rows.map(JSON.stringify).join('\n'); return { text: `Created ${command.split(' ').at(-1)}. It is ready for a task.` };
      }
      return { text: command.startsWith('codex status ') ? 'state=DEAD' : 'ok' };
    } },
  });
  assert.equal(report.classification, 'DEAD'); assert.equal(report.cleanup.status, 'CLEAN');
  return { report, reads };
}

test('action recovery uses the recent join and avoids per-command scans without a successful result', async () => {
  for (const state of [undefined, 'FAILED', 'SUCCEEDED']) {
    const { report, reads } = await recoveryCase({ state });
    assert.equal(report.assertions[0].passed, state === 'SUCCEEDED'); assert.equal(reads, 1);
  }
  const { report, reads } = await recoveryCase({ state: 'SUCCEEDED', wrongAgent: true });
  assert.equal(report.assertions[0].passed, false); assert.equal(reads, 1);
});

test('action recovery batches old candidate IDs without dropping a late witness', async () => {
  for (const resultIndex of [0, 513]) {
    const { report, reads } = await recoveryCase({ state: 'SUCCEEDED', resultIndex, padding: 4200 });
    assert.equal(report.assertions[0].passed, true); assert.ok(reads <= 5, `read count ${reads}`);
    assert.equal(report.evidence.coverage.protocol.complete, true);
  }
});

test('evidence expiry reports incomplete coverage and stops recovery', async () => {
  const { report, reads } = await recoveryCase({ state: 'SUCCEEDED', padding: 4200, expire: true });
  assert.equal(reads, 1); assert.equal(report.evidence.coverage.protocol.complete, false);
  assert.ok(report.evidence.coverage.protocol.reasons.includes('evidence_deadline_exceeded'));
  assert.equal(report.assertions[0].passed, false);
});

test('latency roster fixtures supply accessible wood and a workstation before any summon', async () => {
  const matrix = normalizeHeadlessMatrix(JSON.parse(await readFile(new URL('../config/latency-headless-matrix.json', import.meta.url))));
  for (const scenario of matrix.scenarios) {
    const logs = scenario.setupBlocks.filter(block => block.blockId === 'minecraft:oak_log');
    assert.ok(logs.length * 4 >= scenario.rosterSize * 5, 'three planks and a two-plank stick recipe per pickaxe');
    assert.ok(scenario.setupBlocks.some(block => block.blockId === 'minecraft:crafting_table'));
    assert.ok(scenario.setupBlocks.every(block => Math.abs(block.x) <= 8 && Math.abs(block.z) <= 8 && [201, 202].includes(block.y)));
    for (const reject of [false, true]) {
      const commands = [];
      const report = await runHeadlessScenario({ scenario, runDirectory: 'virtual-g09', now: () => 100,
        fileSize: async () => 0, readFile: async () => '', writeFile: async () => {},
        rcon: { close: async () => {}, command: async command => {
          commands.push(command);
          return { text: reject && command.includes(' run setblock ') ? 'ERROR placement rejected' : command.includes('summon-configured') ? 'profile unavailable' : 'ok' };
        } },
      });
      const firstSummon = commands.findIndex(command => command.includes('summon-configured'));
      if (reject) { assert.equal(firstSummon, -1); assert.equal(report.status, 'FAILED'); }
      else {
        assert.ok(firstSummon > 0);
        assert.equal(commands.slice(0, firstSummon).filter(command => command.includes(' run setblock ')).length, scenario.setupBlocks.length);
      }
      assert.ok(commands.includes('execute in minecraft:overworld run forceload remove 0 0'));
    }
  }
});
