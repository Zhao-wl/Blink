import { build } from 'esbuild';
import { mkdir, readFile, writeFile, copyFile } from 'node:fs/promises';

await mkdir('www', { recursive: true });
await build({entryPoints:['mobile/bridge.js'],bundle:true,outfile:'www/bridge.js',format:'iife',target:'es2022'});
const html=await readFile('index.html','utf8');
await writeFile('www/index.html',html.replace('<script>', '<script src="bridge.js"></script>\n<script>'));

for(const name of ['sync.js','account.js'])await copyFile(name,'www/'+name);
