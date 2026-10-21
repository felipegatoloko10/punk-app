import {test} from 'node:test';
import assert from 'node:assert/strict';
process.env.NEON_AUTH_BASE_URL='https://auth.example.test/auth';
process.env.NEON_AUTH_JWKS_URL='https://auth.example.test/auth/.well-known/jwks.json';
const {handle}=await import('../src/index.js');

test('unauthenticated requests do not touch the database',async()=>{
  let calls=0; const db={query:async()=>{calls++;throw Error('unexpected');}};
  const r=await handle(new Request('https://api.test/computers'),db);
  assert.equal(r.status,401);assert.equal(calls,0);
});
test('forged JWT is rejected before database work',async()=>{
  const r=await handle(new Request('https://api.test/computers',{headers:{Authorization:'Bearer forged'}}),
    {query:async()=>{throw Error('unexpected database call');}},async()=>{throw Error('invalid signature');});
  assert.equal(r.status,401);
});
test('list always scopes to verified identity, never caller-supplied owner',async()=>{
  const queries=[];const db={query:async(sql,args)=>{queries.push({sql,args});return {rows:sql.includes('returning hits')?[{hits:1}]:[]};}};
  const r=await handle(new Request('https://api.test/computers?owner_id=victim',{headers:{Authorization:'Bearer valid'}}),db,async()=>({sub:'actual-owner'}));
  assert.equal(r.status,200);assert.deepEqual(queries[1].args,['actual-owner']);
});
test('authenticated quota returns retry header',async()=>{
  const r=await handle(new Request('https://api.test/computers',{headers:{Authorization:'Bearer valid'}}),
    {query:async()=>({rows:[{hits:91}]})},async()=>({sub:'actual-owner'}));
  assert.equal(r.status,429);assert.equal(r.headers.get('Retry-After'),'60');
});
