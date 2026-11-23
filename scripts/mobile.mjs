import {execFileSync} from 'node:child_process';
import {copyFile, mkdir, readFile, writeFile} from 'node:fs/promises';
import {existsSync} from 'node:fs';
const platform=process.argv[2];
if(!['ios','android'].includes(platform))throw Error('Expected ios or android');
const cli='./node_modules/@capacitor/cli/bin/capacitor';
execFileSync(process.execPath,[cli,existsSync(platform)?'sync':'add',platform],{stdio:'inherit'});
if(platform==='android'){
  const dir='android/app/src/main/res/raw';await mkdir(dir,{recursive:true});await copyFile('mobile/blink.wav',dir+'/blink.wav');
  const manifest='android/app/src/main/AndroidManifest.xml';
  const xml=await readFile(manifest,'utf8');
  await writeFile(manifest,xml.replace(/android:allowBackup="true"/,'android:allowBackup="false"'));
}else{
  // The web bundle is a resources folder in the SPM-based Capacitor app.
  await copyFile('mobile/blink.wav','ios/App/App/public/blink.wav');
}
