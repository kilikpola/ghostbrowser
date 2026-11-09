// Mimic es-module-shims: request every module concurrently (shuffled), then check that every
// importer of a top-level-await module waits for it.
globalThis.self = globalThis;
globalThis.Babel = (await import('@babel/standalone')).default;
const urls = (await import('node:fs')).readFileSync(process.argv[2], 'utf8').trim().split('\n').map(p => 'https://solvely.ai' + p);
for (let i = urls.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [urls[i], urls[j]] = [urls[j], urls[i]]; }
const { convertModule } = await import('../src/convert.js');
const out = {};
await Promise.all(urls.map(async u => { out[u] = await (await convertModule(u, {})).text(); }));
let violations = [], tla = 0;
for (const [u, code] of Object.entries(out)) {
  if (code.startsWith('/*ghost:tla*/')) tla++;
  for (const m of code.matchAll(/import\s*\{[^}]*\}\s*from\s*"(\.{0,2}\/[^"]+\.js)"/g)) {
    const dep = new URL(m[1], u).href;
    if (!out[dep] || !out[dep].startsWith('/*ghost:tla*/') || /__ghostTla as/.test(m[0])) continue;
    if (!code.includes('__ghostTla as __ghostD') || !code.includes(`from ${JSON.stringify(m[1])}`)) violations.push(u.split('/').pop() + ' -> ' + m[1]);
    else if (!new RegExp('__ghostTla as __ghostD\\d+ \\} from ' + JSON.stringify(m[1]).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).test(code)) violations.push(u.split('/').pop() + ' does not wait for ' + m[1]);
  }
}
console.log(`${Object.keys(out).length} modules (concurrent), ${tla} wrapped; importers not waiting for a wrapped dependency: ${violations.length}`);
violations.slice(0, 8).forEach(v => console.log('  ' + v));
