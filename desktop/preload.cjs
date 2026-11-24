const {contextBridge,ipcRenderer}=require('electron');
contextBridge.exposeInMainWorld('blinkDesktop',{
  request:async()=>true,
  show:card=>ipcRenderer.send('reminder:show',card),
  clear:id=>ipcRenderer.send('reminder:clear',id),
  onVisibility:handler=>ipcRenderer.on('reminder:visibility',(_,hidden)=>handler(hidden)),
  onResume:handler=>ipcRenderer.on('reminder:resume',()=>handler()),
  listen:handler=>ipcRenderer.on('reminder:open',(_,id,confirm)=>handler(id,confirm))
});
