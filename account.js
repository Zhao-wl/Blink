/* Accounts are optional and only replicate data. Guest mode never needs a network connection. */
window.BlinkAccounts=function(env){
  const {native,modal,get,getKey,guestKey,save,settle,render,say}=env,engine=window.BlinkSync;
  let info,running=false,authBusy=false,message='',nextTry=0,failures=0,devicesCache=null;
  const clone=x=>JSON.parse(JSON.stringify(x));
  const profile=id=>'blink-profile-'+id;
  const errorText=e=>({invalid_credentials:'账号或密码不正确',name_unavailable:'此用户名已使用',
    invalid_request:'请检查输入内容',invalid_session:'登录已过期，本机记录仍已保存',pair_expired:'配对码已过期，请重新获取',
    pair_unknown:'配对码不正确或已过期',try_later:'操作频繁，请稍后再试',not_found:'服务暂未支持此功能，请稍后再试'}[e.message]||e.message);
  const rpc=(route,body={},account=info?.account||'')=>native.request({route,body,account});
  const ago=ms=>{const m=Math.round((Date.now()-ms)/60000);return m<1?'刚刚':m<60?m+' 分钟前':m<1440?Math.round(m/60)+' 小时前':Math.round(m/1440)+' 天前'};
  function display(text){message=text;document.querySelectorAll('.cloud-status').forEach(n=>n.textContent=text);}
  function button(parent,text,fn,primary=false){
    const b=document.createElement('button');b.type='button';b.textContent=text;if(primary)b.className='primary';
    b.onclick=async()=>{b.disabled=true;try{await fn()}catch(e){say(errorText(e))}finally{b.disabled=false}};
    parent.append(b);return b;
  }
  function dialog(title){
    if(modal.open)modal.close();modal.replaceChildren();
    const head=document.createElement('h2');head.textContent=title;modal.append(head);modal.showModal();
  }
  function note(text,cls=''){const p=document.createElement('p');p.textContent=text;if(cls)p.className=cls;modal.append(p);return p;}
  function field(form,title,type='text'){
    const label=document.createElement('label'),input=document.createElement('input');
    label.className='field';label.textContent=title;input.type=type;
    if(type!=='checkbox'){input.required=true;input.maxLength=type==='password'?128:64;}
    if(type==='password')input.autocomplete='current-password';
    label.append(input);form.append(label);return input;
  }
  function store(){if(!save())throw Error('本机数据保存失败');}
  function fresh(){return env.loadMealTimes(env.createState(false));}
  function switchTo(k,s){env.switchTo(k,s);message='';nextTry=0;failures=0;devicesCache=null;}
  function scope(s){return s.cloud&&info?.account&&getKey()===profile(info.account);}
  function returnGuest(keepCopy){
    const source=get();
    let next=JSON.parse(localStorage.getItem(guestKey)||'null')||fresh();
    if(keepCopy){next=clone(source);delete next.cloud;}
    next.cursor=Date.now();next.card=null;next.pause=null;next.away=null;
    switchTo(guestKey,next);
  }
  function showRecovery(code){
    dialog('保存恢复码');note('忘记密码时，用用户名和这个恢复码找回账号。请保存在密码管理器中。');
    const p=note(code);p.style.cssText='font:600 16px/1.6 ui-monospace,monospace;color:var(--ink);word-break:break-all;user-select:all';
    button(modal,'我已保存',()=>modal.close(),true);
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
  // Enter an account space after any successful sign-in (password, pairing).
  async function enter(result,merge,registered){
    settle();store();
    const source=get(),sourceKey=getKey();
    info={...info,account:result.account,name:result.name};
    const targetKey=profile(result.account);
    let target=JSON.parse(localStorage.getItem(targetKey)||'null');
    if(!target){
      target=merge&&sourceKey===guestKey?clone(source):fresh();
      initializeProfile(target,merge&&sourceKey===guestKey,info.device);
      if(registered)target.cloud.seen={};
    }
    target.cursor=Date.now();target.card=null;target.pause=null;target.session=null;target.away=null;target.onboarded=true;
    switchTo(targetKey,target);modal.close();
    if(merge&&sourceKey===guestKey)localStorage.setItem('blink-guest-imported',result.account);
    if(result.recovery)showRecovery(result.recovery);
    else say('已登录 '+result.name);
    await sync(true);
  }
  async function login(route,name,password,merge){
    if(running||authBusy)throw Error('同步处理中，请稍后重试');
    authBusy=true;
    try{const result=await rpc(route,{name,password});await enter(result,merge,route==='v1/register');}
    finally{authBusy=false}
  }
  function mergeField(form){
    if(getKey()!==guestKey||localStorage.getItem('blink-guest-imported'))return null;
    const s=get();if(!s.logs.length&&Object.keys(s.totals).length<2)return null;
    const input=field(form,'合并本机的 '+s.logs.length+' 条记录和 '+Object.keys(s.totals).length+' 天时长','checkbox');input.checked=true;return input;
  }
  function loginForm(route){
    dialog(route==='v1/register'?'创建账号':'用密码登录');
    const form=document.createElement('form');modal.append(form);
    const name=field(form,'用户名'),password=field(form,'密码（至少 8 位）','password');password.minLength=8;
    if(route==='v1/register')password.autocomplete='new-password';
    const merge=mergeField(form);
    const submit=document.createElement('button');submit.className='primary';submit.textContent=route==='v1/register'?'创建并登录':'登录';form.append(submit);
    form.onsubmit=async e=>{e.preventDefault();submit.disabled=true;try{await login(route,name.value.trim(),password.value,!!merge?.checked)}catch(err){say(errorText(err))}finally{submit.disabled=false}};
    button(modal,'取消',()=>modal.close());
  }
  // New device: show a code; a signed-in device enters it to approve. No password typed here.
  async function pairNew(){
    const form=document.createElement('form');
    dialog('用配对码登录');
    note('在已登录 Blink 的设备上打开「设置 → 账号 → 添加设备」，输入下面的配对码。');
    const merge=mergeField(form);if(merge)modal.append(form);
    const code=note('获取中…');code.style.cssText='font:650 40px/1.3 ui-monospace,monospace;letter-spacing:.18em;color:var(--ink);text-align:center;margin:18px 0';
    const state=note('配对码 2 分钟内有效。','muted');
    let stopped=false;button(modal,'取消',()=>{stopped=true;modal.close()});
    modal.addEventListener('close',()=>{stopped=true},{once:true});
    let ticket;
    try{ticket=await rpc('v2/pair/new',{});}catch(e){code.textContent='—';state.textContent=errorText(e);return}
    code.textContent=ticket.code.slice(0,3)+' '+ticket.code.slice(3);
    const until=Date.now()+(ticket.expires||120)*1000;
    while(!stopped&&Date.now()<until){
      await new Promise(r=>setTimeout(r,2000));
      if(stopped)return;
      try{
        const reply=await rpc('v2/pair/poll',{secret:ticket.secret});
        if(reply.account){await enter(reply,!!merge?.checked,false);return}
      }catch(e){if(e.message==='pair_expired'||e.message==='pair_unknown')break}
    }
    if(!stopped){code.textContent='—';state.textContent='配对码已过期，请重新获取。'}
  }
  function pairApprove(){
    dialog('添加设备');
    note('在新设备上选择「用配对码登录」，然后在这里输入它显示的 6 位数字。');
    const form=document.createElement('form');modal.append(form);
    const input=field(form,'配对码');input.inputMode='numeric';input.pattern='[0-9 ]{6,7}';input.maxLength=7;input.autocomplete='one-time-code';
    const go=document.createElement('button');go.className='primary';go.textContent='确认添加';form.append(go);
    form.onsubmit=async e=>{e.preventDefault();go.disabled=true;
      try{const r=await rpc('v2/pair/approve',{code:input.value.replace(/\s/g,'')});modal.close();say('已添加 '+(r.label||'新设备'));devicesCache=null}
      catch(err){say(errorText(err))}finally{go.disabled=false}};
    button(modal,'取消',()=>modal.close());
  }
  async function sync(force=false){
    const s=get();if(!scope(s)||running||authBusy||(!force&&Date.now()<nextTry))return;
    const owner=info.account,c=s.cloud,valid=()=>get()===s&&scope(s)&&owner===info.account;
    running=true;display('同步中…');
    try{
      let more;
      do{
        settle();store();
        const batch=engine.recordBatch(s);
        const rows=await rpc('v1/sync',{records:batch,cursor:c.recordCursor},owner);
        if(!valid())return;engine.records(s,rows,batch);store();
        const changes=clone(Object.values(c.pending).filter(x=>!x.key.startsWith('day/')||c.features?.includes('day')).slice(0,80));
        const reply=await rpc('v2/sync',{changes,cursor:c.cursor,epoch:c.epoch},owner);
        if(!valid())return;
        if(reply.reset){
          const deleted=Object.keys(c.logs).filter(id=>!s.logs.some(r=>r.id===id));
          c.cursor=0;c.recordCursor=0;c.epoch=reply.epoch;c.logs=Object.fromEntries(deleted.map(id=>[id,true]));
          c.remote={};c.seen={};c.pending={};store();more=true;continue;
        }
        if(Array.isArray(reply.features))c.features=reply.features;
        settle();engine.observe(s);engine.objects(s,reply,changes);store();
        more=rows.more||reply.more||batch.length===200||changes.length===80;
      }while(more&&valid());
      c.synced=Date.now();store();failures=0;nextTry=Date.now()+20000;
      display('已同步 · '+ago(c.synced));
      if(Object.keys(c.conflicts||{}).length)askConflict();
      if(!modal.open&&!document.activeElement?.matches('input,select'))render(false);
    }catch(e){
      failures++;nextTry=Date.now()+Math.min(300000,5000*2**Math.min(6,failures));
      display(e.code==='401'?'请重新登录 · 本机记录已保存':'上次同步 '+(c.synced?ago(c.synced):'—')+' · 联网后自动重试');
      if(force)say(errorText(e));
    }finally{running=false}
  }
  async function logout(keepCopy){
    if(running)throw Error('请稍后再试');settle();store();
    // Revoke on server before removing the local session.
    await rpc('v1/logout').catch(e=>{if(e.code!=='401')throw e});
    info=await native.info();returnGuest(keepCopy);modal.close();say(keepCopy?'已退出，记录保留在本机':'已退出');
  }
  function logoutDialog(){
    dialog('退出登录');note('其他设备不受影响。这台设备上的数据怎么处理？');
    button(modal,'保留在本机（转为游客数据）',()=>logout(true),true);
    button(modal,'从本机清除',()=>logout(false));
    button(modal,'取消',()=>modal.close());
  }
  function passwordAction(mode){
    dialog({reset:'找回账号',password:'修改密码',code:'重新生成恢复码',delete:'注销账号'}[mode]);
    const form=document.createElement('form');modal.append(form);
    let name,recovery,password,newPassword;
    if(mode==='reset'){name=field(form,'用户名');recovery=field(form,'恢复码');}
    else password=field(form,'当前密码','password');
    if(['reset','password'].includes(mode)){newPassword=field(form,'新密码（至少 8 位）','password');newPassword.minLength=8;newPassword.autocomplete='new-password';}
    if(mode==='delete')note('将删除这个账号的云端数据与本机账号副本，所有设备都会退出。此操作无法撤销。');
    const go=document.createElement('button');go.className='primary';go.textContent=mode==='delete'?'确认注销':'确认';form.append(go);
    form.onsubmit=async e=>{
      e.preventDefault();if(running||authBusy){say('同步处理中，请稍后重试');return}
      authBusy=true;go.disabled=true;
      try{
        const result=await rpc('v2/'+(mode==='code'?'recovery-code':mode),{name:name?.value,recovery:recovery?.value,password:password?.value,newPassword:newPassword?.value});
        modal.close();
        if(mode==='delete'){const k=getKey();info=await native.info();returnGuest(false);localStorage.removeItem(k);}
        if(result.recovery)showRecovery(result.recovery);
        else if(mode==='password')say('密码已修改');
      }catch(err){say(errorText(err))}finally{authBusy=false;go.disabled=false}
    };
    button(modal,'取消',()=>modal.close());
  }
  async function loadDevices(){
    const result=await rpc('v2/devices');devicesCache={at:Date.now(),list:result.devices};return result.devices;
  }
  async function devicesDialog(){
    const list=await loadDevices();
    dialog('我的设备');
    note('正在使用的设备负责工作提醒：电脑在用时手机不响，电脑锁屏或离开后由手机提醒。','muted');
    for(const d of list){
      const row=document.createElement('div');row.className='health-row';
      const text=document.createElement('span');text.textContent=(d.label||'未命名设备')+(d.current?' · 本机':'');
      const small=document.createElement('small');small.className='muted';small.style.display='block';
      small.textContent=(d.platform==='desktop'?'电脑':d.platform==='android'?'手机':'设备')+(d.active?' · '+ago(d.active)+'活跃':'')+(d.exclusive?' · 只在此设备提醒':'');
      text.append(small);row.append(text);
      if(d.id){
        const rename=document.createElement('button');rename.textContent='改名';rename.onclick=()=>{
          dialog('设备名称');const form=document.createElement('form');modal.append(form);
          const input=field(form,'名称');input.maxLength=40;input.value=d.label||'';
          const ok=document.createElement('button');ok.className='primary';ok.textContent='保存';form.append(ok);
          form.onsubmit=async e=>{e.preventDefault();try{await rpc('v2/rename',{device:d.id,label:input.value.trim().slice(0,40)});await devicesDialog()}catch(err){say(errorText(err))}};
          button(modal,'取消',devicesDialog)};
        row.append(rename);
      }
      if(!d.current&&d.id){const remove=document.createElement('button');remove.textContent='移除';remove.onclick=()=>{
        dialog('移除「'+(d.label||'设备')+'」？');note('该设备会退出登录，本机数据不受影响。');
        button(modal,'移除',async()=>{await rpc('v2/revoke',{device:d.id});await devicesDialog()},true);button(modal,'取消',devicesDialog)};row.append(remove)}
      modal.append(row);
    }
    const s=get();
    const exclusive=document.createElement('label');exclusive.className='field';exclusive.textContent='只在这台设备提醒';
    const box=document.createElement('input');box.type='checkbox';box.checked=!!s.cloud?.exclusive;
    box.onchange=async()=>{s.cloud.exclusive=box.checked;store();try{await presence()}catch{}};exclusive.append(box);modal.append(exclusive);
    button(modal,'添加设备',pairApprove,true);
    button(modal,'完成',()=>modal.close());
  }
  // Words, not keys: only the schedule is ever asked about; everything else follows the last edit.
  function askConflict(){
    const c=get().cloud,entry=c.conflicts?.hours;if(!entry||modal.open)return;
    const fmt=v=>Array.isArray(v)?v[0]+'–'+v[3]+' 上班，午休 '+v[1]+'–'+v[2]:'—';
    dialog('作息在两台设备上都改过');
    note('这台设备：'+fmt(entry.mine));note('另一台设备：'+fmt(entry.theirs));
    for(const [title,mine] of [['用这台设备的',true],['用另一台的',false]])button(modal,title,async()=>{
      if(mine){engine.apply(get(),'hours',entry.mine);c.pending.hours={key:'hours',value:entry.mine,base:c.remote.hours?.rev||0,mutation:crypto.randomUUID()};}
      delete c.conflicts.hours;store();modal.close();render(false);await sync(true);
    },mine);
  }
  function exportDialog(){
    dialog('导出数据');
    button(modal,'完整备份（JSON，可再导入）',backup,true);
    button(modal,'打卡记录表格（CSV）',exportCsv);
    button(modal,'取消',()=>modal.close());
  }
  async function exportCsv(){
    const s=get(),kinds=['远眺','离席','喝水'];
    const rows=[['日期','时间','活动','来源']].concat(s.logs.slice().sort((a,b)=>a.at-b.at).map(r=>{
      const d=new Date(r.at+8*3600000).toISOString();return [d.slice(0,10),d.slice(11,16),kinds[r.k],r.auto||r.id.startsWith('a11e0000-')?'推测':'打卡'];
    }));
    const totals=Object.entries(s.totals).sort().map(([day])=>[day,'',  '用眼分钟',Math.round(((s.totals[day]||0)+(s.corrections[day]||0))/60000)]);
    const csv='﻿'+rows.concat([[]],totals).map(r=>r.join(',')).join('\r\n');
    await native.exportFile({content:csv,name:'Blink-records.csv',type:'text/csv'});say('已导出');
  }
  async function backup(){settle();store();const state=clone(get());delete state.cloud;
    await native.exportFile({content:JSON.stringify({format:'blink-backup-v1',state}),name:'Blink-backup.json',type:'application/json'});say('已导出');
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
    if(integer(source.eyeSeconds,5,600))s.eyeSeconds=source.eyeSeconds;
    s.logs=source.logs.map(r=>{requireValue(r&&uuid(r.id)&&[0,1,2].includes(r.k)&&integer(r.at,0,253402300799999));return {id:r.id,k:r.k,at:r.at,...(r.auto===true?{auto:true}:{})}});
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
    if(source.workdays==='mon-fri'||source.workdays==='single'||source.workdays==='alt'||Array.isArray(source.workdays)&&source.workdays.every(d=>integer(d,0,6)))s.workdays=source.workdays;
    if(day(source.altSaturday))s.altSaturday=source.altSaturday;
    if(typeof source.holidays==='boolean')s.holidays=source.holidays;
    if(['system','light','dark'].includes(source.theme))s.theme=source.theme;
    if(source.delivery)s.delivery=Object.fromEntries(['enabled','sound','buzz','lock','wake','persist'].filter(k=>typeof source.delivery[k]==='boolean').map(k=>[k,source.delivery[k]]));
    s.onboarded=true;
    dialog('导入本机备份');note('恢复 '+s.logs.length+' 条记录和 '+Object.keys(s.totals).length+' 天时长；当前游客数据会在本机保留一份快照。');
    button(modal,'恢复至游客空间',()=>{
      if(getKey()!==guestKey)throw Error('请先退出账号');store();
      localStorage.setItem('blink-before-import',JSON.stringify(get()));
      delete s.cloud;s.cursor=Date.now();s.card=null;s.pause=null;s.session=null;
      switchTo(guestKey,s);localStorage.removeItem('blink-guest-imported');modal.close();say('已恢复');
    },true);button(modal,'取消',()=>modal.close());
  }
  async function update(status){
    status.textContent='正在检查版本…';
    let r;
    try{r=await native.update({install:false})}
    catch(e){status.textContent=errorText(e);return}
    if(r.installed>=r.version){status.textContent='已是最新版本';return}
    status.textContent='有新版本 0.2.'+r.version;
    dialog('可更新到 0.2.'+r.version);
    if(r.notes)note(r.notes);
    note('安装后保留账号和离线记录。','muted');
    const progress=note('');progress.setAttribute('role','status');
    button(modal,'下载安装',async()=>{
      progress.textContent='正在下载并校验安装包…';
      try{await native.update({install:true,version:r.version});modal.close()}
      catch(e){progress.textContent=errorText(e)}
    },true);button(modal,'稍后',()=>modal.close());
  }
  function accountPage(){
    const s=get();
    dialog(info.name);
    const st=note(message||(s.cloud?.synced?'已同步 · '+ago(s.cloud.synced):'尚未同步'),'muted cloud-status');st.setAttribute('role','status');
    button(modal,'立即同步',()=>sync(true));
    button(modal,'我的设备',devicesDialog);
    button(modal,'导出数据',exportDialog);
    button(modal,'账号安全',()=>{
      dialog('账号安全');
      button(modal,'修改密码',()=>passwordAction('password'));
      button(modal,'重新生成恢复码',()=>passwordAction('code'));
      button(modal,'注销账号',()=>passwordAction('delete'));
      button(modal,'返回',accountPage);
    });
    button(modal,'退出登录',logoutDialog);
    button(modal,'关闭',()=>modal.close());
  }
  function guestPage(){
    dialog('在其他设备同步记录');
    note('登录后，打卡、作息和用眼时长会在你的手机和电脑之间同步。不登录也能完整使用。','muted');
    button(modal,'用配对码登录（推荐）',pairNew,true);
    button(modal,'用密码登录',()=>loginForm('v1/login'));
    button(modal,'创建账号',()=>loginForm('v1/register'));
    button(modal,'忘记密码',()=>passwordAction('reset'));
    button(modal,'从备份导入',restore);
    button(modal,'导出备份',backup);
    button(modal,'取消',()=>modal.close());
  }
  function card(parent){
    if(!info?.enabled)return;
    const s=get(),b=document.createElement('button');b.className='account-card';
    const text=document.createElement('span');const title=document.createElement('strong'),sub=document.createElement('small');
    sub.className='muted cloud-status';
    if(info.account){
      title.textContent=info.name;
      const count=devicesCache?.list?.length;
      sub.textContent=message&&!message.startsWith('已同步')?message:(count?count+' 台设备 · ':'')+(s.cloud?.synced?'已同步 · '+ago(s.cloud.synced):'尚未同步');
      b.onclick=accountPage;
      if(!devicesCache||Date.now()-devicesCache.at>600000)loadDevices().then(()=>{if(b.isConnected){const n=devicesCache.list.length;sub.textContent=n+' 台设备 · '+(get().cloud?.synced?'已同步 · '+ago(get().cloud.synced):'尚未同步')}}).catch(()=>{});
    }else{
      title.textContent='在其他设备同步记录';sub.textContent='游客模式 · 数据仅在本机';b.onclick=guestPage;
    }
    text.append(title,sub);b.append(text);const arrow=document.createElement('span');arrow.className='end';arrow.textContent='›';b.append(arrow);parent.append(b);
  }
  function about(parent){
    const p=document.createElement('p');p.className='muted';p.textContent='Blink '+(info?.versionName||(info?.version?'0.2.'+info.version:''));parent.append(p);
    if(info?.stage||native.desktop){
      const status=document.createElement('small');status.className='muted';status.style.display='block';
      const b=document.createElement('button');b.className='row';b.textContent='检查更新';b.onclick=()=>update(status);
      if(native.desktop)b.onclick=()=>native.checkUpdate().then(r=>{status.textContent=r||''}).catch(e=>{status.textContent=errorText(e)});
      parent.append(b,status);
    }
  }
  // Reports this device's activity; the reply says whether it should show a work reminder.
  async function presence(round){
    const s=get();if(!scope(s))return null;
    const idle=Math.max(0,Date.now()-(env.lastActive?.()||s.lastActive||0));
    return rpc('v2/presence',{idle,round,platform:native.desktop?'desktop':'android',exclusive:!!s.cloud.exclusive},info.account);
  }
  native.info().then(result=>{
    info=result;if(!info.enabled)return;
    if(info.account&&getKey()!==profile(info.account)){
      const s=JSON.parse(localStorage.getItem(profile(info.account))||'null')||initializeProfile(fresh(),false,info.device);
      s.cursor=Date.now();switchTo(profile(info.account),s);
    }else if(!info.account&&getKey()!==guestKey)returnGuest(false);
    render(false);watchVisibility();
    window.addEventListener('online',()=>{if(!document.hidden)sync(true)});
    document.addEventListener('visibilitychange',watchVisibility);
  }).catch(()=>display('无法读取本机凭证'));
  let syncTimer,pollTimer;
  function watchVisibility(){
    clearTimeout(pollTimer);clearTimeout(syncTimer);
    if(document.hidden&&!native.desktop)return;
    if(scope(get())){sync();presence().catch(()=>{});}
    pollTimer=setTimeout(watchVisibility,native.desktop?60000:30000);
  }
  function changed(){
    if(!scope(get())||document.hidden&&!native.desktop)return;
    clearTimeout(syncTimer);syncTimer=setTimeout(()=>{if(!failures)nextTry=0;sync()},750);
  }
  return {card,about,sync,changed,presence};
};
