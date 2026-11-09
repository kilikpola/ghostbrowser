import { translate, chrome61Issues } from './unit.mjs';
const UA = 'Mozilla/5.0 (Linux; Android 7.1.2) AppleWebKit/537.36 Chrome/61.0 Safari/537.36';
const seen = new Map(); const q = [process.argv[2]];
while (q.length) {
  const p = q.shift(); if (seen.has(p)) continue;
  let s; try { s = await (await fetch('https://solvely.ai' + p, { headers: { 'User-Agent': UA } })).text(); } catch { continue; }
  seen.set(p, s);
  for (const m of s.matchAll(/(from\s*|import\s*\(\s*|import\s*)["'](\.{0,2}\/[^"']+\.js)["']/g)) { const n = new URL(m[2], 'https://solvely.ai' + p).pathname; if (!seen.has(n)) q.push(n); }
}
let bad = [], t = 0, slow = [];
for (const [p, s] of seen) {
  const t0 = performance.now(); let out;
  try { out = translate(s); } catch (e) { bad.push(p.split('/').pop() + ' THROW ' + e.message.slice(0, 80)); continue; }
  const dt = performance.now() - t0; t += dt; if (dt > 500) slow.push(`${p.split('/').pop()} ${(s.length/1024)|0}KB ${dt|0}ms`);
  let iss; try { iss = chrome61Issues(out, true); } catch (e) { iss = ['PARSE ' + e.message.slice(0, 60)]; }
  if (iss.length) bad.push(p.split('/').pop() + ' ' + iss.join(','));
}
console.log(`${seen.size} modules, translated in ${(t/1000).toFixed(1)} s on this PC; slowest: ${slow.join(' | ')}`);
console.log(bad.length ? 'REMAINING:\n  ' + bad.join('\n  ') : 'ALL CLEAN for Chrome 61');
