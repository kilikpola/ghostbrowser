import { build } from 'esbuild';

await build({
  entryPoints: ['src/index.js'],
  bundle: true,
  minify: true,
  format: 'iife',
  target: ['chrome61'],
  legalComments: 'none',
  outfile: '../assets/esm.js',
  logLevel: 'info',
});
