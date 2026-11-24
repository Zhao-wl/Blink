import {execFileSync} from 'node:child_process';
import {copyFile, mkdir, readFile, rm, writeFile} from 'node:fs/promises';
import {existsSync} from 'node:fs';
const platform=process.argv[2];
if(!['ios','android'].includes(platform))throw Error('Expected ios or android');
const cli='./node_modules/@capacitor/cli/bin/capacitor';
execFileSync(process.execPath,[cli,existsSync(platform)?'sync':'add',platform],{stdio:'inherit'});
if(platform==='android'){
  const dir='android/app/src/main/res/raw';await mkdir(dir,{recursive:true});await copyFile('mobile/blink.wav',dir+'/blink.wav');
  await copyFile('mobile/quiet.wav',dir+'/quiet.wav');
  const java='android/app/src/main/java/com/zhaowl/blink';
  for(const name of ['MainActivity','BlinkCloudPlugin','ReminderAction','BlinkTimerAlarm','BlinkTimerService','BlinkTimerPlugin','BlinkRules','BlinkWidget'])await copyFile('mobile/android/'+name+'.java',java+'/'+name+'.java');
  for(const name of ['LockClockActivity','LockFace','BlinkWorkScheduling'])await rm(java+'/'+name+'.java',{force:true});
  const res='android/app/src/main/res';
  for(const sub of ['layout','xml','drawable'])await mkdir(res+'/'+sub,{recursive:true});
  await copyFile('mobile/android/blink_notification.xml',res+'/drawable/blink_notification.xml');
  for(const file of ['layout/blink_widget.xml','xml/blink_widget_info.xml','drawable/blink_widget_bg.xml','drawable/blink_widget_button.xml'])
    await copyFile('mobile/android/res/'+file,res+'/'+file);

  // Rebuild Blink's manifest entries from scratch on every sync so removed features cannot linger.
  const manifest='android/app/src/main/AndroidManifest.xml';
  let xml=await readFile(manifest,'utf8');
  xml=xml.replace(/<service android:name="(?:com\.zhaowl\.blink)?\.BlinkTimerService"[\s\S]*?<\/service>/g,'')
    .replace(/<receiver android:name="com\.zhaowl\.blink\.(?:ReminderAction|BlinkTimerAlarm|BlinkWidget)"[^>]*\/>/g,'')
    .replace(/<receiver android:name="com\.zhaowl\.blink\.(?:ReminderAction|BlinkTimerAlarm|BlinkWidget)"[\s\S]*?<\/receiver>/g,'')
    .replace(/<activity\s+android:name="(?:com\.zhaowl\.blink)?\.LockClockActivity"[^>]*\/>/g,'');
  const components=[
    '<service android:name="com.zhaowl.blink.BlinkTimerService" android:exported="false" android:stopWithTask="false" android:foregroundServiceType="specialUse"><property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="User enabled work and break countdown with local reminders"/></service>',
    '<receiver android:name="com.zhaowl.blink.ReminderAction" android:exported="false"/>',
    '<receiver android:name="com.zhaowl.blink.BlinkTimerAlarm" android:exported="false"><intent-filter><action android:name="android.intent.action.BOOT_COMPLETED"/><action android:name="android.intent.action.MY_PACKAGE_REPLACED"/><action android:name="android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"/></intent-filter></receiver>',
    '<receiver android:name="com.zhaowl.blink.BlinkWidget" android:exported="true" android:label="Blink"><intent-filter><action android:name="android.appwidget.action.APPWIDGET_UPDATE"/></intent-filter><meta-data android:name="android.appwidget.provider" android:resource="@xml/blink_widget_info"/></receiver>'
  ];
  xml=xml.replace('</application>',components.join('')+'</application>');
  for(const permission of ['FOREGROUND_SERVICE','FOREGROUND_SERVICE_SPECIAL_USE','WAKE_LOCK','TURN_SCREEN_ON','RECEIVE_BOOT_COMPLETED','REQUEST_IGNORE_BATTERY_OPTIMIZATIONS','POST_PROMOTED_NOTIFICATIONS']){
    const tag='<uses-permission android:name="android.permission.'+permission+'"/>';
    if(!xml.includes(tag))xml=xml.replace('</manifest>',tag+'</manifest>');
  }
  await writeFile(manifest,xml.replace(/android:allowBackup="true"/,'android:allowBackup="false"'));
}else{
  // The web bundle is a resources folder in the SPM-based Capacitor app.
  await copyFile('mobile/blink.wav','ios/App/App/public/blink.wav');
}
execFileSync(process.platform==='win32'?'python':'python3',['scripts/icons.py',platform],{stdio:'inherit'});
if(platform==='ios')execFileSync('ruby',['mobile/ios/widget.rb'],{stdio:'inherit'});
