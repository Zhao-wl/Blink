import { Capacitor } from '@capacitor/core';
import { LocalNotifications as notifications } from '@capacitor/local-notifications';

if (Capacitor.isNativePlatform()) {
  const android = Capacitor.getPlatform() === 'android';
  const channels = new Set();
  async function channel(sound, buzz) {
    const id = 'blink-' + Number(sound) + Number(buzz);
    if (android && !channels.has(id)) {
      await notifications.createChannel({id, name:'Blink '+(sound?'有声':'静音')+' · '+(buzz?'震动':'无震动'),
        importance:4, visibility:1, vibration: buzz, sound: sound?'blink.wav':'quiet.wav'});
      channels.add(id);
    }
    return id;
  }
  let queue = Promise.resolve();
  function replace(items,pref) {
    queue = queue.catch(()=>{}).then(async()=>{
      const pending = (await notifications.getPending()).notifications;
      if(pending.length) await notifications.cancel({notifications:pending.map(({id})=>({id}))});
      if(!items.length)return;
      const channelId=await channel(pref.sound,pref.buzz);
      const list=items.filter(item=>item.at>Date.now()).map(item=>({
        id:item.slot, isExactNotification:false, title:'Blink · '+(item.retry?'再次提醒 · ':'')+item.title,
        body:item.body+' 点开打卡。',extra:{token:item.token},channelId,
        sound:pref.sound?'blink.wav':undefined,
        schedule:{at:new Date(item.at),allowWhileIdle:true}
      }));
      if(list.length)await notifications.schedule({notifications:list});
    });
    return queue;
  }
  const handlers=[];
  let waiting;
  notifications.addListener('localNotificationActionPerformed',event=>{
    const token=event.notification.extra?.token;
    if(typeof token!=='string')return;
    if(handlers.length)handlers.forEach(fn=>fn(token));else waiting=token;
  });
  window.blinkMobile={
    ios:!android,replace,
    async show(card,title,body,pref){
      const channelId=await channel(pref.sound,pref.buzz);
      await notifications.schedule({notifications:[{id:1000,title:'Blink · '+title,body,
        extra:{token:card.id},channelId,foreground:true,sound:pref.sound?'blink.wav':undefined}]});
    },
    async clear(token){
      const {notifications:shown}=await notifications.getDeliveredNotifications();
      const matches=shown.filter(item=>item.extra?.token===token);
      if(matches.length)await notifications.removeDeliveredNotifications({notifications:matches})
    },
    request:async()=>(await notifications.requestPermissions()).display==='granted',
    listen(fn){handlers.push(fn);if(waiting){fn(waiting);waiting=null}}
  };
}
