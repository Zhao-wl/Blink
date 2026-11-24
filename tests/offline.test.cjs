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
  const ctx=vm.createContext({Date:Clock,crypto,setTimeout,clearTimeout,window:{},document:{hidden:false,
    getElementById:()=>({addEventListener(){},hidden:true})},
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

test('Android receives a plan with first-delivery wake flags and pauses it while away',()=>{
  const {run,advance}=engine('2026-11-23T09:00');
  run(`let native;
    window.blinkMobile={replace:(items,pref,display,authority)=>{native={items,authority};return Promise.resolve()}};
    data.delivery={enabled:true};data.confirmStart=false;settle();syncBackground()`);
  assert.equal(run('native.items[0].wake'),true);
  assert.equal(run('native.items[1].wake'),false);
  assert.equal(run('native.authority.owner'),'blink-auto-v2');
  advance('2026-11-23T09:10');run('say=()=>{};enterAway(Date.now()-60000);nativePlanSignature="";syncBackground()');
  assert.equal(run('native.items.length'),0);
  assert.equal(run('native.authority.awaySince'),Date.parse('2026-11-23T09:09:00+08:00'));
});

test('a signed-in work reminder is skipped when the server says another device is in use',async()=>{
  const {run,advance}=engine('2026-11-23T09:00');
  run(`say=()=>{};paintCard=()=>{};key='blink-profile-x';data.confirmStart=false;data.extraFreq=[[0,0],[0,0]];
    window.blinkAccounts={presence:()=>Promise.resolve({deliver:false})}`);
  advance('2026-11-23T09:20');run('checkReminder()');
  assert.equal(run('data.card.checking'),true);
  await new Promise(r=>setImmediate(r));
  assert.equal(run('data.card'),null);
  assert.equal(run('data.logs.length'),0);
  assert.equal(run('left(0)'),20*60000);
});
