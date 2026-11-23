// Read the original debug build's guest snapshot while Blink is open on the device.
import { execFileSync } from 'node:child_process';
import { writeFile } from 'node:fs/promises';
import { chromium } from 'playwright-core';

const [serial, destination] = process.argv.slice(2);
if (!serial || !destination) throw Error('Usage: node scripts/export-android-guest.mjs DEVICE NEW_BACKUP.json');
const adb = (...args) => execFileSync(process.env.ADB || 'adb', ['-s', serial, ...args], { encoding: 'utf8' }).trim();
const pid = adb('shell', 'pidof', 'com.zhaowl.blink');
if (!/^\d+$/.test(pid)) throw Error('Open the original Blink app on the device first');
const port = adb('forward', 'tcp:0', 'localabstract:webview_devtools_remote_' + pid);
let browser;
try {
  browser = await chromium.connectOverCDP('http://127.0.0.1:' + port, { timeout: 10000 });
  const page = browser.contexts()[0].pages().find(p => new URL(p.url()).hostname === 'localhost');
  if (!page) throw Error('Could not find the original Blink app page');
  const state = await page.evaluate(() => JSON.parse(localStorage.getItem('blink-auto-v2')));
  if (!state || !Array.isArray(state.logs) || !state.totals || state.cloud) throw Error('Missing guest data');
  await writeFile(destination, JSON.stringify({format: 'blink-backup-v1', state}), {mode: 0o600, flag: 'wx'});
  console.log('已导出游客记录 ' + state.logs.length + ' 条、每日估算 ' + Object.keys(state.totals).length + ' 天');
} finally {
  if (browser) await browser.close();
  adb('forward', '--remove', 'tcp:' + port);
}
