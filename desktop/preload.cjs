const {contextBridge,ipcRenderer}=require('electron');
contextBridge.exposeInMainWorld('blinkDesktop',{
  version:ipcRenderer.sendSync('app:version'),
  request:async()=>true,
  show:card=>ipcRenderer.send('reminder:show',card),
  clear:id=>ipcRenderer.send('reminder:clear',id),
  prompt:p=>ipcRenderer.send('prompt:show',p),
  clearPrompt:id=>ipcRenderer.send('prompt:clear',id),
  status:state=>ipcRenderer.send('desktop:status',state),
  autoStart:()=>ipcRenderer.sendSync('autostart:get'),
  setAutoStart:on=>ipcRenderer.invoke('autostart:set',on),
  health:()=>ipcRenderer.invoke('desktop:health'),
  openSetting:action=>ipcRenderer.invoke('desktop:setting',action),
  updates:true,
  checkUpdate:()=>ipcRenderer.invoke('update:check'),
  updateState:()=>ipcRenderer.sendSync('update:state'),
  onVisibility:handler=>ipcRenderer.on('reminder:visibility',(_,hidden)=>handler(hidden)),
  onResume:handler=>ipcRenderer.on('reminder:resume',()=>handler()),
  onPresence:handler=>ipcRenderer.on('desktop:presence',(_,state)=>handler(state)),
  onCommand:handler=>ipcRenderer.on('desktop:command',(_,name)=>handler(name)),
  onPrompt:handler=>ipcRenderer.on('prompt:answer',(_,id,action)=>handler(id,action)),
  onQuiet:handler=>ipcRenderer.on('desktop:quiet',(_,until,id)=>handler(until,id)),
  onUpdate:handler=>ipcRenderer.on('desktop:update',(_,version)=>handler(version)),
  listen:handler=>ipcRenderer.on('reminder:open',(_,id,confirm)=>handler(id,confirm))
});
// The account API matches the Android plugin so account.js runs unchanged.
contextBridge.exposeInMainWorld('blinkCloudNative',{
  desktop:true,
  info:()=>ipcRenderer.invoke('cloud:info'),
  request:async args=>{
    try{return await ipcRenderer.invoke('cloud:request',args)}
    catch(e){
      // Errors crossing IPC lose their fields; keep the server code in the message.
      const text=String(e.message||'').replace(/^Error invoking remote method 'cloud:request': (Error: )?/,'');
      const err=Error(text);if(/invalid_session/.test(text))err.code='401';throw err;
    }
  },
  exportFile:args=>ipcRenderer.invoke('cloud:export',args),
  importFile:()=>ipcRenderer.invoke('cloud:import'),
  checkUpdate:()=>ipcRenderer.invoke('update:check'),
  update:async()=>{throw Error('桌面版在「检查更新」中自动下载')}
});
