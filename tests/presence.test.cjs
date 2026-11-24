const test = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const vm = require('node:vm');

// Same slicing harness as offline.test.cjs: run the engine without a DOM.
function engine(initial) {
  let ms = Date.parse(initial+'+08:00');
  class Clock extends Date {
    constructor(...args){super(...(args.length ? args : [ms]))}
    static now(){return ms}
  }
  const storage=new Map();
  const ctx=vm.createContext({Date:Clock,crypto,window:{},document:{hidden:false,
    getElementById:()=>({addEventListener(){},hidden:true})},
    localStorage:{getItem:key=>storage.get(key)??null,setItem:(key,value)=>storage.set(key,value)}});
  const src=readFileSync('index.html','utf8').split('<script>')[1].split('</script>')[0];
  const run=code=>vm.runInContext(code,ctx);
  run(src.slice(0,src.indexOf("el('logo').innerHTML")));
  run(src.slice(src.indexOf('let nativePlanSignature'),src.indexOf('let importingConfirmations')));
  run(src.slice(src.indexOf('function scheduleTick()')));
  run('render=()=>{};paintCard=()=>{};say=(text)=>{globalThis.lastSay=text};visible=false;data.lastActive=Date.now();data.onboarded=true;data.createdAt=0');
  return {run, at(time){ms=Date.parse(time+'+08:00')}, advance(time){ms=Date.parse(time+'+08:00');run('settle()')}};
}
const T=(s)=>Date.parse(s+'+08:00');

test('an unanswered reminder retries once, then pauses counting until the person returns',()=>{
  const e=engine('2026-11-23T09:00');
  e.run("data.confirmStart=false;data.freq=[20,20];data.extraFreq=[[0,0],[0,0]]");
  e.advance('2026-11-23T09:20');e.run('checkReminder()');
  assert.equal(e.run('data.card.k'),0);
  e.advance('2026-11-23T09:25');e.run('checkReminder()');
  assert.equal(e.run('data.card.retry'),true);
  e.advance('2026-11-23T09:30');e.run('checkReminder()');
  assert.equal(e.run('data.away.from'),T('2026-11-23T09:20'));
  assert.equal(e.run('data.card'),null);
  // Time while away is not eye use.
  e.advance('2026-11-23T09:50');
  assert.equal(e.run("eyeDuration('2026-11-23')/minute"),20);
  e.run('markActive()');
  assert.equal(e.run('data.away'),null);
  const log=e.run('data.logs[0]');
  assert.equal(log.k,1);assert.equal(log.at,T('2026-11-23T09:20'));assert.equal(log.auto,true);
  assert.equal(e.run('left(0)'),20*60000);
  assert.match(e.run('lastSay'),/记为一次离席/);
});

test('a short absence resumes without inventing a break',()=>{
  const e=engine('2026-11-23T09:00');
  e.run("data.confirmStart=false");
  e.advance('2026-11-23T09:10');e.run('enterAway(Date.now()-60000)');
  e.advance('2026-11-23T09:15');e.run('markActive()');
  assert.equal(e.run('data.logs.length'),0);
  assert.equal(e.run("eyeDuration('2026-11-23')/minute"),9);
});

test('end of day asks once; overtime extends and the question repeats',()=>{
  const e=engine('2026-11-23T17:00');
  e.run("data.confirmStart=false");
  e.advance('2026-11-23T17:55');e.run('markActive()');
  e.advance('2026-11-23T18:00');e.run('checkDay()');
  assert.equal(e.run('data.prompt.kind'),'end');
  e.advance('2026-11-23T18:05');
  assert.equal(e.run('status(Date.now()).work'),true);
  e.run("answerPrompt(data.prompt.id,'end-30')");
  assert.equal(e.run("data.days['2026-11-23'].end"),'18:35');
  e.advance('2026-11-23T18:34');e.run('markActive()');
  e.advance('2026-11-23T18:35');e.run('checkDay()');
  assert.equal(e.run('data.prompt.title'),'还在工作吗？');
  e.run("answerPrompt(data.prompt.id,'end-off')");
  e.advance('2026-11-23T18:40');
  assert.equal(e.run('status(Date.now()).work'),false);
});

test('an unanswered end question ends work at the scheduled time',()=>{
  const e=engine('2026-11-23T17:50');
  e.run("data.confirmStart=false");e.run('markActive()');
  e.advance('2026-11-23T18:00');e.run('checkDay()');
  const before=e.run("eyeDuration('2026-11-23')");
  e.advance('2026-11-23T18:31');e.run('checkDay()');
  assert.equal(e.run("data.days['2026-11-23'].end"),'18:00');
  assert.equal(e.run('status(Date.now()).work'),false);
  assert.equal(e.run("eyeDuration('2026-11-23')"),before);
});

test('nobody at the start time holds work until the first activity is confirmed',()=>{
  const e=engine('2026-11-23T08:00');
  e.run("data.lastActive=Date.parse('2026-11-22T20:00:00+08:00')");
  e.advance('2026-11-23T09:01');e.run('checkDay()');
  assert.equal(e.run("data.days['2026-11-23'].hold"),true);
  assert.equal(e.run('status(Date.now()).work'),false);
  e.advance('2026-11-23T09:40');e.run('markActive()');
  assert.equal(e.run('data.prompt.kind'),'start');
  e.run("answerPrompt(data.prompt.id,'start-now')");
  assert.equal(e.run("data.days['2026-11-23'].start"),'09:40');
  assert.equal(e.run('status(Date.now()).work'),true);
});

test('choosing the planned start time counts the morning retroactively',()=>{
  const e=engine('2026-11-23T08:00');
  e.run("data.lastActive=0");
  e.advance('2026-11-23T09:01');e.run('checkDay()');
  e.advance('2026-11-23T09:30');e.run('markActive()');
  e.run("answerPrompt(data.prompt.id,'start-plan')");
  assert.equal(e.run('status(Date.now()).work'),true);
  assert.equal(e.run("Math.round(eyeDuration('2026-11-23')/minute)"),30);
});

test('leaving early lasts until tomorrow; returning restores the fixed end time',()=>{
  const e=engine('2026-11-23T09:00');
  e.run("data.confirmStart=false");
  e.advance('2026-11-23T15:00');e.run('offNow()');
  assert.equal(e.run('status(Date.now()).work'),false);
  e.advance('2026-11-23T15:30');e.run('workNow()');
  assert.equal(e.run('status(Date.now()).work'),true);
  e.advance('2026-11-24T10:00');
  assert.equal(e.run('status(Date.now()).work'),true);
  assert.equal(e.run("data.days['2026-11-24']"),undefined);
});

test('rest today and late start tomorrow are both undoable day overrides',()=>{
  const e=engine('2026-11-23T10:00');
  e.run("data.confirmStart=false;restToday()");
  assert.equal(e.run('status(Date.now()).work'),false);
  e.run("setDay('2026-11-24',{start:'10:30',confirmed:true})");
  e.at('2026-11-24T10:00');assert.equal(e.run('status(Date.now()).work'),false);
  e.at('2026-11-24T10:31');assert.equal(e.run('status(Date.now()).work'),true);
});

test('custom workdays: single-rest week works Saturday, alternate week every other Saturday',()=>{
  const e=engine('2026-11-28T10:00');
  e.run("data.confirmStart=false");
  assert.equal(e.run("dayType('2026-11-28')"),'周末');
  e.run("data.workdays='single'");
  assert.equal(e.run("dayType('2026-11-28')"),'工作日');
  e.run("data.workdays='alt';data.altSaturday='2026-11-28'");
  assert.equal(e.run("dayType('2026-11-28')"),'工作日');
  assert.equal(e.run("dayType('2026-12-05')"),'周末');
  assert.equal(e.run("dayType('2026-12-12')"),'工作日');
  e.run("data.workdays=[1,3,5]");
  assert.equal(e.run("dayType('2026-11-24')"),'周末');
});

test('missing holiday year still follows the weekday rule',()=>{
  const e=engine('2028-03-06T10:00');
  assert.equal(e.run("dayType('2028-03-06')"),'工作日');
  assert.equal(e.run("calendarMissing('2028-03-06')"),true);
});
