/* Android account controls are optional. Guest mode never needs a network connection. */
window.BlinkAccounts=function(env){
  const {native,modal,get,getKey,guestKey,save,settle,render,say}=env,engine=window.BlinkSync;
  let info,expanded=false,running=false,authBusy=false,message='',nextTry=0,failures=0;
  const clone=x=>JSON.parse(JSON.stringify(x));
  const profile=id=>'blink-profile-'+id;
  const errorText=e=>({invalid_credentials:'账号或密码不正确',name_unavailable:'此用户名已使用',
    invalid_request:'请检查输入内容',invalid_session:'登录已过期，本机记录仍已保存',
    reminder_selection_changed:'主设备状态已变化，请重新打开登录设备后再试',try_later:'操作频繁，请稍后再试'}[e.message]||e.message);
  const rpc=(route,body={},account=info?.account||'')=>native.request({route,body,account});
  function display(text){message=text;const node=document.getElementById('cloud-status');if(node)node.textContent=text;}
  function button(parent,text,fn){
    const button=document.createElement('button');button.type='button';button.textContent=text;
    button.onclick=async()=>{button.disabled=true;try{await fn()}catch(e){say(errorText(e))}finally{button.disabled=false}};
    parent.append(button);return button;
  }
  function dialog(title){
    if(modal.open)modal.close();modal.replaceChildren();
    const head=document.createElement('h2');head.textContent=title;modal.append(head);modal.showModal();
  }
  function note(text){const p=document.createElement('p');p.textContent=text;modal.append(p);return p;}
  function field(form,title,type='text'){
    const label=document.createElement('label'),input=document.createElement('input');
    label.className='field';label.textContent=title;input.type=type;
    if(type!=='checkbox'){input.required=true;input.maxLength=type==='password'?128:64;}
    if(type==='password')input.autocomplete='current-password';
    label.append(input);form.append(label);return input;
  }
  function store(){if(!save())throw Error('本机数据保存失败');}
  function fresh(){return env.loadMealTimes(env.createState(false));}
  function switchTo(k,s){env.switchTo(k,s);message='';nextTry=0;failures=0;}
  function scope(s){return s.cloud&&info?.account&&getKey()===profile(info.account);}
  function legacyReminderReply(reply){
    return reply&&typeof reply==='object'&&!('revision' in reply)&&!('expiresAt' in reply)&&Array.isArray(reply.devices)&&
      reply.devices.every(d=>d&&typeof d.id==='string'&&typeof d.label==='string'&&
        Number.isSafeInteger(d.expires)&&d.expires>=0&&typeof d.current==='boolean');
  }
  function leaseReminderReply(reply){
    return reply&&[reply.revision,reply.expiresAt,reply.serverTime,reply.localExpires,reply.elapsedExpires]
      .every(n=>Number.isSafeInteger(n)&&n>=0)&&[reply.device,reply.requested].every(d=>d===null||typeof d==='string');
  }
  function clearReminderAuthority(cloud){
    delete cloud.reminder;delete cloud.reminderLegacy;
    delete cloud.reminderCheckedElapsed;delete cloud.reminderBoot;
  }
  let reminderRequest;
  function reminderStatus(action='status',revision){
    if(reminderRequest){
      if(action==='status')return reminderRequest;
      return reminderRequest.catch(()=>{}).then(()=>reminderStatus(action,revision));
    }
    const s=get(),owner=info?.account;
    if(!scope(s))return Promise.resolve(null);
    function accept(reply){
      if(get()!==s||!scope(s))return false;
      const legacy=legacyReminderReply(reply);
      if(!legacy&&!leaseReminderReply(reply))throw Error('主提醒设备状态暂不可用');
      const cloud=s.cloud;
      if(reply.boot===cloud.reminderBoot&&reply.checkedElapsed<cloud.reminderCheckedElapsed)return false;
      clearReminderAuthority(cloud);
      if(legacy)cloud.reminderLegacy=true;
      else cloud.reminder=reply;
      if(Number.isSafeInteger(reply.checkedElapsed)&&Number.isSafeInteger(reply.boot)){
        cloud.reminderCheckedElapsed=reply.checkedElapsed;cloud.reminderBoot=reply.boot;
      }
      store();return true;
    }
    const request=async()=>{
      const reply=await rpc('v2/devices',{action,revision},owner);
      if(!accept(reply))return null;
      if(s.cloud.reminder===reply&&reply.device===info.device&&reply.requested){
        try{
          await env.stopWorkReminders(reply.revision);
          const released=await rpc('v2/devices',{action:'release',revision:reply.revision},owner);
          if(!accept(released))return null;
        }finally{if(get()===s&&scope(s))env.refreshReminders();}
      }else env.refreshReminders();
      return s.cloud.reminder||null;
    };
    reminderRequest=request().catch(error=>{
      if((error.code==='401'||error.message==='invalid_session')&&get()===s&&scope(s)){
        clearReminderAuthority(s.cloud);store();env.refreshReminders();
      }
      throw error;
    }).finally(()=>{reminderRequest=null});
    return reminderRequest;
  }
  function returnGuest(){
    const old=JSON.parse(localStorage.getItem(guestKey)||'null')||fresh();
    old.cursor=Date.now();old.card=null;old.pause=null;
    switchTo(guestKey,old);
  }
  function showRecovery(code){
    dialog('保存恢复码');note('忘记密码时可用。请保存在自己的密码管理器中。');note(code);
    button(modal,'我已保存',()=>modal.close());
  }
  function initializeProfile(s,migrate,device){
    const c=engine.accountState(s,device);
    if(migrate){
      for(const [day,total] of Object.entries(s.totals)){
        const cut=Math.min(86400000,Math.max(0,s.cursor-env.midnight(day)));
        c.local['baseline/'+day]=[Math.max(0,Math.min(86400000,total)),cut];
      }
    }else c.seen=clone(engine.collect(s));
    return s;
  }
  async function login(route,name,password,merge){
    if(running||authBusy)throw Error('同步处理中，请稍后重试');
    authBusy=true;
    try{
      settle();store();
      const source=get(),sourceKey=getKey(),result=await rpc(route,{name,password});
      info={...info,account:result.account,name:result.name};
      const targetKey=profile(result.account);
      let target=JSON.parse(localStorage.getItem(targetKey)||'null');
      if(!target){
        target=merge&&sourceKey===guestKey?clone(source):fresh();
        initializeProfile(target,merge&&sourceKey===guestKey,info.device);
        if(route==='v1/register')target.cloud.seen={};
      }
      if(target.cloud)clearReminderAuthority(target.cloud);
      target.cursor=Date.now();target.card=null;target.pause=null;target.session=null;
      switchTo(targetKey,target);modal.close();
      if(merge&&sourceKey===guestKey)localStorage.setItem('blink-guest-imported',result.account);
      if(result.recovery)showRecovery(result.recovery);
    }finally{authBusy=false}
    await sync(true);
  }
  function loginForm(route){
    dialog(route==='v1/register'?'创建账号':'登录');
    const form=document.createElement('form');modal.append(form);
    const name=field(form,'用户名'),password=field(form,'密码（至少 10 位）','password');password.minLength=10;
    let merge;
    if(getKey()===guestKey&&!localStorage.getItem('blink-guest-imported')){
      const s=get();
      merge=field(form,'首次使用此账号时合并本机 '+s.logs.length+' 条记录、'+Object.keys(s.totals).length+' 天估算和作息','checkbox');
    }
    const submit=document.createElement('button');submit.className='primary';submit.textContent=route==='v1/register'?'创建并登录':'登录';form.append(submit);
    form.onsubmit=async e=>{e.preventDefault();submit.disabled=true;try{await login(route,name.value.trim(),password.value,!!merge?.checked)}catch(err){say(errorText(err))}finally{submit.disabled=false}};
    button(modal,'取消',()=>modal.close());
  }
  async function sync(force=false){
    const s=get();if(!scope(s)||running||authBusy||(!force&&Date.now()<nextTry))return;
    const owner=info.account,c=s.cloud,valid=()=>get()===s&&scope(s)&&owner===info.account;
    running=true;display('同步中…');
    reminderStatus().catch(()=>{});
    try{
      let more;
      do{
        settle();store();
        const batch=engine.recordBatch(s);
        const rows=await rpc('v1/sync',{records:batch,cursor:c.recordCursor},owner);
        if(!valid())return;engine.records(s,rows,batch);store();
        const changes=clone(Object.values(c.pending).slice(0,80));
        const reply=await rpc('v2/sync',{changes,cursor:c.cursor,epoch:c.epoch},owner);
        if(!valid())return;
        if(reply.reset){
          const deleted=Object.keys(c.logs).filter(id=>!s.logs.some(r=>r.id===id));
          c.cursor=0;c.recordCursor=0;c.epoch=reply.epoch;c.logs=Object.fromEntries(deleted.map(id=>[id,true]));
          c.remote={};c.seen={};c.pending={};store();more=true;continue;
        }
        settle();engine.observe(s);engine.objects(s,reply,changes);store();
        more=rows.more||reply.more||batch.length===200||changes.length===80;
      }while(more&&valid());
      c.synced=Date.now();store();failures=0;nextTry=Date.now()+20000;
      display('已同步 · '+new Date(c.synced).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit'}));
      if(!modal.open&&!document.activeElement?.matches('input,select'))render(false);
    }catch(e){
      failures++;nextTry=Date.now()+Math.min(300000,5000*2**Math.min(6,failures));
      display(e.code==='401'?'请重新登录 · 本机记录已保存':'待同步 · 联网后自动重试');
      if(force)say(errorText(e));
    }finally{running=false}
  }
  async function logout(){
    if(running)throw Error('请稍后再试');settle();store();
    // Revoke on server before removing the local session.
    await rpc('v1/logout');info=await native.info();returnGuest();modal.close();
  }
  function passwordAction(mode){
    dialog({reset:'找回账号',password:'修改密码',code:'重新生成恢复码',delete:'注销并删除账号'}[mode]);
    const form=document.createElement('form');modal.append(form);
    let name,recovery,password,newPassword;
    if(mode==='reset'){name=field(form,'用户名');recovery=field(form,'一次性恢复码');}
    else password=field(form,'当前密码','password');
    if(['reset','password'].includes(mode)){newPassword=field(form,'新密码','password');newPassword.minLength=10;}
    if(mode==='delete')note('将删除这个账号的云端数据与本机账号副本，所有会话都会退出。');
    const go=document.createElement('button');go.textContent=mode==='delete'?'确认删除我的账号':'确认';form.append(go);
    form.onsubmit=async e=>{
      e.preventDefault();if(running||authBusy){say('同步处理中，请稍后重试');return}
      authBusy=true;go.disabled=true;
      try{
        const result=await rpc('v2/'+(mode==='code'?'recovery-code':mode),{name:name?.value,recovery:recovery?.value,password:password?.value,newPassword:newPassword?.value});
        modal.close();
        if(mode==='delete'){const k=getKey();info=await native.info();returnGuest();localStorage.removeItem(k);}
        if(result.recovery)showRecovery(result.recovery);
      }catch(err){say(errorText(err))}finally{authBusy=false;go.disabled=false}
    };
    button(modal,'取消',()=>modal.close());
  }
  async function devices(){
    const result=await rpc('v2/devices');
    const state=await reminderStatus().catch(()=>null);
    dialog('登录设备');
    if(get().cloud?.reminderLegacy)note('当前服务使用本机工作提醒，升级服务后可选择主提醒设备。');
    else if(!state)note('主提醒设备状态暂不可用。');
    else if(!state.device)note('请选择一台设备接收工作提醒。');
    else if(state.requested)note('等待旧主设备结束提醒。旧机无法联网时，当前授权到期后完成交接。');
    for(const d of result.devices){
      const role=state?.device===d.id?' · 主提醒设备':state?.requested===d.id?' · 等待接管':'';
      note(d.label+(d.current?' · 本机':'')+role);
      if(state&&d.current&&d.id&&!role&&!state.requested)button(modal,'将本机设为主提醒设备',async()=>{
        await reminderStatus('select',state.revision);
        await devices();
      });
      if(!d.current&&d.id)button(modal,'退出此设备',async()=>{await rpc('v2/revoke',{device:d.id});await devices()});
    }
    button(modal,'关闭',()=>modal.close());
  }
  function conflicts(){
    const c=get().cloud;
    dialog('同时修改的设置');
    for(const [key,v] of Object.entries(c.conflicts)){
      note(key+' · 本机 '+JSON.stringify(v.mine)+' / 云端 '+JSON.stringify(v.theirs));
      for(const [title,mine] of [['采用本机',true],['采用云端',false]])button(modal,title,async()=>{
        if(mine){engine.apply(get(),key,v.mine);c.pending[key]={key,value:v.mine,base:c.remote[key].rev,mutation:crypto.randomUUID()};}
        delete c.conflicts[key];store();modal.close();await sync(true);
      });
    }
    button(modal,'稍后处理',()=>modal.close());
  }
  async function backup(){settle();store();const state=clone(get());delete state.cloud;
    await native.exportFile({content:JSON.stringify({format:'blink-backup-v1',state})});say('已导出');
  }
  async function restore(){
    const backup=JSON.parse((await native.importFile()).content);
    const source=backup.state,s=fresh();
    const requireValue=ok=>{if(!ok)throw Error('备份格式不正确')};
    const integer=(v,min,max)=>Number.isSafeInteger(v)&&v>=min&&v<=max;
    const time=v=>typeof v==='string'&&/^(?:[01][0-9]|2[0-3]):[0-5][0-9]$/.test(v);
    const day=v=>typeof v==='string'&&/^\d{4}-\d{2}-\d{2}$/.test(v)&&Number.isFinite(env.midnight(v))&&env.dayKey(env.midnight(v))===v;
    const uuid=v=>typeof v==='string'&&/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(v);
    requireValue(backup.format==='blink-backup-v1'&&source&&Array.isArray(source.logs)&&Array.isArray(source.blocks));
    for(const k of ['start','end','lunchStart','lunchEnd','dinnerStart','dinnerEnd']){
      requireValue(time(source[k]));s[k]=source[k];
    }
    requireValue(s.start<=s.lunchStart&&s.lunchStart<=s.lunchEnd&&s.lunchEnd<=s.end&&s.start<s.end&&s.lunchEnd<=s.dinnerStart&&s.dinnerStart<=s.dinnerEnd);
    requireValue(Array.isArray(source.freq)&&source.freq.length===2&&source.freq.every(v=>integer(v,0,1440)));
    requireValue(Array.isArray(source.extraFreq)&&source.extraFreq.length===2&&source.extraFreq.every(v=>Array.isArray(v)&&integer(v[0],0,1440)));
    requireValue(integer(source.breakMinutes,1,1440));
    s.freq=source.freq;s.extraFreq=source.extraFreq.map(v=>[v[0],0]);s.breakMinutes=source.breakMinutes;
    s.logs=source.logs.map(r=>{requireValue(r&&uuid(r.id)&&[0,1,2].includes(r.k)&&integer(r.at,0,253402300799999));return {id:r.id,k:r.k,at:r.at}});
    s.blocks=source.blocks.map(b=>{
      requireValue(b&&uuid(b.id)&&day(b.day)&&time(b.at)&&typeof b.title==='string'&&b.title.length>0&&b.title.length<=100&&integer(b.minutes,1,1440)&&typeof b.screen==='boolean');
      return {id:b.id,day:b.day,at:b.at,title:b.title,minutes:b.minutes,screen:b.screen,done:b.done===true};
    });
    for(const field of ['totals','corrections']){
      const values=source[field];requireValue(values&&typeof values==='object'&&!Array.isArray(values));
      for(const [date,value] of Object.entries(values)){
        requireValue(day(date)&&integer(value,field==='totals'?0:-86400000,86400000));s[field][date]=value;
      }
    }
    if(['system','light','dark'].includes(source.theme))s.theme=source.theme;
    if(source.delivery)s.delivery=Object.fromEntries(['enabled','sound','buzz','autoFace'].filter(k=>typeof source.delivery[k]==='boolean').map(k=>[k,source.delivery[k]]));
    dialog('导入本机备份');note('恢复 '+s.logs.length+' 条记录和 '+Object.keys(s.totals).length+' 天估算；当前游客数据会在本机保留一份迁移快照。');
    button(modal,'恢复至游客空间',()=>{
      if(getKey()!==guestKey)throw Error('请先退出账号');store();
      localStorage.setItem('blink-before-import',JSON.stringify(get()));
      delete s.cloud;s.cursor=Date.now();s.card=null;s.pause=null;s.session=null;
      switchTo(guestKey,s);localStorage.removeItem('blink-guest-imported');modal.close();say('已恢复');
    });button(modal,'取消',()=>modal.close());
  }
  async function update(){
    display('正在检查版本…');
    let r;
    try{r=await native.update({install:false})}
    catch(e){display(errorText(e));return}
    if(r.installed>=r.version){display('当前已是最新内测版 0.2.'+r.installed);return}
    display('当前内测版 0.2.'+r.installed);
    dialog('可更新到 0.2.'+r.version);note('安装完成后保留账号和离线记录。');
    const progress=note('');
    progress.setAttribute('role','status');
    button(modal,'下载安装',async()=>{
      progress.textContent='正在下载并校验安装包…';
      try{await native.update({install:true,version:r.version});modal.close()}
      catch(e){progress.textContent=errorText(e)}
    });button(modal,'稍后',()=>modal.close());
  }
  function settings(parent){
    if(!info?.enabled)return;
    const panel=document.createElement('details');panel.open=expanded;panel.ontoggle=()=>{if(panel.isConnected)expanded=panel.open};
    const summary=document.createElement('summary');summary.textContent='账号与同步';panel.append(summary);parent.append(panel);
    const state=document.createElement('p');state.id='cloud-status';state.textContent=message||(info.account?'已登录 · '+info.name:'游客 · 数据仅在本机');panel.append(state);
    if(info.account){
      const reminder=get().cloud?.reminder;
      const role=document.createElement('p');
      role.textContent=get().cloud?.reminderLegacy?'当前服务使用本机工作提醒。':
        reminder?.requested===info.device?'本机正在等待接管工作提醒。':
        reminder?.device===info.device&&!reminder.requested?'本机是主提醒设备。':
        reminder?.device?'工作提醒由主设备发送。':'请在「登录设备」中选择主提醒设备。';
      panel.append(role);
      button(panel,'立即同步',()=>sync(true));button(panel,'重新登录',()=>loginForm('v1/login'));
      if(get().cloud&&Object.keys(get().cloud.conflicts).length)button(panel,'处理设置冲突',conflicts);
      button(panel,'登录设备',devices);button(panel,'修改密码',()=>passwordAction('password'));
      button(panel,'恢复码',()=>passwordAction('code'));button(panel,'退出账号',async()=>{dialog('退出本机账号');note('待同步记录保存在此账号的本机空间。');button(modal,'退出',logout);button(modal,'取消',()=>modal.close())});
      button(panel,'注销账号',()=>passwordAction('delete'));
    }else{
      button(panel,'登录',()=>loginForm('v1/login'));button(panel,'注册',()=>loginForm('v1/register'));button(panel,'找回账号',()=>passwordAction('reset'));
      button(panel,'导入备份',restore);
    }
    button(panel,'导出备份',backup);button(panel,'检查内测更新',update);
  }
  native.info().then(result=>{
    info=result;if(!info.enabled)return;
    if(info.account&&getKey()!==profile(info.account)){
      const s=JSON.parse(localStorage.getItem(profile(info.account))||'null')||initializeProfile(fresh(),false,info.device);
      s.cursor=Date.now();switchTo(profile(info.account),s);
    }else if(!info.account&&getKey()!==guestKey)returnGuest();
    render(false);watchVisibility();
    window.addEventListener('online',()=>{if(!document.hidden)sync(true)});
    document.addEventListener('visibilitychange',watchVisibility);
  }).catch(()=>display('无法读取本机凭证'));
  let syncTimer,pollTimer;
  function watchVisibility(){
    clearTimeout(pollTimer);clearTimeout(syncTimer);
    if(document.hidden)return;
    if(scope(get()))sync();
    pollTimer=setTimeout(watchVisibility,30000);
  }
  function changed(){
    if(!scope(get())||document.hidden)return;
    clearTimeout(syncTimer);syncTimer=setTimeout(()=>{if(!failures)nextTry=0;sync()},750);
  }
  return {settings,sync,changed,refreshReminderAuthority:()=>reminderStatus()};
};
