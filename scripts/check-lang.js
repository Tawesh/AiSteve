const fs = require('fs');

const files = [
  'src/main/resources/assets/aisteve/lang/zh_cn.json',
  'src/main/resources/assets/aisteve/lang/en_us.json',
  'src/main/resources/assets/aisteve/agent/strings_zh_cn.json',
  'src/main/resources/assets/aisteve/agent/strings_en_us.json',
];

for (const f of files) {
  try {
    const json = JSON.parse(fs.readFileSync(f, 'utf8'));
    const keys = Object.keys(json);
    console.log('OK   ' + f + '   (' + keys.length + ' keys)');
  } catch (e) {
    console.log('FAIL ' + f + '   -> ' + e.message);
  }
}

// Cross-check: every key present in one language must exist in the other.
function keysOf(f) {
  return new Set(Object.keys(JSON.parse(fs.readFileSync(f, 'utf8'))));
}

const pairs = [
  ['src/main/resources/assets/aisteve/lang/zh_cn.json',
   'src/main/resources/assets/aisteve/lang/en_us.json'],
  ['src/main/resources/assets/aisteve/agent/strings_zh_cn.json',
   'src/main/resources/assets/aisteve/agent/strings_en_us.json'],
];

for (const [zh, en] of pairs) {
  const a = keysOf(zh);
  const b = keysOf(en);
  const missingInEn = [...a].filter(k => !b.has(k) && !k.startsWith('_'));
  const missingInZh = [...b].filter(k => !a.has(k) && !k.startsWith('_'));
  console.log('\n' + zh.split('/').pop() + ' vs ' + en.split('/').pop());
  console.log('  missing in en: ' + (missingInEn.length ? missingInEn.join(', ') : 'none'));
  console.log('  missing in zh: ' + (missingInZh.length ? missingInZh.join(', ') : 'none'));
}
