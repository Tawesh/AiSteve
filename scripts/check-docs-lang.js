/*
 * Guards the language of the project's documents.
 *
 * Two failure modes this catches:
 *   1. Chinese leaking into an English document. That happens when an English doc is produced
 *      by editing a Chinese one and a table row or example is missed - which is exactly what
 *      happened to README.md, whose settings table listed the Chinese page titles a Chinese
 *      client sees while the text around it was English.
 *   2. A Chinese document silently becoming English (a bad find-and-replace), which would break
 *      the .zh-CN pairing.
 *
 * Usage: node scripts/check-docs-lang.js
 */
const fs = require('fs');

/** Must contain NO CJK, except where explicitly allowed. */
const ENGLISH_DOCS = [
  'README.md',
  'CONTRIBUTING.md',
  'CLAUDE.md',
];

/** Expected to be primarily Chinese (kept deliberately, paired by the .zh-CN suffix). */
const CHINESE_DOCS = [
  'ARCHITECTURE.md',
  'CHANGELOG.md',
  'TROUBLESHOOTING.md',
  'docs/STATUS.md',
  'docs/USAGE.zh-CN.md',
  'docs/BUILD.zh-CN.md',
  'docs/DESIGN-BRIEF.zh-CN.md',
];

const CJK = /[\u3400-\u4DBF\u4E00-\u9FFF\u3040-\u30FF\uAC00-\uD7AF\uF900-\uFAFF]/;

/**
 * Legitimate CJK in an English document:
 *  - the language switcher link
 *  - prose that is *about* Chinese
 *  - quoting a real in-game string / code constant, in a code-ish context
 */
const ALLOWED = [
  /\[简体中文\]/,                                        // language switcher
  /Chinese/i,                                            // talking about Chinese
  (line) => /"[^"]*[\u4e00-\u9fff][^"]*"/.test(line)     // a quoted CJK literal ...
         && /`[A-Za-z_][\w.#]*(?:\(\))?`/.test(line),    // ... next to a `CodeIdentifier`
];

function isAllowed(line) {
  return ALLOWED.some(rule =>
    typeof rule === 'function' ? rule(line) : rule.test(line));
}

let problems = 0;

console.log('--- English documents (must not contain Chinese) ---');
for (const file of ENGLISH_DOCS) {
  if (!fs.existsSync(file)) {
    console.log('SKIP  ' + file + '  (not found)');
    continue;
  }
  const hits = [];
  fs.readFileSync(file, 'utf8').split('\n').forEach((line, i) => {
    if (CJK.test(line) && !isAllowed(line)) {
      hits.push((i + 1) + ': ' + line.trim());
    }
  });

  if (hits.length === 0) {
    console.log('OK    ' + file);
  } else {
    problems += hits.length;
    console.log('FAIL  ' + file + '  (' + hits.length + ' line(s) with Chinese)');
    for (const h of hits) {
      console.log('        ' + (h.length > 140 ? h.slice(0, 140) + '...' : h));
    }
  }
}

console.log('');
console.log('--- Chinese documents (kept Chinese on purpose) ---');
for (const file of CHINESE_DOCS) {
  if (!fs.existsSync(file)) {
    console.log('SKIP  ' + file + '  (not found)');
    continue;
  }
  const lines = fs.readFileSync(file, 'utf8').split('\n');
  const cjk = lines.filter(l => CJK.test(l)).length;
  const pct = Math.round((cjk / Math.max(1, lines.length)) * 100);
  if (pct < 10) {
    problems++;
    console.log('FAIL  ' + file + '  (expected Chinese, only ' + pct + '% of lines have CJK)');
  } else {
    console.log('OK    ' + file + '  (' + pct + '% CJK lines)');
  }
}

console.log('');
console.log(problems === 0
  ? 'All documents match their intended language.'
  : problems + ' problem(s) found.');
process.exit(problems === 0 ? 0 : 1);
