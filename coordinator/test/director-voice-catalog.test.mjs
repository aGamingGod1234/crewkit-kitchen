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
  const worker = createVoiceHttpServer({ provider: { async synthesize(request) {
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
