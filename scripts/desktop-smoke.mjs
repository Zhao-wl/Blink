import { _electron as electron } from 'playwright-core';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

// usage: node scripts/desktop-smoke.mjs <electron or packaged Blink executable> [app dir]
const profile=await mkdtemp(path.join(os.tmpdir(),'blink-smoke-'));
const executablePath=path.resolve(process.argv[2]);
const extra=process.argv[3]?[path.resolve(process.argv[3])]:[];
const launch=()=>electron.launch({executablePath,args:[...extra,'--user-data-dir='+profile],timeout:60000});
const send=(instance,channel,...args)=>instance.evaluate(({BrowserWindow},[channel,args])=>{
  const win=BrowserWindow.getAllWindows().find(w=>w.webContents.getURL().endsWith('index.html'));win.webContents.send(channel,...args);
},[channel,args]);
let instance;
try {
  instance=await launch();
  await instance.context().setOffline(true);
  let page=await instance.firstWindow();
  await page.reload({waitUntil:'domcontentloaded',timeout:60000});
  // First run: three steps, then the home screen. (The autostart question is covered separately.)
  await page.evaluate(()=>{data.autoStartAsked=true;save()});
  await page.getByRole('button',{name:'下一步'}).click();
  await page.getByRole('button',{name:'下一步'}).click();
  await page.getByRole('checkbox',{name:'系统通知'}).check();
  await page.getByRole('button',{name:'开始使用'}).click();
  await page.locator('#countdown').waitFor();
  assert.equal(await page.locator('html').getAttribute('data-platform'),'desktop');
  const nav=await page.locator('#nav').boundingBox();
  const timer=await page.locator('.timer-panel').boundingBox();
  assert.ok(nav.x+nav.width<timer.x);

  // Make "now" a workday at 10:00 regardless of the real clock, then use the capsule.
  await page.evaluate(()=>{data.workdays=[0,1,2,3,4,5,6];data.holidays=false;data.start='00:00';data.lunchStart=data.lunchEnd='00:00';
    data.end='23:58';data.dinnerStart=data.dinnerEnd='23:58';data.days={};data.confirmStart=false;render()});
  assert.match(await page.locator('#capsuleText').innerText(),/上班中/);
  await page.locator('#capsule').click();
  await page.getByRole('dialog').getByRole('button',{name:'现在下班'}).click();
  assert.match(await page.locator('#capsuleText').innerText(),/已下班|今天休息/);
  await page.getByRole('button',{name:'撤销'}).click();
  assert.match(await page.locator('#capsuleText').innerText(),/上班中/);

  await page.getByRole('button',{name:'设置',exact:true}).click();
  await page.getByLabel('晚餐开始').fill('23:58');
  await page.getByLabel('主题').selectOption('dark');
  assert.equal(await page.locator('html').getAttribute('data-theme'),'dark');
  await page.getByRole('button',{name:'今日',exact:true}).click();

  // A minimized window still delivers the reminder at the bottom-right corner.
  const main=await instance.browserWindow(page);
  await main.evaluate(win=>win.minimize());
  await page.waitForFunction(()=>pageHidden());
  const popupPromise=instance.waitForEvent('window',{timeout:15000});
  await page.evaluate(()=>{settle();data.progress[2]=frequency(2)*minute-500;data.nextCardAt=0;scheduleTick()});
  const reminder=await popupPromise;
  const reminderWindow=await instance.browserWindow(reminder);
  const bounds=await reminderWindow.evaluate(win=>win.getBounds());
  const area=await instance.evaluate(({screen},rect)=>screen.getDisplayMatching(rect).workArea,bounds);
  assert.equal(area.x+area.width-bounds.x-bounds.width,16);
  assert.equal(area.y+area.height-bounds.y-bounds.height,16);
  const closed=reminder.waitForEvent('close',{timeout:10000});
  await reminder.keyboard.press('Enter').catch(()=>{});
  await closed;
  await main.evaluate(win=>win.restore());
  await page.waitForFunction(()=>!pageHidden());
  assert.equal(await page.evaluate(()=>data.logs.filter(r=>r.k===2).length),1);

  // Three idle minutes pause counting; activity resumes and an absence longer than a break is logged.
  await page.evaluate(()=>{settle();data.lastActive=Date.now()-20*60000});
  await send(instance,'desktop:presence',{idle:15*60,locked:false});
  await page.waitForFunction(()=>!!data.away);
  assert.match(await page.locator('#capsuleText').innerText(),/已暂停/);
  await send(instance,'desktop:presence',{idle:1,locked:false});
  await page.waitForFunction(()=>!data.away);
  assert.equal(await page.evaluate(()=>data.logs.some(r=>r.auto&&r.k===1)),true);

  // Day questions arrive as a popup with their own buttons.
  const promptPromise=instance.waitForEvent('window',{timeout:15000});
  await page.evaluate(()=>showPrompt({kind:'end',title:'下班了？',body:'测试',actions:[['end-off','下班'],['end-30','再 30 分钟']]}));
  const prompt=await promptPromise;
  const promptClosed=prompt.waitForEvent('close',{timeout:10000});
  await prompt.getByRole('button',{name:'再 30 分钟'}).click().catch(()=>{});
  await promptClosed;
  await page.waitForFunction(()=>!data.prompt);

  await page.getByRole('button',{name:'记录',exact:true}).click();
  await page.locator('#feed .recorded').first().waitFor();
  assert.equal(await page.locator('#feed .auto').count(),1);

  // Closing the window keeps Blink in the tray; quitting is explicit.
  await main.evaluate(win=>win.close());
  await page.waitForFunction(()=>pageHidden());
  assert.equal(await instance.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows().filter(w=>!w.isDestroyed()).length>0),true);
  const exit=instance.waitForEvent('close',{timeout:10000});
  await instance.evaluate(({app})=>app.quit());
  await exit;
  instance=await launch();
  await instance.context().setOffline(true);
  page=await instance.firstWindow();
  await page.getByRole('button',{name:'记录',exact:true}).click();
  await page.locator('#feed .recorded').first().waitFor();
  assert.equal(await page.locator('html').getAttribute('data-theme'),'dark');
  assert.ok(await page.locator('#feed .recorded').count()>=2);
  console.log('Desktop: onboarding, capsule undo, minimized reminder, idle pause, prompt popup, tray close and restart all passed.');
} finally {
  if(instance)await instance.close().catch(()=>{});
  await rm(profile,{recursive:true,force:true,maxRetries:4,retryDelay:300});
}
