// Semantic test of the top-level-await wrapping: translate a small graph, run it in Node.
import fs from 'node:fs';
globalThis.self = globalThis;
globalThis.Babel = (await import('@babel/standalone')).default;
const D = process.argv[2];
const files = {
  'a.js': `const v = await new Promise(r => setTimeout(() => r(5), 20)); const w = v * 2; export { v, w as double };`,
  'b.js': `import { v, double } from './a.js'; const seen = [v, double]; export { seen };`,
  'c.js': `import { seen } from './b.js'; export const ignore = 0;`.replace('export const ignore = 0;', 'const out = seen.concat(["c"]); export { out };'),
  'd.js': `const lazy = await import('./a.js'); const fromDyn = lazy.double; export { fromDyn };`,
  // cycle: e needs f (top-level await); f imports e back
  'e.js': `import { helper } from './f.js'; const r = helper() + 1; export { r };`,
  'f.js': `import { r } from './e.js'; await null; function helper(){ return 7 } const later = () => r; export { helper, later };`,
  // cycle where the partner calls a hoisted export while loading
  'g.js': `import { h } from './h.js'; function gfn(){ return 'g' } const gv = h; export { gfn, gv };`,
  'h.js': `import { gfn } from './g.js'; const fromG = typeof gfn === 'function' ? gfn() : 'missing'; await null; const h = fromG + 'h'; export { h };`,
};
for (const [n, c] of Object.entries(files)) fs.writeFileSync(`${D}/src/${n}`, c);
globalThis.fetch = async (url) => new Response(fs.readFileSync(`${D}/src/` + url.split('/').pop(), 'utf8'), { headers: { 'content-type': 'application/javascript' } });
const { convertModule } = await import('../src/convert.js');
for (const n of Object.keys(files)) fs.writeFileSync(`${D}/out/${n}`, await (await convertModule('https://t.test/' + n, {})).text());
const c = await import(`${D}/out/c.js`); await c.__ghostTla;
const d = await import(`${D}/out/d.js`); await d.__ghostTla;
console.log('c.out =', JSON.stringify(c.out), '(want [5,10,"c"])', ' d.fromDyn =', d.fromDyn, '(want 10)');
for (const n of ['e.js','f.js','g.js','h.js']) fs.writeFileSync(`${D}/out/${n}`, await (await convertModule('https://t.test/' + n, {})).text());
const e = await import(`${D}/out/e.js`); await e.__ghostTla;
const g = await import(`${D}/out/g.js`); await g.__ghostTla;
console.log('e.r =', e.r, '(want 8)  g.gv =', g.gv, '(want "gh")');
console.log(JSON.stringify(c.out) === '[5,10,"c"]' && d.fromDyn === 10 && e.r === 8 && g.gv === 'gh' ? 'TLA SEMANTICS PASS' : 'TLA SEMANTICS FAIL');
