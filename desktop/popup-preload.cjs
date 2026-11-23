const {contextBridge,ipcRenderer}=require('electron');
contextBridge.exposeInMainWorld('respond',yes=>ipcRenderer.send('reminder:action',yes===true));
