import { build, context } from 'esbuild';
import { cp, mkdir } from 'node:fs/promises';

const watch = process.argv.includes('--watch');

/** @type {import('esbuild').BuildOptions[]} */
const targets = [
  {
    entryPoints: ['src/main/main.ts'],
    outfile: 'dist/main/main.js',
    platform: 'node',
    target: 'node20',
    external: ['electron'],
  },
  {
    entryPoints: ['src/preload/preload.ts'],
    outfile: 'dist/preload/preload.js',
    platform: 'node',
    target: 'node20',
    external: ['electron'],
  },
  {
    entryPoints: ['src/renderer/app.ts'],
    outfile: 'dist/renderer/app.js',
    platform: 'browser',
    target: 'chrome128',
    // IIFE, not cjs: a classic script's top-level `var` lands on `window`,
    // where it would collide with the read-only `window.desk` that
    // contextBridge installs.
    format: 'iife',
  },
];

const common = { bundle: true, format: 'cjs', sourcemap: true, logLevel: 'info' };

async function copyStatic() {
  await mkdir('dist/renderer', { recursive: true });
  await cp('src/renderer/index.html', 'dist/renderer/index.html');
  await cp('src/renderer/styles.css', 'dist/renderer/styles.css');
}

await copyStatic();

if (watch) {
  for (const t of targets) {
    const ctx = await context({ ...common, ...t });
    await ctx.watch();
  }
  console.log('watching…');
} else {
  await Promise.all(targets.map((t) => build({ ...common, ...t })));
}
