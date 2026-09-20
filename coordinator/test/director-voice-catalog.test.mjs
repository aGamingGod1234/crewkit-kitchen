import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { directorVoiceProfiles, VoiceProfileStore } from '../src/voice/voice-profile-store.mjs';

const ACTOR = '00000000-0000-4000-8000-000000000001';
test('Director offers named Fish voices with distinct reference voices and descriptive labels', async () => {
  const choices = directorVoiceProfiles();
  assert.equal(choices.length, 6);
  assert.equal(new Set(choices.map(choice => choice.voiceId)).size, choices.length);
  const java = await readFile(new URL('../../src/main/java/dev/agaminggod/arenaagents/server/voice/VoiceCatalog.java', import.meta.url), 'utf8');
  const store = new VoiceProfileStore();
  for (const choice of choices) {
    assert.match(choice.label, /^[A-Z][a-z]+ - .+, (male|female)$/);
    assert.equal(choice.model, 's2.1-pro-free');
    assert.equal(store.resolveRequested(ACTOR, choice.profileId), choice);
    assert.ok(java.includes(choice.profileId), choice.profileId);
    assert.ok(java.includes(choice.label), choice.label);
  }
  assert.deepEqual(store.snapshotAssignments(), {});
  assert.equal(store.resolveRequested(ACTOR, 'voice.moss.v1').voiceId, 'c5f56a6cc2ec4fa8920cb4c5889a3fb7');
});


test('every new Director choice crosses HTTP validation and reaches synthesis', async () => {
  const { createVoiceHttpServer, createVoiceRequestHeaders } = await import('../src/voice/voice-http-server.mjs');
  const references = [];
  const secret = 'director-catalog-fixture';
  const worker = createVoiceHttpServer({ provider: null, fishProvider: { async synthesize(request) {
    references.push(request.voiceId);
    return { sampleRateHz: 48000, channels: 1, sampleFormat: 's16le', pcm: Buffer.alloc(960) };
  } }, profileStore: new VoiceProfileStore(), secret, port: 0 });
  const address = await worker.start();
  try {
    for (const choice of directorVoiceProfiles()) {
      const body = JSON.stringify({ agentId: ACTOR, text: 'Hello.', profileId: choice.profileId,
        radius: 48, conversationSequence: 1, speed: 1, tone: 'neutral' });
      const headers = createVoiceRequestHeaders({ secret, path: '/v1/tts', contentType: 'application/json', body: Buffer.from(body) });
      const response = await fetch(`http://127.0.0.1:${address.port}/v1/tts`, {
        method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' }, body });
      assert.equal(response.status, 200, choice.profileId);
      await response.arrayBuffer();
    }
    assert.deepEqual(references, directorVoiceProfiles().map(choice => choice.voiceId));
  } finally { await worker.close(); }
});

for (const mode of ['configured', 'missing', 'rejected']) {
 test(`Director Fish selection never substitutes Windows speech: ${mode}`, async () => {
  const { createVoiceHttpServer, createVoiceRequestHeaders } = await import('../src/voice/voice-http-server.mjs');
  let windowsCalls = 0, fishCalls = 0;
  const pcm = { sampleRateHz: 48000, channels: 1, sampleFormat: 's16le', pcm: Buffer.alloc(960) };
  const secret = 'director-fish-routing-fixture';
  const worker = createVoiceHttpServer({
   provider: { cacheNamespace: () => 'windows/system-speech', async synthesize() { windowsCalls++; return pcm; } },
   fishProvider: mode === 'missing' ? null : { cacheNamespace: () => 'fish/s2.1-pro-free/delivery-v1', async synthesize() { fishCalls++; if (mode === 'rejected') throw Object.assign(new Error('Fish key rejected'), { code: 'TTS_AUTH_REQUIRED' }); return pcm; } },
   profileStore: new VoiceProfileStore(), secret, port: 0,
  });
  const address = await worker.start();
  try {
   const body = JSON.stringify({ agentId: ACTOR, text: 'Hello.', profileId: 'voice.laura.v1', radius: 48, conversationSequence: 1, speed: 1, tone: 'neutral' });
   const response = await fetch(`http://127.0.0.1:${address.port}/v1/tts`, { method: 'POST', headers: { ...createVoiceRequestHeaders({ secret, path: '/v1/tts', contentType: 'application/json', body: Buffer.from(body) }), 'Content-Type': 'application/json' }, body });
   assert.equal(response.status, mode === 'configured' ? 200 : mode === 'missing' ? 503 : 502);
   assert.equal(windowsCalls, 0, 'Fish selections must never speak using the Windows robot');
   if (mode === 'configured') { assert.match(response.headers.get('x-voice-synthesizer'), /^fish\//); await response.arrayBuffer(); }
   else assert.match((await response.json()).message, /Fish/);
   assert.equal(fishCalls, mode === 'missing' ? 0 : 1);
  } finally { await worker.close(); }
 });
}
