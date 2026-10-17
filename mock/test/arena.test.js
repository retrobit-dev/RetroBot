#!/usr/bin/env node
/**
 * The arena flow, end to end: battle → vote → Elo → leaderboard.
 *
 * This mirrors what the Android app does:
 *
 *   1. The same prompt is sent to two models, side by side.
 *   2. The user votes for one of them.
 *   3. The vote updates both Elo ratings.
 *   4. The leaderboard is the ratings, sorted.
 *
 * The Elo formulas below are a direct transcription of `Elo.kt`, and the
 * expected values are hand-computed, so this test pins down the maths as
 * well as the plumbing. The Kotlin unit test asserts the same numbers.
 *
 * Run: node test/arena.test.js
 */

'use strict';

const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const path = require('node:path');

const PORT = Number(process.env.TEST_PORT || 8794);
const BASE = `http://127.0.0.1:${PORT}`;

// ---------------------------------------------------------------------------
// A transcription of ai.retrobot.core.arena.Elo
// ---------------------------------------------------------------------------

const DEFAULT_RATING = 1000;
const SCALE = 400;

function expectedScore(ratingA, ratingB) {
  return 1 / (1 + 10 ** ((ratingB - ratingA) / SCALE));
}

function kFactor(gamesPlayed) {
  if (gamesPlayed < 10) return 48;
  if (gamesPlayed < 30) return 32;
  if (gamesPlayed < 100) return 24;
  return 16;
}

function update(ratingA, ratingB, scoreA, k) {
  const delta = k * (scoreA - expectedScore(ratingA, ratingB));
  return [ratingA + delta, ratingB - delta];
}

function confidenceBand(gamesPlayed) {
  if (gamesPlayed <= 0) return 200;
  return 200 / Math.sqrt(gamesPlayed);
}

// ---------------------------------------------------------------------------
// The arena
// ---------------------------------------------------------------------------

/** A stand-in for the app's rating table. */
class Arena {
  constructor() {
    this.ratings = new Map();
    this.votes = [];
  }

  recordVote(vote) {
    this.votes.push(vote);
    this.#applyVote(vote);
  }

  #applyVote(vote) {
    const left = this.ratings.get(vote.leftModelKey) ?? blank(vote.leftModelKey);
    const right = this.ratings.get(vote.rightModelKey) ?? blank(vote.rightModelKey);

    const scoreLeft = vote.winner === 'left' ? 1 : vote.winner === 'right' ? 0 : 0.5;
    const k = kFactor(Math.max(left.games, right.games));
    const [newLeft, newRight] = update(left.rating, right.rating, scoreLeft, k);

    this.ratings.set(vote.leftModelKey, {
      ...left,
      rating: newLeft,
      games: left.games + 1,
      wins: left.wins + (vote.winner === 'left' ? 1 : 0),
      losses: left.losses + (vote.winner === 'right' ? 1 : 0),
      ties: left.ties + (vote.winner === 'tie' ? 1 : 0),
    });
    this.ratings.set(vote.rightModelKey, {
      ...right,
      rating: newRight,
      games: right.games + 1,
      wins: right.wins + (vote.winner === 'right' ? 1 : 0),
      losses: right.losses + (vote.winner === 'left' ? 1 : 0),
      ties: right.ties + (vote.winner === 'tie' ? 1 : 0),
    });
  }

  /** Rebuild every rating from the vote log, the way the app can. */
  recompute() {
    this.ratings = new Map();
    for (const vote of this.votes) this.#applyVote(vote);
  }

  leaderboard() {
    return [...this.ratings.values()].sort((a, b) => b.rating - a.rating);
  }
}

function blank(modelKey) {
  return { modelKey, rating: DEFAULT_RATING, games: 0, wins: 0, losses: 0, ties: 0 };
}

// ---------------------------------------------------------------------------
// Talking to the mock provider
// ---------------------------------------------------------------------------

async function ask(model, prompt) {
  const res = await fetch(`${BASE}/v1/chat/completions`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      model,
      stream: false,
      messages: [{ role: 'user', content: prompt }],
    }),
  });
  const body = await res.json();
  return body.choices[0].message.content;
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

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

(async () => {
  console.log('\n  Arena flow — end to end\n');

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
    setTimeout(() => reject(new Error('server did not start')), 8000);
  });

  await test('expected score of an equal pair is exactly a half', async () => {
    assert.equal(expectedScore(1000, 1000), 0.5);
  });

  await test('a 400 point gap means a 10:1 chance', async () => {
    // The weaker side is expected to score 1 in 11; the stronger 10 in 11.
    assert.ok(Math.abs(expectedScore(1000, 1400) - 1 / 11) < 1e-12);
    assert.ok(Math.abs(expectedScore(1400, 1000) - 10 / 11) < 1e-12);
    assert.ok(Math.abs(expectedScore(1000, 1400) + expectedScore(1400, 1000) - 1) < 1e-12);
  });

  await test('a win moves the winner up and the loser down by the same amount', async () => {
    const [a, b] = update(1000, 1000, 1, 32);
    assert.equal(a, 1016);
    assert.equal(b, 984);
  });

  await test('a loss is the mirror image of a win', async () => {
    const [a, b] = update(1000, 1000, 0, 32);
    assert.equal(a, 984);
    assert.equal(b, 1016);
  });

  await test('a tie between equals changes nothing', async () => {
    const [a, b] = update(1000, 1000, 0.5, 32);
    assert.equal(a, 1000);
    assert.equal(b, 1000);
  });

  await test('the K factor shrinks as games accumulate', async () => {
    assert.equal(kFactor(0), 48);
    assert.equal(kFactor(9), 48);
    assert.equal(kFactor(10), 32);
    assert.equal(kFactor(29), 32);
    assert.equal(kFactor(30), 24);
    assert.equal(kFactor(100), 16);
  });

  await test('the confidence band narrows with the square root of the games', async () => {
    assert.equal(confidenceBand(0), 200);
    assert.equal(confidenceBand(4), 100);
    assert.equal(confidenceBand(100), 20);
  });

  await test('a battle sends the same prompt to both models', async () => {
    const prompt = 'jelaskan apa itu elo rating';
    const [left, right] = await Promise.all([
      ask('mock-concise', prompt),
      ask('mock-detailed', prompt),
    ]);
    assert.match(left, /jelaskan apa itu elo rating/);
    assert.match(right, /jelaskan apa itu elo rating/);
    assert.notEqual(left, right);
  });

  await test('a vote raises the winner and lowers the loser', async () => {
    const arena = new Arena();
    arena.recordVote({
      leftModelKey: 'mock-concise',
      rightModelKey: 'mock-detailed',
      winner: 'right',
    });

    const left = arena.ratings.get('mock-concise');
    const right = arena.ratings.get('mock-detailed');

    assert.equal(left.games, 1);
    assert.equal(left.losses, 1);
    assert.equal(right.wins, 1);
    assert.ok(right.rating > DEFAULT_RATING);
    assert.ok(left.rating < DEFAULT_RATING);
    // The pair of ratings must stay centred: one gains exactly what the
    // other loses.
    assert.ok(Math.abs(left.rating + right.rating - 2000) < 1e-9);
  });

  await test('a tie counts for both and moves neither', async () => {
    const arena = new Arena();
    arena.recordVote({
      leftModelKey: 'a',
      rightModelKey: 'b',
      winner: 'tie',
    });

    assert.equal(arena.ratings.get('a').rating, DEFAULT_RATING);
    assert.equal(arena.ratings.get('b').rating, DEFAULT_RATING);
    assert.equal(arena.ratings.get('a').ties, 1);
    assert.equal(arena.ratings.get('b').ties, 1);
  });

  await test('the leaderboard is ordered by rating', async () => {
    const arena = new Arena();
    // A clear case: one model wins every game.
    for (let i = 0; i < 5; i++) {
      arena.recordVote({ leftModelKey: 'weak', rightModelKey: 'strong', winner: 'right' });
    }
    arena.recordVote({ leftModelKey: 'mid', rightModelKey: 'weak', winner: 'left' });

    const board = arena.leaderboard();
    assert.deepEqual(
      board.map((r) => r.modelKey),
      ['strong', 'mid', 'weak'],
    );
    assert.ok(board[0].rating > board[1].rating);
    assert.ok(board[1].rating > board[2].rating);
  });

  await test('beating a stronger opponent gains more than beating an equal one', async () => {
    // This is the property that makes Elo a relative system rather than an
    // absolute one.
    const gainAgainst = (opponentRating) => {
      const arena = new Arena();
      arena.ratings.set('incumbent', { ...blank('incumbent'), rating: opponentRating, games: 10 });
      arena.ratings.set('challenger', { ...blank('challenger'), rating: 1000, games: 10 });
      arena.recordVote({
        leftModelKey: 'challenger',
        rightModelKey: 'incumbent',
        winner: 'left',
      });
      return arena.ratings.get('challenger').rating - 1000;
    };

    const gainVsEqual = gainAgainst(1000);
    const gainVsStrong = gainAgainst(1400);

    // k = 32 here (ten games played).
    assert.ok(Math.abs(gainVsEqual - 16) < 1e-9, `expected +16, got ${gainVsEqual}`);
    assert.ok(
      gainVsStrong > gainVsEqual,
      `beating a 1400 should gain more than beating a 1000 (${gainVsStrong} vs ${gainVsEqual})`,
    );
    assert.ok(Math.abs(gainVsStrong - 32 * (1 - 1 / 11)) < 1e-9);
  });

  await test('recomputing from the log reproduces the cached ratings', async () => {
    const arena = new Arena();
    const votes = [
      { leftModelKey: 'a', rightModelKey: 'b', winner: 'left' },
      { leftModelKey: 'b', rightModelKey: 'c', winner: 'right' },
      { leftModelKey: 'a', rightModelKey: 'c', winner: 'tie' },
      { leftModelKey: 'a', rightModelKey: 'b', winner: 'right' },
    ];
    for (const vote of votes) arena.recordVote(vote);

    const before = new Map(arena.ratings);
    arena.recompute();

    for (const [key, rating] of arena.ratings) {
      assert.ok(
        Math.abs(rating.rating - before.get(key).rating) < 1e-9,
        `${key} changed after a recompute`,
      );
    }
  });

  await test('a model that has never been voted on keeps the default rating', async () => {
    const arena = new Arena();
    arena.recordVote({ leftModelKey: 'a', rightModelKey: 'b', winner: 'left' });
    assert.equal(arena.ratings.get('a').rating > DEFAULT_RATING, true);
    assert.equal(arena.ratings.has('never-seen'), false);
  });

  server.kill('SIGTERM');
  console.log(`\n  ${passed} passed, ${failed} failed\n`);
  process.exit(failed === 0 ? 0 : 1);
})().catch((err) => {
  console.error(err);
  if (server) server.kill('SIGTERM');
  process.exit(1);
});
