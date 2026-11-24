const test = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const vm = require('node:vm');

const source = readFileSync(require.resolve('../account.js'), 'utf8');
const device = '11111111-1111-4111-8111-111111111111';
const other = '22222222-2222-4222-8222-222222222222';

function legacy(checkedElapsed) {
  return {devices:[{id:device,label:'本机',expires:1800000000,current:true}],checkedElapsed,boot:3};
}

function modern(device, revision, checkedElapsed, requested=null) {
  return {device,requested,revision,checkedElapsed,boot:3,expiresAt:1700000100000,serverTime:1700000000000,
    localExpires:1700000100000,elapsedExpires:checkedElapsed+100000};
}

async function accountEnvironment(reply) {
  const s = {cloud:{device, pending:{}}};
  const actions = [], saved = [], refreshes = [];
  const snapshot = () => JSON.parse(JSON.stringify(s.cloud));
  const context = vm.createContext({
    window:{addEventListener(){}, BlinkSync:{recordBatch:()=>[], records(){}, observe(){}, objects(){}}},
    document:{hidden:true,getElementById:()=>null,addEventListener(){}},
  });
  vm.runInContext(source, context);
  const accounts = context.window.BlinkAccounts({
    native:{
      info:async()=>({enabled:true, account:'account', device}),
      request:async({route,body})=>{
        if(route!=='v2/devices')return {};
        actions.push(body.action);
        return reply(body);
      },
    },
    modal:{open:false}, get:()=>s, getKey:()=>'blink-profile-account',
    save:()=>{saved.push(snapshot());return true;}, settle(){},render(){},say(){},
    refreshReminders:()=>refreshes.push(snapshot()),
    stopWorkReminders:async revision=>actions.push('stop:'+revision),
  });
  await new Promise(setImmediate);
  return {accounts,snapshot,actions,saved,refreshes};
}

test('a new client restores local work reminders after authenticating to a legacy API', async () => {
  const {accounts,snapshot,saved,refreshes}=await accountEnvironment(()=>legacy(100));
  await accounts.sync(true);
  await new Promise(setImmediate);
  assert.equal(snapshot().reminderLegacy,true);
  assert.equal(snapshot().reminder,undefined);
  assert.equal(saved.at(-1).reminderCheckedElapsed,100);
  assert.equal(saved.at(-1).reminderBoot,3);
  assert.equal(refreshes.at(-1).reminderLegacy,true);
});

test('legacy compatibility ends when the API returns modern state and resumes on confirmed rollback', async () => {
  let answer=legacy(100);
  const {accounts,snapshot}=await accountEnvironment(()=>answer);
  await accounts.refreshReminderAuthority();
  answer=modern(other,2,200);
  await accounts.refreshReminderAuthority();
  assert.equal(snapshot().reminderLegacy,undefined);
  assert.equal(snapshot().reminder.device,other);
  answer=legacy(150);
  await accounts.refreshReminderAuthority();
  assert.equal(snapshot().reminder.device,other);
  answer=legacy(300);
  await accounts.refreshReminderAuthority();
  assert.equal(snapshot().reminderLegacy,true);
  assert.equal(snapshot().reminder,undefined);
  answer=modern(device,1,400);
  await accounts.refreshReminderAuthority();
  assert.equal(snapshot().reminderLegacy,undefined);
  assert.equal(snapshot().reminder.device,device);
});

test('transport errors and unknown responses cannot enable legacy reminders, and 401 removes compatibility', async () => {
  let answer=()=>{throw Error('network unavailable');};
  const {accounts,snapshot}=await accountEnvironment(()=>answer());
  await assert.rejects(accounts.refreshReminderAuthority(),/network unavailable/);
  assert.equal(snapshot().reminderLegacy,undefined);
  answer=()=>({devices:[{id:device}]});
  await assert.rejects(accounts.refreshReminderAuthority(),/主提醒设备状态暂不可用/);
  assert.equal(snapshot().reminderLegacy,undefined);
  answer=()=>({revision:1,device,requested:null,localExpires:1800000000000});
  await assert.rejects(accounts.refreshReminderAuthority(),/主提醒设备状态暂不可用/);
  assert.equal(snapshot().reminder,undefined);
  answer=()=>legacy(100);
  await accounts.refreshReminderAuthority();
  answer=()=>{throw Object.assign(Error('invalid_session'),{code:'401'});};
  await assert.rejects(accounts.refreshReminderAuthority(),/invalid_session/);
  assert.equal(snapshot().reminderLegacy,undefined);
  assert.equal(snapshot().reminderCheckedElapsed,undefined);
});

test('a legacy client stops reminders before releasing its modern authorization during handoff', async () => {
  let answer=legacy(100);
  const {accounts,snapshot,actions}=await accountEnvironment(({action})=>action==='release'?modern(other,4,300):answer);
  await accounts.refreshReminderAuthority();
  answer=modern(device,3,200,other);
  await accounts.refreshReminderAuthority();
  assert.deepEqual(actions,['status','status','stop:3','release']);
  assert.equal(snapshot().reminder.device,other);
  assert.equal(snapshot().reminderLegacy,undefined);
});
