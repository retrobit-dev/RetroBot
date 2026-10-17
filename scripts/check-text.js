#!/usr/bin/env node
/**
 * Scans the Markdown files for stray characters.
 *
 * The docs are written in Indonesian, and the occasional character from
 * another script slips in while editing. Those are invisible in a diff
 * review and look like garbage to a reader, so this checks for them.
 *
 * Run: node scripts/check-text.js
 */

'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.join(__dirname, '..');

/** Characters that legitimately appear in the docs. */
const ALLOWED = new Set([
  ...'—–·→←…“”‘’«»±°×÷≈≤≥≠',
  ...'─│┌┐└┘├┤┬┴┼',
  ...'✓✗●○◆◈▪▫▼▲◀▶►◄',
  ...'₀₁₂₃',
  ...'áéíóúàèìòùâêîôûäëïöüñç',
  ...'ÁÉÍÓÚÀÈÌÒÙÂÊÎÔÛÄËÏÖÜÑÇ',
  ...'€$£¥',
]);

/** Files we expect to be plain text. */
const TARGETS = ['README.md', 'docs'];

let problems = 0;
let checked = 0;

function scan(file, text) {
  checked++;
  const lines = text.split('\n');
  lines.forEach((line, index) => {
    for (const ch of line) {
      const code = ch.codePointAt(0);
      if (code < 128) continue;
      if (ALLOWED.has(ch)) continue;

      // Flag anything that looks like it belongs to a different script.
      const isLatin = /[A-Za-zÀ-ɏ]/.test(ch);
      if (isLatin) continue;

      problems++;
      console.log(
        `  ✗ ${path.relative(ROOT, file)}:${index + 1}: stray character ${JSON.stringify(ch)} ` +
          `(U+${code.toString(16).toUpperCase().padStart(4, '0')})`,
      );
      console.log(`      ${line.trim().slice(0, 100)}`);
      break;
    }
  });
}

function walk(target) {
  const full = path.join(ROOT, target);
  if (!fs.existsSync(full)) return;
  const stat = fs.statSync(full);
  if (stat.isFile()) {
    if (full.endsWith('.md')) scan(full, fs.readFileSync(full, 'utf8'));
    return;
  }
  for (const entry of fs.readdirSync(full, { withFileTypes: true })) {
    walk(path.join(target, entry.name));
  }
}

console.log('\n  Documentation text\n');

for (const target of TARGETS) walk(target);

console.log(
  problems === 0
    ? `  ✓ ${checked} files checked, no stray characters\n`
    : `  ✗ ${checked} files checked, ${problems} problems\n`,
);

process.exit(problems === 0 ? 0 : 1);
