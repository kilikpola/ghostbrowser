globalThis.self = globalThis;
const Babel = (await import('@babel/standalone')).default;
const { transform } = await import('sucrase');
const { prePass, postPass, fixSucrase } = await import('../src/precise.js');
const acorn = await import('acorn');
const conv = (await import('node:fs')).readFileSync(new URL('../src/convert.js', import.meta.url), 'utf8');
const LEFT = eval(conv.match(/var LEFTOVER = (\/.*\/);/)[1]);
const PLUG = eval('[' + conv.match(/var BABEL_PLUGINS = \[([\s\S]*?)\];/)[1] + ']');
const snip = c => Babel.transform(c, { sourceType: 'module', plugins: PLUG }).code;
export function translate(code) {
  let out = fixSucrase(transform(prePass(code), { transforms: [], production: true }).code);
  for (let p = 0; p < 3 && LEFT.test(out); p++) { const n = postPass(out, snip); if (n === out) break; out = n; }
  return out;
}
export function chrome61Issues(out, module) {
  const issues = new Set();
  (function walk(n) {
    if (!n || typeof n !== 'object') return; const t = n.type;
    if ((t === 'LogicalExpression' && n.operator === '??') || t === 'ChainExpression' || (t === 'CatchClause' && !n.param) ||
        t === 'PropertyDefinition' || t === 'PrivateIdentifier' || t === 'StaticBlock' || (t === 'ForOfStatement' && n.await) ||
        ((t === 'FunctionExpression' || t === 'FunctionDeclaration') && n.async && n.generator) ||
        (t === 'AssignmentExpression' && /^(\?\?|\|\||&&)=$/.test(n.operator)) || (t === 'ExportAllDeclaration' && n.exported)) issues.add(t + (n.operator || ''));
    if (t === 'Literal' && n.regex && (/\(\?<[a-zA-Z$=!]/.test(n.regex.pattern) || n.regex.flags.includes('s') || /\\[pP]\{/.test(n.regex.pattern))) issues.add('regex ' + n.regex.pattern.slice(0, 30));
    for (const k in n) walk(n[k]);
  })(acorn.parse(out, { ecmaVersion: 2022, sourceType: module ? 'module' : 'script' }));
  return [...issues];
}
if (process.argv[2] === 'unit') {
  const cases = {
    logical: `var o={a:0,b:{c:null}}; o.a ||= 5; o.b.c ??= 7; let z=1; z &&= z+1; var arr=[0]; arr[0] ||= 9; [o.a,o.b.c,z,arr[0]]`,
    logicalTernary: `var a=null, f=true; var r = f ? (a ??= 3) : 0; [a,r]`,
    regexP: `[/^\\p{L}+$/u.test("héllo"), /\\p{Script=Han}/u.test("中"), "a1!b".replace(/[\\p{P}]/gu,"")]`,
    namedGroups: `var m=/(?<y>\\d{4})-(?<mo>\\d{2})/.exec("2026-11"); [m.groups.y, m.groups.mo, "2026-11".replace(/(?<y>\\d{4})-(?<mo>\\d{2})/, "$<mo>/$<y>")]`,
    dotAll: `/a.b/s.test("a\\nb")`,
    asyncGenDecl: `async function* g(n){ for (let i=0;i<n;i++){ await null; yield i*2; } } (async()=>{ let s=[]; for await (const v of g(3)) s.push(v); return s.join(); })()`,
    asyncGenMethod: `var obj={ base:10, async *items(){ yield this.base; yield await Promise.resolve(this.base+1); } }; (async()=>{ let s=[]; for await (const v of obj.items()) s.push(v); return s.join(); })()`,
    asyncGenExpr: `var mk = async function*(){ yield "x"; }; (async()=>{ for await (const v of mk()) return v; })()`,
    privateFields: `class A { #n = 2; static #k = 3; get n(){ return this.#n * A.#k; } has(o){ return #n in o; } #inc(){ this.#n++; } bump(){ this.#inc(); return this.#n; } } var a=new A(); [a.n, a.has(a), a.bump()]`,
    staticBlock: `class B { static v; static { B.v = 42; } } B.v`,
    nullishOptional: `var o={a:{b:null}}; [o?.a?.b ?? "d", o.x?.y, (()=>{ try { throw 1 } catch { return "c" } })()]`,
  };
  let pass = 0;
  for (const [name, code] of Object.entries(cases)) {
    let want, got, out, issues;
    try { want = await (0, eval)(code); } catch (e) { want = 'ERR ' + e.message; }
    try { out = translate(code); issues = chrome61Issues(out, false); got = await (0, eval)(out); } catch (e) { got = 'ERR ' + e.message; issues = issues || []; }
    const ok = JSON.stringify(want) === JSON.stringify(got) && !issues.length;
    if (ok) pass++;
    console.log(`${ok ? 'PASS' : 'FAIL'} ${name.padEnd(16)} want=${JSON.stringify(want)} got=${JSON.stringify(got)}${issues.length ? ' issues=' + issues : ''}`);
  }
  console.log(`${pass}/${Object.keys(cases).length} passed`);
}
