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
  const ctx=vm.createContext({Date:Clock,crypto,window:{},document:{hidden:false,
    getElementById:()=>({addEventListener(){}})},localStorage:{getItem(){return null},setItem(){}}});
  const src=readFileSync('index.html','utf8').split('<script>')[1].split('</script>')[0];
  new Function(src);
  const run=code=>vm.runInContext(code,ctx);
  run(src.slice(0,src.indexOf("el('logo').innerHTML")));
  run('render=()=>{};visible=false');
  return {run, advance(time){ms=Date.parse(time+'+08:00');run('settle()')}};
}
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

test('lock screen follows work, dinner and explicit non-work sessions',()=>{
  const {run,advance}=engine('2026-11-23T17:50');
  run("data.end='19:00';settle()");
  const rows=run('faceRows(Date.now(),backgroundPlan(Date.now()))');
  const rowAt=hm=>rows.find(r=>r.from<=Date.parse('2026-11-23T'+hm+'+08:00')&&r.until>Date.parse('2026-11-23T'+hm+'+08:00'));
  assert.equal(rowAt('18:10').label,'晚餐结束');
  assert.equal(rowAt('18:40').work,true);
  assert.equal(rowAt('19:01'),undefined);
  advance('2026-11-23T19:05');run('startSession(30)');
  assert.equal(run('faceRows(Date.now(),backgroundPlan(Date.now()))[0].work'),false);
});
