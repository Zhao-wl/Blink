const {app,BrowserWindow,Menu,Tray,nativeImage,screen,ipcMain,powerMonitor,shell}=require('electron');
const path=require('node:path');
let main,popup,tray,active,expiryTimer,quitting=false;
if(!app.requestSingleInstanceLock()){app.quit()}else{
app.on('second-instance',()=>{main?.show();main?.focus()});
app.on('before-quit',()=>{quitting=true});
app.whenReady().then(()=>{
  app.setAppUserModelId('com.zhaowl.blink');
  main=new BrowserWindow({title:'Blink',width:470,height:780,minWidth:360,minHeight:520,
    webPreferences:{preload:path.join(__dirname,'preload.cjs'),sandbox:true,contextIsolation:true,backgroundThrottling:false}});
  main.webContents.setWindowOpenHandler(()=>({action:'deny'}));
  main.webContents.on('will-navigate',event=>event.preventDefault());
  main.loadFile(path.join(__dirname,'../www/index.html'));
  main.on('close',event=>{if(!quitting){event.preventDefault();main.hide()}});
  tray=new Tray(nativeImage.createFromPath(path.join(__dirname,'icon.png')).resize({width:20,height:20}));
  tray.setToolTip('Blink');tray.on('click',()=>main.show());
  tray.setContextMenu(Menu.buildFromTemplate([{label:'打开 Blink',click:()=>main.show()},{label:'退出',click:()=>app.quit()}]));
  app.on('activate',()=>main.show());
  const reconcile=()=>{
    if(active&&active.until<=Date.now())clear();
    main.webContents.send('reminder:resume');
  };
  powerMonitor.on('resume',reconcile);
  powerMonitor.on('unlock-screen',reconcile);
});
function clear(){
  clearTimeout(expiryTimer);
  const previous=popup;
  popup=null;
  active=null;
  if(previous&&!previous.isDestroyed())previous.destroy();
}
ipcMain.on('reminder:clear',(event,id)=>{if(event.sender===main?.webContents&&id===active?.id)clear()});
ipcMain.on('reminder:show',(event,card)=>{
  if(event.sender!==main?.webContents||typeof card?.id!=='string'||![0,1,2].includes(card.k)||
    !Number.isFinite(card.until)||card.until<=Date.now())return;
  clear();active={id:card.id,until:card.until};
  expiryTimer=setTimeout(clear,card.until-Date.now());
  const area=screen.getDisplayNearestPoint(screen.getCursorScreenPoint()).workArea;
  const win=new BrowserWindow({width:330,height:128,x:area.x+area.width-346,y:area.y+area.height-144,
    frame:false,resizable:false,alwaysOnTop:true,skipTaskbar:true,show:false,
    webPreferences:{preload:path.join(__dirname,'popup-preload.cjs'),sandbox:true,contextIsolation:true}});
  popup=win;
  win.once('ready-to-show',()=>{
    if(popup!==win)return;
    if(active.until<=Date.now()){clear();return}
    win.showInactive();
    if(card.sound)shell.beep();
  });
  win.loadFile(path.join(__dirname,'popup.html'),{query:{kind:String(card.k),retry:card.retry?'1':'0',theme:card.theme==='dark'?'dark':'light'}});
});
ipcMain.on('reminder:action',(event,confirm)=>{
  if(!popup||event.sender!==popup.webContents)return;
  if(active.until>Date.now())main.webContents.send('reminder:open',active.id,confirm===true);
  clear();
});
}
