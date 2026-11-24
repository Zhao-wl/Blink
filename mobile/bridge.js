import { Capacitor, registerPlugin } from '@capacitor/core';
import { LocalNotifications as notifications } from '@capacitor/local-notifications';

if (Capacitor.isNativePlatform()) {
  const android = Capacitor.getPlatform() === 'android';
  if(android)window.blinkCloudNative=registerPlugin('BlinkCloud');
  const timer=registerPlugin('BlinkTimer');
  const live=registerPlugin('BlinkLive');
  const channels = new Set();
  async function channel(sound, buzz) {
    const id = 'blink-' + Number(!!sound) + Number(!!buzz);
    if (android && !channels.has(id)) {
      await notifications.createChannel({id, name:'Blink '+(sound?'有声':'静音')+' · '+(buzz?'震动':'无震动'),
        importance:4, visibility:1, vibration: buzz, sound: sound?'blink.wav':'quiet.wav'});
      channels.add(id);
    }
    return id;
  }
  let queue = Promise.resolve();
  function replace(items,pref,display,authorization) {
    queue = queue.catch(()=>{}).then(async()=>{
      if(android){
        const channelId=items.length?await channel(pref.sound,pref.buzz):'';
        await timer.configure({...authorization,items:items.map(item=>({...item,owner:authorization.owner})),channel:channelId,lock:pref.lock!==false,wake:pref.wake===true});
        return;
      }
      const liveUpdate=live.setTimer({at:pref.lock===false?0:display?.at||0,title:display?.title||''}).catch(()=>{});
      const pending = (await notifications.getPending()).notifications;
      if(pending.length) await notifications.cancel({notifications:pending.map(({id})=>({id}))});
      if(!items.length)return liveUpdate;
      const channelId=await channel(pref.sound,pref.buzz);
      const list=items.filter(item=>item.at>Date.now()).map(item=>({
        id:item.slot, isExactNotification:false, foreground:false, title:'Blink · '+(item.retry?'再次提醒 · ':'')+item.title,
        body:item.body+' 点开打卡。',extra:{token:item.token},channelId,
        sound:pref.sound?'blink.wav':undefined,
        schedule:{at:new Date(item.at),allowWhileIdle:true}
      }));
      if(list.length)await notifications.schedule({notifications:list});
      await liveUpdate;
    });
    return queue;
  }
  const handlers=[];
  let waiting;
  function receive(token){
    if(typeof token!=='string')return;
    if(handlers.length)handlers.forEach(fn=>fn(token));else waiting=token;
  }
  if(android)timer.addListener('tap',event=>receive(event.token));
  notifications.addListener('localNotificationActionPerformed',event=>receive(event.notification.extra?.token));
  window.blinkMobile={
    ios:!android,replace,confirmations:owner=>timer.pendingConfirmations({owner}),acknowledge:(owner,ids)=>timer.acknowledge({owner,ids}),
    validateTap:(owner,token)=>timer.validateTap({owner,token}),
    stopShared(owner,revision){
      queue=queue.catch(()=>{}).then(()=>timer.stopShared({owner,revision}));
      return queue;
    },
    async exactAllowed(){return (await notifications.checkExactNotificationSetting()).exact_alarm==='granted'},
    async requestExact(){return (await notifications.changeExactNotificationSetting()).exact_alarm==='granted'},
    async consumeTap(){if(android){const {token}=await timer.consumeTap();if(token)receive(token)}},
    async show(card,title,body,pref){
      const channelId=await channel(pref.sound,pref.buzz);
      if(android){
        await queue.catch(()=>{});
        return timer.showReminder({channel:channelId,row:{owner:card.owner,shared:card.shared,token:card.id,round:card.round,k:card.k,at:Date.now(),expires:card.until,title,body,rest:card.rest||0}});
      }
      await notifications.schedule({notifications:[{id:1000,isExactNotification:false,title:'Blink · '+title,body,
        extra:{token:card.id},channelId,foreground:true,sound:pref.sound?'blink.wav':undefined}]});
    },
    async clear(token){
      if(android)await timer.clearReminder({token});
      const {notifications:shown}=await notifications.getDeliveredNotifications();
      const matches=shown.filter(item=>item.extra?.token===token);
      if(matches.length)await notifications.removeDeliveredNotifications({notifications:matches})
    },
    notificationAllowed:async()=>(await notifications.checkPermissions()).display==='granted',
    request:async()=>(await notifications.requestPermissions()).display==='granted',
    listen(fn){handlers.push(fn);if(waiting){fn(waiting);waiting=null}}
  };
}
