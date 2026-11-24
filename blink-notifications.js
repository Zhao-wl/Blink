// The notification carries only a reminder ID. The page validates it before recording anything.
self.addEventListener('message',event=>{
  if(event.data?.type!=='blink-clear'||typeof event.data.id!=='string')return;
  event.waitUntil(self.registration.getNotifications({tag:'blink-current'}).then(items=>{
    for(const item of items)if(item.data?.id===event.data.id)item.close()
  }))
});
self.addEventListener('notificationclick',event=>{
  event.notification.close();
  const id=event.notification.data?.id;
  if(typeof id!=='string')return;
  const url=new URL('index.html',self.registration.scope);
  url.hash=new URLSearchParams({reminder:id,action:event.action==='confirm'?'confirm':'open'});
  event.waitUntil((async()=>{
    const pages=await self.clients.matchAll({type:'window',includeUncontrolled:true});
    const page=pages.find(client=>{const path=new URL(client.url);return path.origin===url.origin&&(path.pathname===url.pathname||path.pathname===new URL(self.registration.scope).pathname)});
    if(page){await page.focus();page.postMessage({type:'blink-reminder',id,confirm:event.action==='confirm'});return}
    await self.clients.openWindow(url.href)
  })())
});
