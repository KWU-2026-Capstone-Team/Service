// Runs against the local demo launcher only. Uploads and deletes generated test data.
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {Api, BASE, TERMINAL} from './api.mjs';
const values=new Map();
const storage={getItem:k=>values.get(k),setItem:(k,v)=>values.set(k,v),removeItem:k=>values.delete(k)};
const origin='https://kwu-2026-capstone-team.github.io';
const transport=(url,options)=>fetch(url,{...options,headers:{...options.headers,Origin:origin}});
const api=new Api(storage,transport);
assert.equal((await api.connect()).inference_mode,'demo');
const reused=new Api(storage,transport); await reused.connect(); assert.equal(reused.token,api.token);
const preflight=await fetch(BASE+'/api/v1/analyses',{method:'OPTIONS',headers:{Origin:origin,'Access-Control-Request-Method':'POST','Access-Control-Request-Headers':'authorization'}});
assert.equal(preflight.headers.get('access-control-allow-origin'),origin);
async function lifecycle(file,expected) {
  const form=new FormData(); form.append('video',file,'generated-test.mp4');
  const queued=await api.call('/api/v1/analyses',{method:'POST',body:form});
  let item=queued;
  for(let i=0;i<60&&!TERMINAL.has(item.status);i++) {
    await new Promise(resolve=>setTimeout(resolve,500));
    item=await api.call(`/api/v1/analyses/${queued.id}`);
  }
  assert.equal(item.status,expected,JSON.stringify(item.error));
  if(expected==='COMPLETED') { assert.equal(item.result.is_demo,true); assert.equal(item.result.policy.traffic_light,'RED'); assert.equal(item.artifacts.length,0); }
  assert.ok((await api.call('/api/v1/analyses')).items.some(x=>x.id===item.id));
  await api.call(`/api/v1/analyses/${item.id}`,{method:'DELETE'});
  await assert.rejects(()=>api.call(`/api/v1/analyses/${item.id}`),error=>error.status===404);
  console.log(`PASS: upload -> ${expected} -> history -> delete -> 404`);
}
await lifecycle(new Blob([await readFile(process.argv[2])],{type:'video/mp4'}),'COMPLETED');
await lifecycle(new Blob(['not a video'],{type:'video/mp4'}),'FAILED');
await assert.rejects(()=>api.call('/api/v1/games',{method:'POST',json:{rounds:1}}),e=>e.code==='GAME_DATASET_NOT_READY');
console.log('PASS: Pages CORS, session reuse, game dataset not-ready');
