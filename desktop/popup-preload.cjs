const {contextBridge,ipcRenderer}=require('electron');
contextBridge.exposeInMainWorld('respond',answer=>ipcRenderer.send('reminder:action',typeof answer==='string'?answer:answer===true));
