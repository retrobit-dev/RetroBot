#!/usr/bin/env node
/**
 * RetroBot — mock OpenAI-compatible provider
 * ---------------------------------------------------------------------------
 * Stands in for a real model provider so the whole arena can be exercised
 * without an API key: add it in the app as a "Custom" provider pointing at
 * this server, sync its models, and run a battle.
 *
 * It speaks the OpenAI chat-completions dialect, which is what OpenAI,
 * OpenRouter, Groq, Together, Mistral, DeepSeek and a local Ollama all speak.
 *
 *   GET  /health
 *   GET  /v1/models
 *   POST /v1/chat/completions    (streaming and non-streaming)
 *
 * The mock models have deliberately different personalities, so a battle
 * between two of them produces visibly different answers and the vote is
 * meaningful.
 *
 * Zero dependencies. Node >= 18.
 */

'use strict';

const http = require('node:http');
const crypto = require('node:crypto');
const { URL } = require('node:url');

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

const PORT = Number(process.env.PORT || 8787);
const HOST = process.env.HOST || '0.0.0.0';
const MAX_BODY_BYTES = 4 * 1024 * 1024;

/**
 * The fake catalogue. Each entry has its own voice so that a battle between
 * two of them is not a coin flip on formatting alone.
 */
const MODELS = [
  {
    id: 'mock-concise',
    name: 'Mock Concise',
    context_length: 128000,
    pricing: { prompt: '0.0000005', completion: '0.0000015' },
    voice: 'concise',
  },
  {
    id: 'mock-detailed',
    name: 'Mock Detailed',
    context_length: 200000,
    pricing: { prompt: '0.000003', completion: '0.000015' },
    voice: 'detailed',
  },
  {
    id: 'mock-hedged',
    name: 'Mock Hedged',
    context_length: 128000,
    pricing: { prompt: '0.0000008', completion: '0.0000024' },
    voice: 'hedged',
  },
  {
    id: 'mock-wrong',
    name: 'Mock Wrong',
    context_length: 128000,
    pricing: { prompt: '0.0000004', completion: '0.0000008' },
    voice: 'wrong',
  },
];

const MODEL_BY_ID = new Map(MODELS.map((m) => [m.id, m]));

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const CORS_HEADERS = {
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'GET,POST,OPTIONS',
  'access-control-allow-headers': 'authorization,content-type',
};

function json(res, status, body) {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(payload),
    ...CORS_HEADERS,
  });
  res.end(payload);
}

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY_BYTES) {
        reject(Object.assign(new Error('payload too large'), { statusCode: 413 }));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8');
      if (!raw.trim()) return resolve({});
      try {
        resolve(JSON.parse(raw));
      } catch {
        reject(Object.assign(new Error('invalid JSON body'), { statusCode: 400 }));
      }
    });
    req.on('error', reject);
  });
}

function textOf(content) {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) {
    return content
      .map((part) => (typeof part === 'string' ? part : part && part.text ? part.text : ''))
      .join(' ');
  }
  return '';
}

function lastUserText(messages) {
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m && m.role === 'user') return textOf(m.content);
  }
  return '';
}

// ---------------------------------------------------------------------------
// The fake voices
// ---------------------------------------------------------------------------

/**
 * Produce the answer for a given model and prompt.
 *
 * Every answer restates the prompt, so a test can assert that both sides of
 * a battle really did receive the same question.
 */
function answerFor(voice, prompt) {
  const question = prompt.trim() || '(no question)';

  switch (voice) {
    case 'concise':
      return [
        `**${question}**`,
        '',
        '- One: the direct answer, in a single sentence.',
        '- Two: the supporting reason, kept short.',
        '- Three: one caveat worth knowing.',
      ].join('\n');

    case 'detailed':
      return [
        `**${question}**`,
        '',
        'Here is the longer version of the answer.',
        '',
        '## Background',
        'The question has more structure than it first appears, and a complete answer ' +
          'needs to separate what is actually known from what is usually assumed.',
        '',
        '## The short answer',
        'Start with the conclusion, because a reader who stops after two paragraphs ' +
          'should still have the point.',
        '',
        '## The longer answer',
        'Then unpack it. Give the reasoning in order, name the trade-off, and say what ' +
          'would change the conclusion. Two or three worked examples beat a wall of prose.',
        '',
        '## Caveats',
        'End with what the answer does not cover, and where to look next.',
      ].join('\n');

    case 'hedged':
      return [
        `About "${question}" — it depends.`,
        '',
        'On the one hand, the straightforward reading gives a clean answer. On the other, ' +
          'the framing hides an assumption that is easy to miss.',
        '',
        'I would not want to overstate it. It is probably fine, though it could be that ' +
          'a different reading of the question changes things.',
        '',
        'Happy to go deeper on any particular part of this.',
      ].join('\n');

    case 'wrong':
      return [
        `**${question}**`,
        '',
        'The answer is 42, obviously.',
        '',
        '... correction. The question was not actually about 42. Let me start again.',
        '',
        'A more useful answer: the thing you are asking about is better understood by ' +
          'naming the constraint it runs into first.',
      ].join('\n');

    default:
      return `**${question}**\n\nThe mock provider has no voice called "${voice}".`;
  }
}

// ---------------------------------------------------------------------------
// Chat completions
// ---------------------------------------------------------------------------

function chunkText(text, size = 24) {
  const pieces = [];
  for (let i = 0; i < text.length; i += size) pieces.push(text.slice(i, i + size));
  return pieces.length ? pieces : [''];
}

function nonStreamingResponse(model, text) {
  return {
    id: `chatcmpl-${crypto.randomBytes(8).toString('hex')}`,
    object: 'chat.completion',
    created: Math.floor(Date.now() / 1000),
    model: model.id,
    choices: [
      {
        index: 0,
        message: { role: 'assistant', content: text },
        finish_reason: 'stop',
      },
    ],
    usage: {
      prompt_tokens: Math.max(1, Math.round(text.length / 4)),
      completion_tokens: Math.max(1, Math.round(text.length / 4)),
      total_tokens: Math.max(2, Math.round(text.length / 2)),
    },
  };
}

async function streamResponse(res, model, text) {
  res.writeHead(200, {
    'content-type': 'text/event-stream; charset=utf-8',
    'cache-control': 'no-cache, no-transform',
    connection: 'keep-alive',
    'x-accel-buffering': 'no',
    ...CORS_HEADERS,
  });

  const base = {
    id: `chatcmpl-${crypto.randomBytes(8).toString('hex')}`,
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model: model.id,
  };

  const send = (delta, finish = null) => {
    res.write(
      `data: ${JSON.stringify({ ...base, choices: [{ index: 0, delta, finish_reason: finish }] })}\n\n`,
    );
  };

  send({ role: 'assistant', content: '' });
  for (const piece of chunkText(text)) {
    send({ content: piece });
    await new Promise((r) => setTimeout(r, 8));
  }
  send({}, 'stop');

  // The usage frame, the way OpenAI sends it when include_usage is set.
  res.write(
    `data: ${JSON.stringify({
      ...base,
      choices: [],
      usage: {
        prompt_tokens: Math.max(1, Math.round(text.length / 4)),
        completion_tokens: Math.max(1, Math.round(text.length / 4)),
        total_tokens: Math.max(2, Math.round(text.length / 2)),
      },
    })}\n\n`,
  );

  res.write('data: [DONE]\n\n');
  res.end();
}

// ---------------------------------------------------------------------------
// Routes
// ---------------------------------------------------------------------------

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

  if (req.method === 'OPTIONS') {
    res.writeHead(204, CORS_HEADERS);
    return res.end();
  }

  try {
    if (req.method === 'GET' && url.pathname === '/health') {
      return json(res, 200, { ok: true, service: 'retrobot-mock-provider', models: MODELS.length });
    }

    if (req.method === 'GET' && url.pathname === '/v1/models') {
      return json(res, 200, {
        object: 'list',
        data: MODELS.map((m) => ({
          id: m.id,
          object: 'model',
          created: 1_700_000_000,
          owned_by: 'retrobot-mock',
          context_length: m.context_length,
          pricing: m.pricing,
        })),
      });
    }

    if (req.method === 'POST' && url.pathname === '/v1/chat/completions') {
      const body = await readJsonBody(req);
      const modelId = body.model || MODELS[0].id;
      const model = MODEL_BY_ID.get(modelId);

      if (!model) {
        return json(res, 404, {
          error: {
            message: `The model \`${modelId}\` does not exist. Available: ${MODELS.map((m) => m.id).join(', ')}`,
            type: 'invalid_request_error',
          },
        });
      }

      const messages = Array.isArray(body.messages) ? body.messages : [];
      const text = answerFor(model.voice, lastUserText(messages));

      if (body.stream) {
        await streamResponse(res, model, text);
        return;
      }
      return json(res, 200, nonStreamingResponse(model, text));
    }

    return json(res, 404, { error: { message: `no route for ${req.method} ${url.pathname}` } });
  } catch (err) {
    const status = err && err.statusCode ? err.statusCode : 500;
    return json(res, status, { error: { message: String((err && err.message) || err) } });
  }
});

server.listen(PORT, HOST, () => {
  const shown = HOST === '0.0.0.0' ? 'localhost' : HOST;
  console.log('');
  console.log('  ◈ RetroBot mock provider (OpenAI-compatible)');
  console.log(`    base url   ${shown}:${PORT}/v1`);
  console.log(`    models     ${MODELS.map((m) => m.id).join(', ')}`);
  console.log('');
});

process.on('SIGTERM', () => server.close(() => process.exit(0)));
process.on('SIGINT', () => server.close(() => process.exit(0)));
