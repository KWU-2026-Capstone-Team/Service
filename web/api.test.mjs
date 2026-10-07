import test from 'node:test';
import assert from 'node:assert/strict';
import {Api, BASE, errorText, TERMINAL} from './api.mjs';
function storage() { const values=new Map(); return {getItem:k=>values.get(k),setItem:(k,v)=>values.set(k,v),removeItem:k=>values.delete(k)}; }
function response(data,status=200) { return new Response(status===204?null:JSON.stringify(data),{status,headers:{'Content-Type':'application/json'}}); }
test('connect creates one token and reload reuses it',async()=>{
  const s=storage(); let sessions=0;
  const fetcher=async(url,options)=>{
    assert.ok(url.startsWith(BASE)); assert.equal(options.credentials,'omit');
    if(url.endsWith('/sessions')) { sessions++; return response({token:'test-token'}); }
    if(url.includes('/analyses')) assert.equal(options.headers.Authorization,'Bearer test-token');
    return response({inference_mode:'demo'});
  };
  await new Api(s,fetcher).connect(); await new Api(s,fetcher).connect(); assert.equal(sessions,1);
});
test('401 renews expired token but network failure does not',async()=>{
  const s=storage(); s.setItem(`deeptector.session:${BASE}`,'old');
  const api=new Api(s,async url=>url.includes('/analyses')?response({error:{message:'expired'}},401):response({token:'new'}));
  await api.connect(); assert.equal(api.token,'new');
  const offline=new Api(s,async()=>{throw new TypeError('offline');});
  await assert.rejects(()=>offline.connect()); assert.equal(s.getItem(offline.key),'new');
});
test('multipart boundary is browser owned; delete 204; readable errors',async()=>{
  const api=new Api(storage(),async(url,options)=>{
    if(options.method==='DELETE') return response(null,204);
    assert.ok(options.body instanceof FormData); assert.equal(options.headers['Content-Type'],undefined);
    return response({id:'test',status:'QUEUED'},202);
  });
  assert.equal((await api.upload(new Blob(['video']))).status,'QUEUED');
  assert.equal(await api.call('/api/v1/analyses/test',{method:'DELETE'}),null);
  assert.match(errorText({code:'GAME_DATASET_NOT_READY'}),/데이터/);
  assert.equal(TERMINAL.has('QUEUED'),false); assert.equal(TERMINAL.has('FAILED'),true);
});
test('rejects absolute artifact URLs so token stays local',async()=>{
  const api=new Api(storage(),()=>{throw new Error('must not fetch');});
  await assert.rejects(()=>api.call('https://example.com/api/v1/x'),/Invalid API path/);
});
