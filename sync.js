/* Account data lives in the same atomic local snapshot as its replication cursors. */
(function(root){
  const HOURS=['start','lunchStart','lunchEnd','end','dinnerStart','dinnerEnd'];
  const DAY=86400000, clone=x=>JSON.parse(JSON.stringify(x));
  const eq=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
  function union(ranges){
    const result=[];
    for(const [a,b] of ranges.slice().sort((x,y)=>x[0]-y[0])){
      const last=result[result.length-1];
      if(last&&a<=last[1])last[1]=Math.max(b,last[1]);else result.push([a,b]);
    }
    return result;
  }
  function accountState(s,device){
    return s.cloud={device,logs:{},remote:{},seen:{},pending:{},conflicts:{},local:{},cursor:0,recordCursor:0,epoch:'',synced:0};
  }
  function collect(s){
    const c=s.cloud, out={...c.local};
    out.hours=HOURS.map(k=>s[k]);
    out['pref/eye']=s.freq[0];out['pref/screen']=s.freq[1];
    out['pref/rest']=s.extraFreq[0][0];out['pref/water']=s.extraFreq[1][0];out['pref/length']=s.breakMinutes;
    for(const b of s.blocks)out['block/'+b.id]=b;
    for(const [day,offset] of Object.entries(s.corrections))out['correction/'+day]=offset;
    if(s.shift)out['shift/'+new Date(s.shift.from+8*3600000).toISOString().slice(0,10)]=s.shift;
    return out;
  }
  function observe(s){
    const c=s.cloud;
    if(!c)return;
    const out=collect(s);
    for(const key of new Set([...Object.keys(c.seen),...Object.keys(out)])){
      const value=out[key]??null;
      if(eq(c.seen[key]??null,value))continue;
      c.pending[key]={key,value:clone(value),base:c.remote[key]?.rev||0,mutation:crypto.randomUUID()};
      c.seen[key]=clone(value);
    }
  }
  function span(s,day,from,to,midnight){
    if(!s.cloud||from>=to)return;
    const key='spans/'+day+'/'+s.cloud.device, a=from-midnight,b=to-midnight;
    const rows=s.cloud.local[key]??=[];
    const last=rows[rows.length-1];
    if(last&&last[1]>=a)last[1]=Math.max(last[1],b);else rows.push([a,b]);
  }
  function rebuild(s,day){
    const c=s.cloud,baseline=c.local['baseline/'+day]||[0,0],all=[];
    for(const [k,v] of Object.entries(c.local)){
      if(k.startsWith('spans/'+day+'/'))for(const [a,b] of v)if(b>baseline[1])all.push([Math.max(a,baseline[1]),b]);
    }
    s.totals[day]=Math.min(DAY,baseline[0]+union(all).reduce((sum,r)=>sum+r[1]-r[0],0));
  }
  function apply(s,key,value){
    const c=s.cloud,[kind,id]=key.split('/');
    if(kind==='hours')HOURS.forEach((k,i)=>s[k]=value[i]);
    else if(kind==='pref'){
      if(id==='length')s.breakMinutes=value;
      else if(id==='eye'||id==='screen')s.freq[id==='eye'?0:1]=value;
      else s.extraFreq[id==='rest'?0:1][0]=value;
    }else if(kind==='block'){
      s.blocks=s.blocks.filter(b=>b.id!==id);if(value)s.blocks.push(value);
    }else if(kind==='correction')s.corrections[id]=value||0;
    else if(kind==='shift'){
      if(value?.until>Date.now())s.shift=value;
      else if(!value&&s.shift&&new Date(s.shift.from+8*3600000).toISOString().slice(0,10)===id)s.shift=null;
      if(value?.until<=Date.now())c.local[key]=value;
    }else if(kind==='spans'||kind==='baseline'){c.local[key]=value;rebuild(s,id);}
    c.seen[key]=clone(value);
  }
  function recordBatch(s){
    const local=new Map(s.logs.map(r=>[r.id,r])),changes=[];
    for(const r of local.values()){if(!s.cloud.logs[r.id])changes.push(r);if(changes.length===200)return changes;}
    for(const id of Object.keys(s.cloud.logs)){if(!local.has(id))changes.push({id,deleted:true});if(changes.length===200)break;}
    return changes;
  }
  function records(s,reply,sent){
    const c=s.cloud,local=new Map(s.logs.map(r=>[r.id,r]));
    for(const r of sent){if(r.deleted)delete c.logs[r.id];else c.logs[r.id]=true;}
    for(const r of reply.records){
      if(r.deleted){delete c.logs[r.id];local.delete(r.id);}
      else{
        // Keep a local revoke pending even if the upload acknowledgement raced with it.
        const revoked=c.logs[r.id]&&!local.has(r.id);
        c.logs[r.id]=true;if(!revoked)local.set(r.id,r);
      }
    }
    s.logs=[...local.values()].sort((a,b)=>b.at-a.at);c.recordCursor=reply.cursor;
  }
  function objects(s,reply,sent){
    const c=s.cloud;
    for(const item of reply.changes){
      c.remote[item.key]={rev:item.rev,value:item.value};
      const pending=c.pending[item.key];
      if(!pending||reply.accepted.includes(pending.mutation)){
        delete c.pending[item.key];apply(s,item.key,item.value);
      }else if(item.key.startsWith('spans/')){
        const combined=union([...(item.value||[]),...pending.value]);apply(s,item.key,combined);
        pending.value=combined;pending.base=item.rev;
      }else if(sent.some(x=>x.key===item.key&&reply.accepted.includes(x.mutation)))pending.base=item.rev;
    }
    for(const item of reply.conflicts){
      c.remote[item.key]={rev:item.rev,value:item.value};
      const pending=c.pending[item.key];if(!pending)continue;
      if(item.key.startsWith('baseline/')){apply(s,item.key,item.value);delete c.pending[item.key];}
      else{c.conflicts[item.key]={mine:pending.value,theirs:item.value};apply(s,item.key,item.value);delete c.pending[item.key];}
    }
    // Acknowledgements may precede their position in a paginated pull.
    for(const item of sent)if(reply.accepted.includes(item.mutation)&&c.pending[item.key]?.mutation===item.mutation)delete c.pending[item.key];
    c.cursor=reply.cursor;c.epoch=reply.epoch;
  }
  root.BlinkSync={accountState,observe,span,rebuild,collect,apply,recordBatch,records,objects,union};
})(typeof window==='undefined'?globalThis:window);
