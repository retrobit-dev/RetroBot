#!/usr/bin/env node
/**
 * Checks that every project symbol a file uses is actually resolvable.
 *
 * The static checker in `check-kotlin.js` verifies imports point at
 * something. This one goes the other way: it looks for uses of project
 * types and confirms the file imports them, shares their package, or
 * declares them itself.
 *
 * Run: node scripts/check-symbols.js
 */

'use strict';

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.join(__dirname, '..');
const SRC = path.join(ROOT, 'android', 'app', 'src', 'main', 'java');
const TEST = path.join(ROOT, 'android', 'app', 'src', 'test', 'java');

let problems = 0;
let checked = 0;

function fail(file, message) {
  problems++;
  console.log(`  ✗ ${path.relative(ROOT, file)}: ${message}`);
}

function kotlinFiles(dir) {
  if (!fs.existsSync(dir)) return [];
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...kotlinFiles(full));
    else if (entry.name.endsWith('.kt')) out.push(full);
  }
  return out;
}

const allFiles = [...kotlinFiles(SRC), ...kotlinFiles(TEST)];

/** project package -> set of top-level symbols declared there */
const symbolsByPackage = new Map();
/** symbol -> packages that declare it */
const packagesBySymbol = new Map();

const DECLARATION = /^(?:internal |public |private |abstract |open |sealed |data |value |enum |annotation )*(?:class|interface|object|fun|val|var|typealias)[ \t]+(\w+)/gm;

for (const file of allFiles) {
  const source = fs.readFileSync(file, 'utf8');
  const packageMatch = /^package[ \t]+([\w.]+)/m.exec(source);
  const pkg = packageMatch ? packageMatch[1] : '';

  if (!symbolsByPackage.has(pkg)) symbolsByPackage.set(pkg, new Set());
  const bucket = symbolsByPackage.get(pkg);

  let match;
  DECLARATION.lastIndex = 0;
  while ((match = DECLARATION.exec(source)) !== null) {
    bucket.add(match[1]);
    if (!packagesBySymbol.has(match[1])) packagesBySymbol.set(match[1], new Set());
    packagesBySymbol.get(match[1]).add(pkg);
  }
}

/** Every top-level project symbol, so we know which names to look for. */
const projectSymbols = new Set();
for (const [pkg, symbols] of symbolsByPackage) {
  if (!pkg.startsWith('ai.retrobot')) continue;
  for (const symbol of symbols) projectSymbols.add(symbol);
}

console.log('\n  Kotlin symbol resolution\n');

for (const file of allFiles) {
  checked++;
  const source = fs.readFileSync(file, 'utf8');
  const packageMatch = /^package[ \t]+([\w.]+)/m.exec(source);
  const pkg = packageMatch ? packageMatch[1] : '';

  // Strip comments and strings so mentions inside them do not count.
  const code = source
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/\/\/[^\n]*/g, ' ')
    .replace(/""".*?"""/gs, ' ')
    .replace(/"(?:[^"\\]|\\.)*"/g, ' ')
    .replace(/'(?:[^'\\]|\\.)*'/g, ' ');

  const imported = new Set();
  const importRe = /^import[ \t]+([\w.]+)/gm;
  let match;
  while ((match = importRe.exec(source)) !== null) {
    imported.add(match[1].split('.').pop());
  }

  const declaredHere = new Set();
  DECLARATION.lastIndex = 0;
  while ((match = DECLARATION.exec(source)) !== null) declaredHere.add(match[1]);

  const samePackage = symbolsByPackage.get(pkg) || new Set();

  for (const symbol of projectSymbols) {
    // Only care about symbols that are actually referenced in the file.
    const used = new RegExp(`\\b${symbol}\\b`).test(code);
    if (!used) continue;

    if (declaredHere.has(symbol)) continue;
    if (imported.has(symbol)) continue;
    if (samePackage.has(symbol)) continue;

    // A symbol declared in more than one package is only a problem if none
    // of them is this file's package and it is not imported.
    fail(file, `uses '${symbol}' but does not import it`);
  }
}

console.log(
  problems === 0
    ? `  ✓ ${checked} files checked, every project symbol resolves\n`
    : `  ✗ ${checked} files checked, ${problems} problems\n`,
);

process.exit(problems === 0 ? 0 : 1);
