import {build} from 'esbuild';
await build({entryPoints:['src/index.js'],bundle:true,platform:'node',format:'esm',target:'node24',
  outfile:'dist/index.mjs',
  banner:{js:"import { createRequire } from 'node:module'; const require = createRequire(import.meta.url);"}});
