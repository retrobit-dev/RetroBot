#!/usr/bin/env node
/**
 * Static sanity checks for the Kotlin sources.
 *
 * This cannot replace a real compile — the sandbox has no JDK — but it
 * catches the classes of mistake that are easy to make and expensive to
 * debug on a machine that does have one:
 *
 *   1. unbalanced braces, parentheses or brackets
 *   2. imports of `ai.retrobot.*` that resolve to nothing
 *   3. two top-level declarations with the same name in one file
 *
 * Run: node scripts/check-kotlin.js
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

/** Collect every .kt file under a directory. */
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

/**
 * Strip comments and string literals so brace counting is not confused by
 * text inside them.
 */
function stripNoise(source) {
  let out = '';
  let i = 0;
  const n = source.length;

  while (i < n) {
    const two = source.slice(i, i + 2);
    if (two === '//') {
      while (i < n && source[i] !== '\n') i++;
      continue;
    }
    if (two === '/*') {
      i += 2;
      while (i < n && source.slice(i, i + 2) !== '*/') i++;
      i += 2;
      continue;
    }
    if (source[i] === '"') {
      if (source.slice(i, i + 3) === '"""') {
        i += 3;
        while (i < n && source.slice(i, i + 3) !== '"""') i++;
        i += 3;
        out += '""';
        continue;
      }
      i++;
      while (i < n && source[i] !== '"') {
        if (source[i] === '\\') i++;
        i++;
      }
      i++;
      out += '""';
      continue;
    }
    if (source[i] === "'") {
      i++;
      while (i < n && source[i] !== "'") {
        if (source[i] === '\\') i++;
        i++;
      }
      i++;
      out += "''";
      continue;
    }
    out += source[i];
    i++;
  }
  return out;
}

function checkBalance(file) {
  const source = fs.readFileSync(file, 'utf8');
  const clean = stripNoise(source);
  const pairs = { '{': '}', '(': ')', '[': ']' };
  const closers = { '}': '{', ')': '(', ']': '[' };
  const stack = [];

  let line = 1;
  for (const ch of clean) {
    if (ch === '\n') line++;
    if (pairs[ch]) stack.push({ ch, line });
    else if (closers[ch]) {
      const top = stack.pop();
      if (!top) {
        fail(file, `unmatched '${ch}' on line ${line}`);
        return;
      }
      if (top.ch !== closers[ch]) {
        fail(file, `'${top.ch}' opened on line ${top.line} closed by '${ch}' on line ${line}`);
        return;
      }
    }
  }
  if (stack.length) {
    const top = stack[stack.length - 1];
    fail(file, `unclosed '${top.ch}' opened on line ${top.line}`);
  }
}

const DECLARATION_PATTERNS = [
  /^[ \t]*(?:internal |public |private |abstract |open |sealed |data |value |enum |annotation )*class[ \t]+(\w+)/gm,
  /^[ \t]*(?:internal |public |private |abstract |open |sealed |data |value |enum |annotation )*interface[ \t]+(\w+)/gm,
  /^[ \t]*(?:internal |public |private |abstract |open |sealed |data |value |enum |annotation )*object[ \t]+(\w+)/gm,
  // The receiver type is optional, so `fun foo(` and `fun Bar.foo(` both match.
  /^[ \t]*(?:internal |public |private )*(?:suspend )?fun[ \t]+(?:<[^>]*>[ \t]*)?(?:[\w.]+[ \t]*\.)?(\w+)[ \t]*\(/gm,
  /^[ \t]*(?:internal |public |private )*(?:val|var)[ \t]+(\w+)/gm,
  /^[ \t]*typealias[ \t]+(\w+)/gm,
];

/**
 * Index every top-level declaration as `package.Symbol` -> [files].
 */
function buildSymbolIndex(files) {
  const index = new Map();

  for (const file of files) {
    const source = fs.readFileSync(file, 'utf8');
    const packageMatch = /^package[ \t]+([\w.]+)/m.exec(source);
    const pkg = packageMatch ? packageMatch[1] : '';

    for (const pattern of DECLARATION_PATTERNS) {
      let match;
      while ((match = pattern.exec(source)) !== null) {
        const key = pkg ? `${pkg}.${match[1]}` : match[1];
        if (!index.has(key)) index.set(key, []);
        index.get(key).push(file);
      }
    }
  }
  return index;
}

function checkImports(file, index) {
  const source = fs.readFileSync(file, 'utf8');
  const importRe = /^import[ \t]+(ai\.retrobot\.[\w.]+)/gm;
  let match;

  while ((match = importRe.exec(source)) !== null) {
    const fqn = match[1];
    if (!index.has(fqn)) {
      fail(file, `import '${fqn}' is not declared anywhere in the project`);
    }
  }
}

/** Catch two top-level declarations with the same name in one file. */
function checkDuplicateDeclarations(file) {
  const source = fs.readFileSync(file, 'utf8');
  const seen = new Map();
  const pattern =
    /^(?:internal |public |private |abstract |open |sealed |data |value )*(?:class|object|interface|fun|val|var|typealias)[ \t]+(\w+)/gm;
  let match;

  while ((match = pattern.exec(source)) !== null) {
    const name = match[1];
    seen.set(name, (seen.get(name) || 0) + 1);
  }

  for (const [name, count] of seen) {
    if (count > 1) {
      fail(file, `top-level '${name}' declared ${count} times`);
    }
  }
}

const allFiles = [...kotlinFiles(SRC), ...kotlinFiles(TEST)];
const index = buildSymbolIndex(allFiles);

console.log('\n  Kotlin static checks\n');

for (const file of allFiles) {
  checked++;
  checkBalance(file);
  checkImports(file, index);
  checkDuplicateDeclarations(file);
}

console.log(
  problems === 0
    ? `  ✓ ${checked} files checked, no problems found\n`
    : `  ✗ ${checked} files checked, ${problems} problems\n`,
);

process.exit(problems === 0 ? 0 : 1);
