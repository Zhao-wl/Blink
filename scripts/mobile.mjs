import {execFileSync} from 'node:child_process';
import {copyFile, mkdir, readFile, writeFile} from 'node:fs/promises';
import {existsSync} from 'node:fs';
const platform=process.argv[2];
if(!['ios','android'].includes(platform))throw Error('Expected ios or android');
const cli='./node_modules/@capacitor/cli/bin/capacitor';
execFileSync(process.execPath,[cli,existsSync(platform)?'sync':'add',platform],{stdio:'inherit'});
if(platform==='android'){
  const dir='android/app/src/main/res/raw';await mkdir(dir,{recursive:true});await copyFile('mobile/blink.wav',dir+'/blink.wav');
  await copyFile('mobile/quiet.wav',dir+'/quiet.wav');
  const java='android/app/src/main/java/com/zhaowl/blink';
  for(const name of ['MainActivity','BlinkCloudPlugin','LockClockActivity','LockFace','ReminderAction','BlinkTimerAlarm','BlinkTimerService','BlinkTimerPlugin'])await copyFile('mobile/android/'+name+'.java',java+'/'+name+'.java');
  await copyFile('mobile/android/blink_notification.xml','android/app/src/main/res/drawable/blink_notification.xml');
  const manifest='android/app/src/main/AndroidManifest.xml';
  const xml=await readFile(manifest,'utf8');
  const service='<service android:name=".BlinkTimerService" android:exported="false" android:stopWithTask="false" android:foregroundServiceType="specialUse"><property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="User enabled work and break countdown with local reminders"/></service>';
  const patched=xml.includes('.BlinkTimerService')?xml:xml.replace('</application>',service+'</application>').replace('</manifest>','<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/><uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/></manifest>');
  const activity='<activity android:name=".LockClockActivity" android:exported="false" android:excludeFromRecents="true" android:theme="@android:style/Theme.Material.NoActionBar" android:showWhenLocked="true" />';
  const withClock=patched.includes('.LockClockActivity')?patched:patched.replace('</application>',activity+'</application>');
  const receiver='<receiver android:name="com.zhaowl.blink.ReminderAction" android:exported="false"/>';
  const actions=withClock.includes('com.zhaowl.blink.ReminderAction')?withClock:withClock.replace('</application>',receiver+'</application>').replace('</manifest>','<uses-permission android:name="android.permission.WAKE_LOCK"/><uses-permission android:name="android.permission.TURN_SCREEN_ON"/></manifest>');
  const alarm='<receiver android:name="com.zhaowl.blink.BlinkTimerAlarm" android:exported="false"><intent-filter><action android:name="android.intent.action.BOOT_COMPLETED"/><action android:name="android.intent.action.MY_PACKAGE_REPLACED"/><action android:name="android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"/></intent-filter></receiver>';
  const withAlarm=actions.includes('com.zhaowl.blink.BlinkTimerAlarm')?actions:actions.replace('</application>',alarm+'</application>')
    .replace('</manifest>','<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/></manifest>');
  await writeFile(manifest,withAlarm.replace(/android:allowBackup="true"/,'android:allowBackup="false"'));
}else{
  // The web bundle is a resources folder in the SPM-based Capacitor app.
  await copyFile('mobile/blink.wav','ios/App/App/public/blink.wav');
}
execFileSync('python3',['scripts/icons.py',platform],{stdio:'inherit'});
if(platform==='ios')execFileSync('ruby',['mobile/ios/widget.rb'],{stdio:'inherit'});
