/*
 * Verifies that every translation key referenced from Java actually exists in the
 * matching bundle, and reports bundle entries that are never used.
 *
 * This is the check that catches a typo'd key: without it a missing key only shows up
 * at runtime as "agent.foo.bar" rendered literally in chat, which is easy to ship by
 * accident and annoying for players to report.
 *
 * Usage: node scripts/check-lang.js --keys
 */
const fs = require('fs');
const path = require('path');

const SRC = 'src/main/java';

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(p, out);
    else if (entry.name.endsWith('.java')) out.push(p);
  }
  return out;
}

const javaFiles = walk(SRC);

/** key -> [files that reference it] */
const used = new Map();

function record(key, file) {
  if (!used.has(key)) used.set(key, new Set());
  used.get(key).add(file);
}

for (const file of javaFiles) {
  const text = fs.readFileSync(file, 'utf8');

  // AgentLang.t("...") / AgentLang.t(lang, "...")
  for (const m of text.matchAll(/AgentLang\.t\(\s*(?:[A-Za-z_][\w.]*\s*,\s*)?"([^"]+)"/g)) {
    record(m[1], file);
  }
  // Component.translatable("...")
  for (const m of text.matchAll(/Component\.translatable\(\s*"([^"]+)"/g)) {
    record(m[1], file);
  }
}

// Keys passed through a variable (e.g. addToggle(x, y, "aisteve.screen.cap.mine", ...))
// are picked up by scanning for the naming convention in string literals.
for (const file of javaFiles) {
  const text = fs.readFileSync(file, 'utf8');
  for (const m of text.matchAll(/"((?:aisteve|agent)\.[a-z0-9_.]+)"/g)) {
    record(m[1], file);
  }
}

const langZh = JSON.parse(fs.readFileSync('src/main/resources/assets/aisteve/lang/zh_cn.json', 'utf8'));
const langEn = JSON.parse(fs.readFileSync('src/main/resources/assets/aisteve/lang/en_us.json', 'utf8'));
const agentZh = JSON.parse(fs.readFileSync('src/main/resources/assets/aisteve/agent/strings_zh_cn.json', 'utf8'));
const agentEn = JSON.parse(fs.readFileSync('src/main/resources/assets/aisteve/agent/strings_en_us.json', 'utf8'));

const known = new Set([
  ...Object.keys(langZh), ...Object.keys(langEn),
  ...Object.keys(agentZh), ...Object.keys(agentEn),
]);

const missing = [];
for (const [key, files] of used) {
  if (!known.has(key)) {
    missing.push(key + '  <- ' + [...files].join(', '));
  }
}

const referenced = new Set(used.keys());
const unused = [...known].filter(k => !referenced.has(k) && !k.startsWith('_'));

console.log('Referenced from Java: ' + used.size);
console.log('Defined in bundles : ' + known.size);
console.log('');

if (missing.length) {
  console.log('MISSING (referenced but not defined) - ' + missing.length);
  for (const m of missing.sort()) console.log('  ' + m);
} else {
  console.log('MISSING: none - every referenced key is defined.');
}

console.log('');
if (unused.length) {
  console.log('UNUSED (defined but never referenced) - ' + unused.length);
  for (const u of unused.sort()) console.log('  ' + u);
} else {
  console.log('UNUSED: none.');
}
