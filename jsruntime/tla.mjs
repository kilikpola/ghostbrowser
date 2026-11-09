import { translate } from './unit.mjs';
import * as acorn from 'acorn';
const UA = 'Mozilla/5.0 (Linux; Android 7.1.2) AppleWebKit/537.36 Chrome/61.0 Safari/537.36';
const seen = new Map(); const q = ['/assets/index-CHsYE0nT.js'];
while (q.length) {
  const p = q.shift(); if (seen.has(p)) continue;
  let s; try { s = await (await fetch('https://solvely.ai' + p, { headers: { 'User-Agent': UA } })).text(); } catch { continue; }
  seen.set(p, s);
  for (const m of s.matchAll(/(from\s*|import\s*\(\s*|import\s*)["'](\.{0,2}\/[^"']+\.js)["']/g)) { const n = new URL(m[2], 'https://solvely.ai' + p).pathname; if (!seen.has(n)) q.push(n); }
}
for (const [p, s] of seen) {
  const out = translate(s);
  let hits = [];
  (function walk(n, fnDepth) {
    if (!n || typeof n !== 'object') return;
    const isFn = /Function/.test(n.type) || n.type === 'ArrowFunctionExpression';
    if (fnDepth === 0 && (n.type === 'AwaitExpression' || (n.type === 'ForOfStatement' && n.await))) hits.push(out.slice(n.start, n.start + 70));
    for (const k in n) if (k !== 'type') walk(n[k], fnDepth + (isFn ? 1 : 0));
  })(acorn.parse(out, { ecmaVersion: 2022, sourceType: 'module' }), 0);
  // also: other reserved words Chrome 61 rejects in modules
  if (hits.length) console.log(`TOP-LEVEL AWAIT in ${p.split('/').pop()} (${hits.length}x): ${hits[0]}`);
}
console.log('scan done');
