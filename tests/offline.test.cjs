const test = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const vm = require('node:vm');

function engine(initial) {
  let ms = Date.parse(initial+'+08:00');
  class Clock extends Date {
    constructor(...args){super(...(args.length ? args : [ms]))}
    static now(){return ms}
  }
  const storage=new Map();
  const ctx=vm.createContext({Date:Clock,crypto,window:{},document:{hidden:false,
    getElementById:()=>({addEventListener(){}})},
    localStorage:{getItem:key=>storage.get(key)??null,setItem:(key,value)=>storage.set(key,value)}});
  const src=readFileSync('index.html','utf8').split('<script>')[1].split('</script>')[0];
  new Function(src);
  const run=code=>vm.runInContext(code,ctx);
  run(src.slice(0,src.indexOf("el('logo').innerHTML")));
  run(src.slice(src.indexOf('let nativePlanSignature'),src.indexOf('let importingConfirmations')));
  run(src.slice(src.indexOf('function scheduleTick()')));
  run('render=()=>{};visible=false');
  return {run, advance(time){ms=Date.parse(time+'+08:00');run('settle()')}};
}
test('mobile background uses the native plan while minimized desktop wakes at the next deadline',()=>{
  const {run}=engine('2026-11-23T09:00');
  run(`let scheduled;globalThis.setTimeout=(_,delay)=>{scheduled=delay};globalThis.clearTimeout=()=>{scheduled=undefined};
    data.delivery={enabled:true};window.blinkMobile={};document.hidden=true;
    settle();scheduleTick()`);
  assert.equal(run('scheduled'),undefined);
  run('delete window.blinkMobile;window.blinkDesktop={};document.hidden=false;desktopHidden=true;scheduleTick()');
  assert.equal(run('scheduled'),20*60000);
  run('desktopHidden=false;scheduleTick()');
  assert.equal(run('scheduled'),1000);
});
test('work skips lunch; off-screen timed session stops at its deadline',()=>{
  const {run,advance}=engine('2026-11-23T09:00');
  advance('2026-11-23T13:30');
  assert.equal(run("eyeDuration('2026-11-23')/minute"),210);
  advance('2026-11-28T10:00');run('startSession(60)');
  advance('2026-11-28T10:20');run('pauseFor(0,20000)');
  advance('2026-11-28T12:00');
  assert.equal(run("eyeDuration('2026-11-28')"),3600000-20000);
  assert.equal(run('data.session'),null);
});
test('background notifications skip lunch and pair a reminder with one retry',()=>{
  const {run}=engine('2026-11-23T09:00');
  run('settle()');const plan=run('backgroundPlan(Date.now())');
  assert.ok(plan.length>0&&plan.length<=60);
  assert.equal(plan[0].at,Date.parse('2026-11-23T09:20:00+08:00'));
  assert.equal(plan[1].token,plan[0].token);
  assert.equal(plan[1].at-plan[0].at,300000);
  for(const item of plan){const hour=new Date(item.at+8*3600000).getUTCHours();assert.ok(hour>=9&&hour<18&&hour!==12)}
});

test('work reminders require the current account to own an unexpired authorization',()=>{
  const {run}=engine('2026-11-23T09:00');
  assert.equal(run('workAllowed()'),true);
  run("key='blink-profile-example';data.cloud={device:'a'}");
  assert.equal(run('workAllowed()'),false);
  run("data.cloud.reminder={device:'a',requested:null,localExpires:Date.now()+60000}");
  assert.equal(run('workAllowed()'),true);
  run("data.cloud.reminder.requested='b'");
  assert.equal(run('workAllowed()'),false);
  run('data.cloud.reminder.requested=null');
  // Advance the clock directly so the test does not require a cloud observer.
  assert.equal(run('workAllowed(Date.now()+60000)'),false);
});

test('Android sends next-day work candidates even when the current lease expires overnight',()=>{
  const {run}=engine('2026-11-23T18:00');
  run(`let native;
    window.blinkMobile={replace:(items,pref,display,authority)=>{native={items,authority};return Promise.resolve()}};
    key='blink-profile-test';data.delivery={enabled:true};
    data.cloud={device:'a',reminder:{device:'a',requested:null,revision:2,localExpires:Date.now()+12*60*minute}};
    settle();syncBackground()`);
  assert.equal(run('native.authority.monitorWork'),true);
  assert.equal(run('native.items[0].at'),Date.parse('2026-11-24T09:20:00+08:00'));
  assert.equal(run('native.items[0].at>native.authority.lease.localExpires'),true);
  assert.equal(run('native.items.every(item=>item.shared)'),true);
});

test('locked Android takeover candidates stay gated until native authorizes the new primary',()=>{
  const {run}=engine('2026-11-23T09:00');
  run(`let native;
    window.blinkMobile={replace:(items,pref,display,authority)=>{native={items,display,authority};return Promise.resolve()}};
    key='blink-profile-test';data.delivery={enabled:true};
    data.cloud={device:'b',reminder:{device:'a',requested:'b',revision:3,localExpires:Date.now()+minute},
      reminderCheckedElapsed:10,reminderBoot:4};
    settle();syncBackground()`);
  assert.equal(run('workAllowed()'),false);
  assert.equal(run('native.authority.monitorWork'),true);
  assert.equal(run('native.items[0].shared'),true);
  assert.equal(run('native.display'),undefined);
  run(`applyNativeReminderAuthority({boot:4,checkedElapsed:11,
    lease:{device:'b',requested:null,revision:4,localExpires:Date.now()+60*minute}})`);
  assert.equal(run('workAllowed()'),true);
  run('applyNativeReminderAuthority({boot:4,checkedElapsed:9,legacy:true})');
  assert.equal(run('data.cloud.reminder.revision'),4);
});

test('opening a shared Android alert uses the renewed native lease without a network request',async()=>{
  const {run}=engine('2026-11-23T09:20');
  run(`let allowed=false,message='';
    say=text=>{message=text};key='blink-profile-test';data.cloud={device:'b'};
    localStorage.setItem('blink-pending:'+key,JSON.stringify([
      {token:'tap',k:0,round:'round',at:Date.now(),expires:Date.now()+minute,shared:true}
    ]));
    window.blinkMobile={validateTap:async()=>({valid:allowed,authority:{
      boot:4,checkedElapsed:11,lease:{device:'b',requested:null,revision:4,localExpires:Date.now()+60*minute}
    }})};`);
  await run("openMobileReminder('tap')");
  assert.equal(run('workAllowed()'),false);
  assert.equal(run('data.card??null'),null);
  assert.equal(run('message'),'这条提醒已结束');
  run('allowed=true');
  await run("openMobileReminder('tap')");
  assert.equal(run('data.card.id'),'tap');
  assert.equal(run('workAllowed()'),true);
});

test('legacy Android work reminders still carry account scope and stop when notifications are disabled',()=>{
  const {run}=engine('2026-11-23T09:00');
  run(`let native;
    window.blinkMobile={replace:(items,pref,display,authority)=>{native={items,display,authority};return Promise.resolve()}};
    key='blink-profile-test';data.delivery={enabled:true};data.cloud={device:'a',reminderLegacy:true};
    settle();syncBackground()`);
  assert.equal(run('workAllowed()'),true);
  assert.equal(run('native.authority.legacyWork'),true);
  assert.equal(run('native.authority.lease'),null);
  assert.equal(run('native.display.shared'),true);
  run('data.delivery.enabled=false;syncBackground()');
  assert.equal(run('native.authority.monitorWork'),false);
  assert.equal(run('native.items.length'),0);
});

test('dinner pauses eye time and reminders, then resumes the remaining interval',()=>{
  const {run,advance}=engine('2026-11-23T17:50');
  run("data.end='20:00';settle()");
  assert.equal(run('projectedTime(Date.now(),20*minute)'),Date.parse('2026-11-23T18:40+08:00'));
  assert.equal(run('backgroundPlan(Date.now())[0].at'),Date.parse('2026-11-23T18:40+08:00'));
  advance('2026-11-23T18:10');
  assert.equal(run('status(Date.now()).meal'),'晚餐');
  assert.equal(run("eyeDuration('2026-11-23')/minute"),10);
  advance('2026-11-23T18:40');
  assert.equal(run('data.progress[0]/minute'),20);
  assert.equal(run("eyeDuration('2026-11-23')/minute"),20);
});
test('dinner never extends the scheduled shift',()=>{
  const {run,advance}=engine('2026-11-23T17:50');
  run("data.dinnerStart='17:55'");
  advance('2026-11-23T17:58');
  assert.equal(run('status(Date.now()).mealUntil'),Date.parse('2026-11-23T18:00+08:00'));
  advance('2026-11-23T19:00');
  assert.equal(run('status(Date.now()).work'),false);
  assert.equal(run("eyeDuration('2026-11-23')/minute"),5);
});

test('lock screen confirmation records its tap time once and excludes a remote break',()=>{
  const {run,advance}=engine('2026-11-23T10:00');
  advance('2026-11-23T10:10');
  run("const tap={id:'test-round',k:1,at:Date.now()-5*minute,rest:10*minute};applyLockConfirmation(tap);applyLockConfirmation(tap)");
  assert.equal(run('data.logs.length'),1);
  assert.equal(run('data.logs[0].at'),Date.parse('2026-11-23T10:05+08:00'));
  advance('2026-11-23T10:20');
  assert.equal(run("eyeDuration('2026-11-23')/minute"),10);
});
