// Apply only to the generated project for the separate internal testing channel.
import { readFile, writeFile } from 'node:fs/promises';
const code=Number(process.env.GITHUB_RUN_NUMBER || 1);
if(!Number.isSafeInteger(code)||code<1)throw Error('Invalid Android build number');
const gradle='android/app/build.gradle',xml='android/app/src/main/AndroidManifest.xml';
let text=await readFile(gradle,'utf8');
text=text.replace(/applicationId "com.zhaowl.blink(?:.stage)?"/,'applicationId "com.zhaowl.blink.stage"')
  .replace(/versionCode \d+/,'versionCode '+code).replace(/versionName "[^"]+"/,'versionName "0.2.'+code+'"');
await writeFile(gradle,text);
let manifest=(await readFile(xml,'utf8')).replace(/android:name="\.(MainActivity|LockClockActivity|BlinkTimerService)"/g,'android:name="com.zhaowl.blink.$1"');
if(!manifest.includes('android:networkSecurityConfig'))manifest=manifest.replace('<application','<application android:networkSecurityConfig="@xml/blink_stage_network"');
if(!manifest.includes('REQUEST_INSTALL_PACKAGES'))manifest=manifest.replace('</manifest>','<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/></manifest>');
await writeFile(xml,manifest);
const config='android/app/src/main/assets/capacitor.config.json';
const settings=JSON.parse(await readFile(config,'utf8'));settings.loggingBehavior='none';await writeFile(config,JSON.stringify(settings));
await writeFile('android/app/src/main/res/xml/blink_stage_network.xml','<network-security-config><base-config cleartextTrafficPermitted="false"/></network-security-config>');
const strings='android/app/src/main/res/values/strings.xml';
await writeFile(strings,(await readFile(strings,'utf8')).replace(/>Blink<\/string>/g,'>Blink 内测</string>'));
