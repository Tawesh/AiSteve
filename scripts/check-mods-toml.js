#!/usr/bin/env node
/**
 * Guards `META-INF/mods.toml`, the file that supplies the mod's metadata to the game
 * (name, description, homepage link) - the one place a mistake is *silent*.
 *
 * Why this exists: the mod shipped for several releases with
 * `displayURL="https://github.com/yourusername/aisteve"`. Forge 1.20.1 has no `displayURL`
 * key - the homepage link is `modUrl` - and NightConfig ignores unrecognised keys without a
 * word. So the placeholder never appeared in-game, nothing logged a warning, and the mod list
 * simply showed no link at all. `authors` is the same story: Forge dropped it (it belongs to
 * Fabric's fabric.mod.json), so it was quietly doing nothing.
 *
 * The key lists below were read out of fmlloader-1.20.1-47.2.0 rather than guessed:
 *   ModInfo     -> per-mod keys
 *   ModFileInfo -> file-level keys
 *
 * Zero dependencies and a deliberately small TOML reader (only the shapes a mods.toml uses),
 * so it can run in CI next to the other check-*.js scripts.
 *
 * Usage: node scripts/check-mods-toml.js [path/to/mods.toml]
 *        (the optional path lets the guard itself be tested against a deliberately broken copy)
 */

const fs = require('fs');
const path = require('path');

const MODS_TOML = process.argv[2]
    ? path.resolve(process.argv[2])
    : path.join(__dirname, '..', 'src', 'main', 'resources', 'META-INF', 'mods.toml');

/** Keys Forge 1.20.1 reads from the file header (ModFileInfo). */
const FILE_KEYS = new Set([
    'modLoader', 'loaderVersion', 'license', 'issueTrackerURL',
    'showAsResourcePack', 'services', 'properties', 'mods',
]);

/** Keys Forge 1.20.1 reads from each `[[mods]]` entry (ModInfo). */
const MOD_KEYS = new Set([
    'modId', 'namespace', 'version', 'displayName', 'description',
    'logoFile', 'logoBlur', 'updateJSONURL', 'modUrl', 'modproperties',
    'features', 'dependencies',
]);

/** Values that mean "someone forgot to fill this in". */
const PLACEHOLDERS = [
    'yourusername', 'your-username', 'yourname', 'username',
    'example.com', 'example.org', 'changeme', 'placeholder',
    'todo', 'tbd', 'xxx', 'foo/bar',
];

let failures = 0;
const fail = msg => { failures++; console.log('FAIL  ' + msg); };
const ok = msg => console.log('OK    ' + msg);

// ---------------------------------------------------------------------------
// Minimal reader: yields { section, key, value } for the shapes mods.toml uses.
// ---------------------------------------------------------------------------

function readEntries(text) {
    const lines = text.split(/\r?\n/);
    const entries = [];
    let section = 'file';          // 'file' | 'mod' | 'other'
    let inMultiline = false;       // inside ''' or """
    let fence = null;

    for (const rawLine of lines) {
        const line = inMultiline ? rawLine : rawLine.replace(/(^|\s)#.*$/, '');
        const trimmed = line.trim();

        if (inMultiline) {
            if (trimmed.includes(fence)) inMultiline = false;
            continue;
        }
        if (!trimmed) continue;

        if (trimmed.startsWith('[[')) {
            // [[mods]] / [[dependencies.x]] / [[mods.foo]] ...
            const name = trimmed.replace(/^\[\[/, '').replace(/\]\].*$/, '').trim();
            section = name === 'mods' ? 'mod' : 'other';
            continue;
        }
        if (trimmed.startsWith('[')) {
            section = 'other';
            continue;
        }

        const eq = trimmed.indexOf('=');
        if (eq < 0) continue;
        const key = trimmed.slice(0, eq).trim().replace(/^["']|["']$/g, '');
        let value = trimmed.slice(eq + 1).trim();

        if (value.startsWith("'''") || value.startsWith('"""')) {
            fence = value.slice(0, 3);
            // Single-line multiline, e.g. '''text'''
            if (!value.slice(3).includes(fence)) inMultiline = true;
        }
        entries.push({ section, key });
    }
    return entries;
}

function extractScalar(text, key) {
    const re = new RegExp('^\\s*' + key + '\\s*=\\s*"([^"]*)"', 'm');
    const m = text.match(re);
    return m ? m[1] : null;
}

// ---------------------------------------------------------------------------

if (!fs.existsSync(MODS_TOML)) {
    console.error('FAIL  mods.toml not found at ' + MODS_TOML);
    process.exit(1);
}

const text = fs.readFileSync(MODS_TOML, 'utf8');
const entries = readEntries(text);

// 1) Only keys Forge actually reads.
for (const { section, key } of entries) {
    if (section === 'file' && !FILE_KEYS.has(key)) {
        fail(`unrecognised file-level key "${key}" - Forge ignores it silently`);
    } else if (section === 'mod' && !MOD_KEYS.has(key)) {
        fail(`unrecognised [[mods]] key "${key}" - Forge ignores it silently`);
    }
}

// 2) The homepage link must exist, be a real URL, and not be a placeholder.
const modUrl = extractScalar(text, 'modUrl');
if (!modUrl) {
    fail('no "modUrl" in [[mods]] - the in-game mod list will show no homepage link');
} else if (!/^https:\/\/\S+\.\S+/.test(modUrl)) {
    fail(`modUrl is not a plain https URL: ${modUrl}`);
} else if (PLACEHOLDERS.some(p => modUrl.toLowerCase().includes(p))) {
    fail(`modUrl still looks like a placeholder: ${modUrl}`);
} else {
    ok(`modUrl -> ${modUrl}`);
}

// 3) Issue tracker is optional, but must be sane when present.
const issueUrl = extractScalar(text, 'issueTrackerURL');
if (issueUrl && PLACEHOLDERS.some(p => issueUrl.toLowerCase().includes(p))) {
    fail(`issueTrackerURL still looks like a placeholder: ${issueUrl}`);
} else if (issueUrl) {
    ok(`issueTrackerURL -> ${issueUrl}`);
}

// 4) The description is what players read in the mod list: make sure it is real.
const descMatch = text.match(/description\s*=\s*'''([\s\S]*?)'''/);
if (!descMatch) {
    fail('no multi-line description in [[mods]] - the mod list would say "MISSING DESCRIPTION"');
} else {
    const desc = descMatch[1].trim();
    if (desc.length < 80) {
        fail(`description is suspiciously short (${desc.length} chars)`);
    } else if (/yourusername|example\.com|TODO/i.test(desc)) {
        fail('description still contains placeholder text');
    } else {
        ok(`description present (${desc.length} chars, ${desc.split(/\n/).length} lines)`);
    }
}

// 5) modUrl and issueTrackerURL should point at the same project.
if (modUrl && issueUrl) {
    const repo = m => m.replace(/^https:\/\/github\.com\//i, '').replace(/[#/?].*$/, '').replace(/\/+$/, '').toLowerCase();
    if (repo(modUrl) !== repo(issueUrl).replace(/\/issues$/, '')) {
        fail(`modUrl and issueTrackerURL point at different projects (${repo(modUrl)} vs ${repo(issueUrl)})`);
    }
}

// 6) Sanity: the mod id must match the one the code uses.
const modId = extractScalar(text, 'modId');
const steveMod = path.join(__dirname, '..', 'src', 'main', 'java', 'com', 'steve', 'ai', 'SteveMod.java');
if (modId && fs.existsSync(steveMod)) {
    const src = fs.readFileSync(steveMod, 'utf8');
    const declared = (src.match(/MODID\s*=\s*"([^"]+)"/) || [])[1];
    if (declared && declared !== modId) {
        fail(`mods.toml modId "${modId}" does not match SteveMod.MODID "${declared}"`);
    } else if (declared) {
        ok(`modId "${modId}" matches SteveMod.MODID`);
    }
}

console.log('');
if (failures > 0) {
    console.log(`${failures} problem(s) found in mods.toml.`);
    console.log('Remember: an unrecognised key is ignored without any warning, so a typo');
    console.log('here shows up only as "missing" in the in-game mod list.');
    process.exit(1);
}
console.log('mods.toml is valid: every key is one Forge 1.20.1 actually reads.');
