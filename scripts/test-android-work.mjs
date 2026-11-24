import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const classes = mkdtempSync(path.join(tmpdir(), 'blink-android-rules-'));
const executable = name => process.env.JAVA_HOME
  ? path.join(process.env.JAVA_HOME, 'bin', name + (process.platform === 'win32' ? '.exe' : ''))
  : name;

try {
  execFileSync(executable('javac'), [
    '-encoding', 'UTF-8', '-d', classes,
    'mobile/android/BlinkRules.java', 'tests/android/BlinkRulesTest.java'
  ], { cwd: root, stdio: 'inherit' });
  execFileSync(executable('java'), ['-cp', classes, 'com.zhaowl.blink.BlinkRulesTest'], { stdio: 'inherit' });
} finally {
  rmSync(classes, { recursive: true, force: true });
}
