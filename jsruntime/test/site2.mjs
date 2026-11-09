globalThis.self = globalThis;
globalThis.Babel = (await import('@babel/standalone')).default;
const acorn = await import('acorn');
const realFetch = globalThis.fetch;
const UA = 'Mozilla/5.0 (Linux; Android 7.1.2) AppleWebKit/537.36 Chrome/61.0 Safari/537.36';
globalThis.fetch = (u, o) => realFetch(u, { headers: { 'User-Agent': UA } });
const { convertModule } = await import('../src/convert.js');
const root = 'https://solvely.ai' + process.argv[2];
const t0 = performance.now();
await convertModule(root, {});           // prepares the whole static graph
// then fetch every module es-module-shims would load (static + dynamic), like the runtime does
const seen = new Set(), q = [root], bad = []; let tla = 0;
while (q.length) {
  const u = q.shift(); if (seen.has(u)) continue; seen.add(u);
  const code = await (await convertModule(u, {})).text();
  if (code.startsWith('/*ghost:tla*/')) tla++;
  try {
    const ast = acorn.parse(code, { ecmaVersion: 2021, sourceType: 'module' }); // 2021: no top-level await, no class fields
    (function walk(n) {
      if (!n || typeof n !== 'object') return; const t = n.type;
      if ((t === 'LogicalExpression' && n.operator === '??') || t === 'ChainExpression' || (t === 'CatchClause' && !n.param) ||
          (t === 'ForOfStatement' && n.await) || ((t === 'FunctionExpression' || t === 'FunctionDeclaration') && n.async && n.generator) ||
          (t === 'AssignmentExpression' && /^(\?\?|\|\||&&)=$/.test(n.operator)) || (t === 'ExportAllDeclaration' && n.exported) ||
          (t === 'Literal' && n.regex && (/\(\?<[a-zA-Z$=!]/.test(n.regex.pattern) || n.regex.flags.includes('s') || /\\[pP]\{/.test(n.regex.pattern))))
        throw new Error(t);
      for (const k in n) walk(n[k]);
    })(ast);
  } catch (e) { bad.push(u.split('/').pop() + ': ' + e.message.slice(0, 70)); }
  for (const m of code.matchAll(/(from\s*|import\s*\(\s*)["'](\.{0,2}\/[^"']+\.js)["']/g)) q.push(new URL(m[2], u).href);
}
console.log(`${seen.size} modules; ${tla} wrapped for top-level await; ${((performance.now() - t0) / 1000).toFixed(1)} s`);
console.log(bad.length ? 'PROBLEMS:\n  ' + bad.join('\n  ') : 'ALL CLEAN for Chrome 61 (incl. top-level await)');
