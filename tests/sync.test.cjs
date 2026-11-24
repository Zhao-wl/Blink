const test = require('node:test');
const assert = require('node:assert/strict');
require('../sync.js');
const sync = globalThis.BlinkSync;

test('overlapping devices count forty minutes, migration baseline ends at its cutoff', () => {
  const day = '2026-11-23', minute = 60000;
  const s = {totals: {}};
  const c = sync.accountState(s, 'phone');
  sync.apply(s, 'spans/'+day+'/phone', [[0,30*minute]]);
  sync.apply(s, 'spans/'+day+'/computer', [[10*minute,40*minute]]);
  assert.equal(s.totals[day], 40*minute);
  sync.apply(s, 'baseline/'+day, [3*minute,10*minute]);
  assert.equal(s.totals[day], 33*minute);
  assert.equal(Object.keys(c.local).length, 3);
});
test('a revoke during upload survives its acknowledgement and tombstone replay', () => {
  const r = {id:crypto.randomUUID(), at:Date.now(), k:2};
  const s = {logs:[r]};
  sync.accountState(s,'phone');
  const sent = sync.recordBatch(s);
  s.logs = [];
  sync.records(s,{records:[r],cursor:1},sent);
  assert.deepEqual(sync.recordBatch(s),[{id:r.id,deleted:true}]);
  sync.records(s,{records:[{id:r.id,deleted:true}],cursor:2},sync.recordBatch(s));
  assert.deepEqual(sync.recordBatch(s),[]);
  assert.deepEqual(s.logs,[]);
});

test('a stale conflict response leaves a later edit queued', () => {
  const s = {};
  const c = sync.accountState(s, 'phone');
  const newer = {key:'pref/eye',value:45,base:1,mutation:'new-edit'};
  c.pending[newer.key] = newer;
  sync.objects(s,{
    changes:[],accepted:[],cursor:2,epoch:'test',
    conflicts:[{key:newer.key,value:30,rev:2,mutation:'old-edit'}]
  },[]);
  assert.deepEqual(c.pending[newer.key],newer);
  assert.equal(c.conflicts[newer.key],undefined);
  assert.deepEqual(c.remote[newer.key],{value:30,rev:2});
});
