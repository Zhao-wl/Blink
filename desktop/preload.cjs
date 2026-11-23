const {contextBridge,ipcRenderer}=require('electron');
contextBridge.exposeInMainWorld('blinkDesktop',{
  request:async()=>true,
  show:card=>ipcRenderer.send('reminder:show',card),
  clear:id=>ipcRenderer.send('reminder:clear',id),
  listen:handler=>ipcRenderer.on('reminder:open',(_,id,confirm)=>handler(id,confirm))
});
