const {app,BrowserWindow,Menu,Tray,nativeImage,screen,ipcMain,powerMonitor,shell,safeStorage,net,Notification,dialog}=require('electron');
const path=require('node:path');
const fs=require('node:fs');
const crypto=require('node:crypto');
const {execFile}=require('node:child_process');
let main,popup,tray,active,expiryTimer,quitting=false,trayState={capsule:'Blink',next:'',work:false},lockedAt=0;
const CLOUD='https://47.108.62.243/';
const ROUTES=new Set(['v1/login','v1/register','v1/logout','v1/sync','v2/sync','v2/devices','v2/revoke','v2/reset','v2/password','v2/delete','v2/recovery-code','v2/presence','v2/rename','v2/pair/new','v2/pair/poll','v2/pair/approve']);
const OPEN=new Set(['v1/login','v1/register','v2/reset','v2/pair/new','v2/pair/poll']);

// Small JSON settings file next to the profile (first-close hint, account session).
const settingsFile=()=>path.join(app.getPath('userData'),'blink-desktop.json');
function settings(){try{return JSON.parse(fs.readFileSync(settingsFile(),'utf8'))}catch{return {}}}
function saveSettings(patch){const next={...settings(),...patch};fs.writeFileSync(settingsFile(),JSON.stringify(next));return next}

if(!app.requestSingleInstanceLock()){app.quit()}else{
app.on('second-instance',()=>{main?.show();main?.focus()});
app.on('before-quit',()=>{quitting=true});
app.whenReady().then(()=>{
  app.setAppUserModelId('com.zhaowl.blink');
  main=new BrowserWindow({title:'Blink',width:1120,height:740,minWidth:680,minHeight:520,show:!process.argv.includes('--hidden'),
    webPreferences:{preload:path.join(__dirname,'preload.cjs'),sandbox:true,contextIsolation:true,backgroundThrottling:false}});
  main.webContents.setWindowOpenHandler(()=>({action:'deny'}));
  main.webContents.on('will-navigate',event=>event.preventDefault());
  main.loadFile(path.join(__dirname,'../www/index.html'));
  // Closing the window keeps Blink in the tray; quitting is explicit (tray menu or Cmd+Q).
  main.on('close',event=>{
    if(quitting)return;
    event.preventDefault();main.hide();
    if(!settings().closeHinted&&Notification.isSupported()){
      saveSettings({closeHinted:true});
      new Notification({title:'Blink 仍在运行',body:'提醒会继续。右键托盘图标可退出。',silent:true}).show();
    }
  });
  main.on('closed',()=>{clear();main=null});
  const sendVisibility=()=>main&&main.webContents.send('reminder:visibility',main.isMinimized()||!main.isVisible());
  for(const event of ['minimize','restore','show','hide'])main.on(event,sendVisibility);
  main.webContents.on('did-finish-load',sendVisibility);
  tray=new Tray(nativeImage.createFromPath(path.join(__dirname,'icon.png')).resize({width:20,height:20}));
  tray.on('click',()=>{main.show();main.focus()});
  updateTray();
  app.on('activate',()=>main.show());
  const reconcile=()=>{
    if(active&&active.until<=Date.now())clear();
    if(main&&!main.isDestroyed())main.webContents.send('reminder:resume');
  };
  powerMonitor.on('resume',reconcile);
  powerMonitor.on('unlock-screen',()=>{lockedAt=0;reconcile();sendPresence()});
  powerMonitor.on('lock-screen',()=>{lockedAt=Date.now();sendPresence()});
  powerMonitor.on('suspend',()=>{lockedAt=lockedAt||Date.now()});
  setInterval(sendPresence,15000);
  setupUpdates();
});

// ---- Presence: system-wide keyboard/mouse idle time and the lock screen ----
function sendPresence(){
  if(!main||main.isDestroyed())return;
  const idle=powerMonitor.getSystemIdleTime(),locked=lockedAt>0;
  main.webContents.send('desktop:presence',{idle:locked?Math.max(idle,Math.round((Date.now()-lockedAt)/1000)):idle,locked});
}

// ---- Tray mirrors the home screen ----
function updateTray(){
  if(!tray)return;
  tray.setToolTip('Blink · '+trayState.capsule+(trayState.next?'\n'+trayState.next:''));
  const command=name=>()=>main?.webContents.send('desktop:command',name);
  tray.setContextMenu(Menu.buildFromTemplate([
    {label:trayState.capsule,enabled:false},
    {label:trayState.next||'—',enabled:false},
    {type:'separator'},
    {label:'立即休息',click:command('rest')},
    {label:'暂停提醒 1 小时',click:command('pause')},
    {label:trayState.work?'现在下班':'现在上班',click:command('shift')},
    {type:'separator'},
    {label:'打开 Blink',click:()=>{main.show();main.focus()}},
    {label:'退出',click:()=>{quitting=true;app.quit()}}
  ]));
}
ipcMain.on('desktop:status',(event,state)=>{
  if(event.sender!==main?.webContents||!state||typeof state.capsule!=='string')return;
  trayState={capsule:state.capsule.slice(0,60),next:String(state.next||'').slice(0,60),work:!!state.work};updateTray();
});

// ---- Do not interrupt full-screen apps, presentations or Focus Assist ----
// SHQueryUserNotificationState: 2 busy (full screen), 3 D3D full screen, 4 presentation, 6 quiet time, 7 app full screen.
let quietCache={at:0,value:false};
function quiet(){
  if(process.platform!=='win32')return Promise.resolve(false);
  if(Date.now()-quietCache.at<20000)return Promise.resolve(quietCache.value);
  const script='$s=Add-Type -Name Q -Namespace B -PassThru -MemberDefinition \'[DllImport("shell32.dll")] public static extern int SHQueryUserNotificationState(out int s);\';$v=0;[void]$s::SHQueryUserNotificationState([ref]$v);$v';
  return new Promise(resolve=>execFile('powershell.exe',['-NoProfile','-NonInteractive','-Command',script],{timeout:4000,windowsHide:true},(error,stdout)=>{
    const state=Number(String(stdout).trim());
    const value=!error&&[2,3,4,6,7].includes(state);
    quietCache={at:Date.now(),value};resolve(value);
  }));
}

function clear(){
  clearTimeout(expiryTimer);
  const previous=popup;
  popup=null;
  active=null;
  if(previous&&!previous.isDestroyed())previous.destroy();
}
function openPopup(card,query){
  clear();active={id:card.id,until:card.until,kind:query.mode};
  expiryTimer=setTimeout(clear,card.until-Date.now());
  const point=card.screen==='primary'?screen.getPrimaryDisplay():screen.getDisplayNearestPoint(screen.getCursorScreenPoint());
  const area=point.workArea;
  const width=356,height=query.mode==='prompt'?176:152,inset=16;
  const win=new BrowserWindow({width,height,x:area.x+area.width-width-inset,y:area.y+area.height-height-inset,
    frame:false,resizable:false,alwaysOnTop:true,skipTaskbar:true,show:false,
    backgroundColor:card.theme==='dark'?'#202432':'#ffffff',
    webPreferences:{preload:path.join(__dirname,'popup-preload.cjs'),sandbox:true,contextIsolation:true}});
  popup=win;
  win.once('ready-to-show',()=>{
    if(popup!==win)return;
    if(active.until<=Date.now()){clear();return}
    win.showInactive();
    if(card.sound)shell.beep();
  });
  win.loadFile(path.join(__dirname,'popup.html'),{query:{...query,theme:card.theme==='dark'?'dark':'light'}});
}
ipcMain.on('reminder:clear',(event,id)=>{if(event.sender===main?.webContents&&id===active?.id)clear()});
ipcMain.on('reminder:show',async(event,card)=>{
  if(event.sender!==main?.webContents||typeof card?.id!=='string'||![0,1,2].includes(card.k)||
    !Number.isFinite(card.until)||card.until<=Date.now())return;
  if(await quiet()){main.webContents.send('desktop:quiet',Date.now()+60000,card.id);return}
  openPopup(card,{mode:'reminder',kind:String(card.k),retry:card.retry?'1':'0',later:String(card.later||'5 分钟后').slice(0,12)});
});
ipcMain.on('prompt:show',(event,p)=>{
  if(event.sender!==main?.webContents||typeof p?.id!=='string'||!Array.isArray(p.actions))return;
  const actions=p.actions.slice(0,3).map(a=>[String(a[0]).slice(0,20),String(a[1]).slice(0,20)]);
  openPopup({id:p.id,until:Date.now()+30*60000,theme:p.theme,screen:p.screen},{mode:'prompt',title:String(p.title).slice(0,40),body:String(p.body||'').slice(0,80),actions:JSON.stringify(actions)});
});
ipcMain.on('prompt:clear',(event,id)=>{if(event.sender===main?.webContents&&id===active?.id)clear()});
ipcMain.on('reminder:action',(event,answer)=>{
  if(!popup||event.sender!==popup.webContents||!active)return;
  if(active.until>Date.now()){
    if(active.kind==='prompt'&&typeof answer==='string')main.webContents.send('prompt:answer',active.id,answer);
    else main.webContents.send('reminder:open',active.id,answer===true);
  }
  clear();
});

// ---- Preferences ----
ipcMain.on('app:version',event=>{event.returnValue=app.getVersion()});
ipcMain.on('autostart:get',event=>{event.returnValue=app.getLoginItemSettings().openAtLogin});
ipcMain.handle('autostart:set',(event,on)=>{app.setLoginItemSettings({openAtLogin:!!on,args:['--hidden']});return app.getLoginItemSettings().openAtLogin});
ipcMain.handle('desktop:health',async()=>{
  const busy=await quiet();
  return [
    {label:'系统通知窗口',state:true,action:null},
    {label:'开机自动启动',state:app.getLoginItemSettings().openAtLogin,action:'autostart',hint:'关闭时需要手动打开 Blink 才会提醒'},
    {label:'专注助手 / 全屏状态',state:!busy,action:'focus',hint:busy?'当前处于全屏、演示或专注助手，提醒会延后到结束后':'没有阻止提醒'}
  ];
});
ipcMain.handle('desktop:setting',(event,action)=>{
  if(action==='autostart')app.setLoginItemSettings({openAtLogin:true,args:['--hidden']});
  if(action==='focus')shell.openExternal('ms-settings:quiethours');
});

// ---- Account session: same API as Android, token sealed with the OS keychain ----
function account(){
  const s=settings();
  let session={};
  if(s.sealed&&safeStorage.isEncryptionAvailable()){try{session=JSON.parse(safeStorage.decryptString(Buffer.from(s.sealed,'base64')))}catch{}}
  if(!s.device)saveSettings({device:crypto.randomUUID()});
  return {session,device:settings().device};
}
function seal(session){
  if(!safeStorage.isEncryptionAvailable())throw Error('系统凭证存储不可用');
  saveSettings({sealed:session?safeStorage.encryptString(JSON.stringify(session)).toString('base64'):null});
}
const label=()=>(require('node:os').hostname()||'电脑').slice(0,40);
ipcMain.handle('cloud:info',()=>{
  const {session,device}=account();
  return {enabled:true,account:session.account,name:session.name,device,label:label(),version:0,versionName:app.getVersion()};
});
ipcMain.handle('cloud:request',async(event,{route,body={},account:accountId=''})=>{
  if(!ROUTES.has(route))throw Error('服务不可用');
  const {session,device}=account(),open=OPEN.has(route);
  const login=route==='v1/login'||route==='v1/register'||route==='v2/pair/poll';
  if(login||route==='v2/pair/new')Object.assign(body,{device,label:label(),platform:'desktop'});
  if(!open&&session.account!==accountId)throw Error('账号已切换');
  let response;
  try{
    response=await net.fetch(CLOUD+route,{method:'POST',body:JSON.stringify(body),redirect:'error',
      headers:{'Content-Type':'application/json',...(open?{}:{Authorization:'Bearer '+session.token})}});
  }catch{const e=Error('无法连接 Blink 服务，请检查网络后重试');throw e}
  const json=await response.json().catch(()=>({error:'request_failed'}));
  if(!response.ok){const e=Error(json.error||'request_failed');e.code=String(response.status);throw e}
  if(login&&json.token){seal({account:json.account,name:json.name,token:json.token});delete json.token}
  else if(route==='v1/logout'||route==='v2/delete')seal(null);
  return json;
});
ipcMain.handle('cloud:export',async(event,{content,name,type})=>{
  const {canceled,filePath}=await dialog.showSaveDialog(main,{defaultPath:name||'Blink-backup.json',filters:[{name:type==='text/csv'?'CSV':'JSON',extensions:[type==='text/csv'?'csv':'json']}]});
  if(canceled||!filePath)throw Error('已取消');
  fs.writeFileSync(filePath,content);
});
ipcMain.handle('cloud:import',async()=>{
  const {canceled,filePaths}=await dialog.showOpenDialog(main,{properties:['openFile'],filters:[{name:'Blink 备份',extensions:['json']}]});
  if(canceled||!filePaths[0])throw Error('已取消');
  const stat=fs.statSync(filePaths[0]);if(stat.size>8*1024*1024)throw Error('文件过大');
  return {content:fs.readFileSync(filePaths[0],'utf8')};
});

// ---- Updates: check daily in the background, install on quit ----
let updater=null,updateState='';
function setupUpdates(){
  if(!app.isPackaged)return;
  try{updater=require('electron-updater').autoUpdater}catch{return}
  updater.autoDownload=true;updater.autoInstallOnAppQuit=true;
  updater.on('update-available',info=>{updateState='正在下载 '+info.version+'…'});
  updater.on('update-not-available',()=>{updateState='已是最新版本'});
  updater.on('update-downloaded',info=>{updateState=info.version+' 已下载，退出 Blink 时自动安装';main?.webContents.send('desktop:update',info.version)});
  updater.on('error',()=>{updateState='暂时无法检查更新'});
  const check=()=>updater.checkForUpdates().catch(()=>{});
  setTimeout(check,30000);setInterval(check,24*3600000);
}
ipcMain.handle('update:check',async()=>{
  if(!updater)return app.isPackaged?'更新服务不可用':'开发版本不检查更新';
  try{const r=await updater.checkForUpdates();if(!r?.isUpdateAvailable)return '已是最新版本';return '发现 '+r.updateInfo.version+'，正在下载…'}
  catch{return '暂时无法检查更新'}
});
ipcMain.on('update:state',event=>{event.returnValue=updateState});
}
