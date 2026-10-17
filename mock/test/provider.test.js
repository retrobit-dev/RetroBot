#!/usr/bin/env node
/**
 * Tests for the mock OpenAI-compatible provider.
 *
 * Run: node test/provider.test.js
 */

'use strict';

const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const path = require('node:path');

const PORT = Number(process.env.TEST_PORT || 8795);
const BASE = `http://127.0.0.1:${PORT}`;

let server = null;
let passed = 0;
let failed = 0;

async function test(name, fn) {
  try {
    await fn();
    passed++;
    console.log(`  ✓ ${name}`);
  } catch (err) {
    failed++;
    console.log(`  ✗ ${name}`);
    console.log(`      ${err.message}`);
  }
}

async function readSse(res) {
  const text = await res.text();
  const events = [];
  for (const block of text.split('\n\n')) {
    const line = block.split('\n').find((l) => l.startsWith('data: '));
    if (!line) continue;
    const data = line.slice(6);
    events.push(data === '[DONE]' ? { done: true } : JSON.parse(data));
  }
  return events;
}

function chat(body) {
  return fetch(`${BASE}/v1/chat/completions`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  });
}

(async () => {
  console.log('\n  Mock provider — tests\n');

  server = spawn(process.execPath, [path.join(__dirname, '..', 'server.js')], {
    env: { ...process.env, PORT: String(PORT), HOST: '127.0.0.1' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  await new Promise((resolve, reject) => {
    let buf = '';
    server.stdout.on('data', (d) => {
      buf += d.toString();
      if (buf.includes('mock provider')) resolve();
    });
    server.stderr.on('data', (d) => {
      buf += d.toString();
    });
    setTimeout(() => reject(new Error('server did not start')), 8000);
  });

  await test('GET /health responds', async () => {
    const res = await fetch(`${BASE}/health`);
    const body = await res.json();
    assert.equal(body.ok, true);
    assert.equal(body.models, 4);
  });

  await test('GET /v1/models lists the mock models with pricing', async () => {
    const res = await fetch(`${BASE}/v1/models`);
    const body = await res.json();
    assert.equal(body.object, 'list');
    assert.equal(body.data.length, 4);
    const concise = body.data.find((m) => m.id === 'mock-concise');
    assert.ok(concise, 'mock-concise should be listed');
    assert.equal(concise.context_length, 128000);
    assert.equal(concise.pricing.prompt, '0.0000005');
  });

  await test('a non-streaming answer restates the prompt', async () => {
    const res = await chat({
      model: 'mock-concise',
      messages: [{ role: 'user', content: 'apa itu arena?' }],
    });
    const body = await res.json();
    assert.equal(body.object, 'chat.completion');
    assert.equal(body.model, 'mock-concise');
    assert.equal(body.choices[0].finish_reason, 'stop');
    assert.match(body.choices[0].message.content, /apa itu arena\?/);
    assert.ok(body.usage.total_tokens > 0);
  });

  await test('two different models give visibly different answers', async () => {
    const prompt = [{ role: 'user', content: 'jelaskan elo' }];
    const concise = await (await chat({ model: 'mock-concise', messages: prompt })).json();
    const detailed = await (await chat({ model: 'mock-detailed', messages: prompt })).json();

    assert.notEqual(
      concise.choices[0].message.content,
      detailed.choices[0].message.content,
      'the two models must not answer identically',
    );
    assert.ok(detailed.choices[0].message.content.length > concise.choices[0].message.content.length);
  });

  await test('the same prompt reaches both sides of a battle', async () => {
    const prompt = [{ role: 'user', content: 'apa itu arena?' }];
    const a = await (await chat({ model: 'mock-concise', messages: prompt })).json();
    const b = await (await chat({ model: 'mock-hedged', messages: prompt })).json();

    assert.match(a.choices[0].message.content, /apa itu arena\?/);
    assert.match(b.choices[0].message.content, /apa itu arena\?/);
  });

  await test('streaming produces incremental deltas and ends with [DONE]', async () => {
    const res = await chat({
      model: 'mock-detailed',
      stream: true,
      messages: [{ role: 'user', content: 'halo' }],
    });
    assert.match(res.headers.get('content-type'), /text\/event-stream/);

    const events = await readSse(res);
    assert.equal(events.at(-1).done, true);

    const content = events
      .map((e) => e.choices?.[0]?.delta?.content || '')
      .join('');
    assert.match(content, /Background/);

    const deltas = events.filter((e) => e.choices?.[0]?.delta?.content);
    assert.ok(deltas.length > 3, `expected several deltas, got ${deltas.length}`);

    const usage = events.find((e) => e.usage);
    assert.ok(usage, 'a usage frame should be sent');
    assert.ok(usage.usage.completion_tokens > 0);
  });

  await test('an unknown model is rejected with a helpful message', async () => {
    const res = await chat({
      model: 'does-not-exist',
      messages: [{ role: 'user', content: 'hi' }],
    });
    assert.equal(res.status, 404);
    const body = await res.json();
    assert.match(body.error.message, /mock-concise/);
  });

  server.kill('SIGTERM');
  console.log(`\n  ${passed} passed, ${failed} failed\n`);
  process.exit(failed === 0 ? 0 : 1);
})().catch((err) => {
  console.error(err);
  if (server) server.kill('SIGTERM');
  process.exit(1);
});
