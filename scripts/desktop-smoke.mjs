import { _electron as electron } from 'playwright-core';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

const profile=await mkdtemp(path.join(os.tmpdir(),'blink-smoke-'));
const executablePath=path.resolve(process.argv[2]);
const launch=()=>electron.launch({executablePath,args:['--user-data-dir='+profile],timeout:60000});
let instance;
try {
  instance=await launch();
  await instance.context().setOffline(true);
  let page=await instance.firstWindow();
  await page.reload();
  await page.locator('#countdown').waitFor();
  await page.locator('#offMode').click();
  assert.equal(await page.locator('#offMode').getAttribute('aria-pressed'),'true');
  await page.locator('#workMode').click();
  assert.equal(await page.locator('#workMode').getAttribute('aria-pressed'),'true');
  await page.getByRole('button',{name:'日程',exact:true}).click();
  await page.locator('#appearance').selectOption('dark');
  assert.equal(await page.locator('html').getAttribute('data-theme'),'dark');
  await page.getByRole('button',{name:'记录',exact:true}).click();
  await page.getByRole('button',{name:'记一次',exact:true}).click();
  await page.getByRole('dialog').getByRole('button',{name:'喝水',exact:true}).click();
  assert.equal(await page.locator('#feed .recorded').count(),1);
  await instance.close();
  instance=await launch();
  await instance.context().setOffline(true);
  page=await instance.firstWindow();
  await page.getByRole('button',{name:'记录',exact:true}).click();
  await page.locator('#feed .recorded').waitFor();
  assert.equal(await page.locator('html').getAttribute('data-theme'),'dark');
  assert.match(await page.locator('#feed .recorded').innerText(),/喝水/);
  await page.getByRole('button',{name:'日程',exact:true}).click();
  await page.locator('#planMode').waitFor();
  console.log('Packaged app opened offline; one drink survived restart.');
} finally {
  if(instance)await instance.close().catch(()=>{});
  await rm(profile,{recursive:true,force:true,maxRetries:4,retryDelay:300});
}
