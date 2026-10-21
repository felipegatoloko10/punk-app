import { Pool } from 'pg';
import { attachDatabasePool } from '@neon/functions';
import { createRemoteJWKSet, jwtVerify } from 'jose';
import { createHash, randomBytes } from 'node:crypto';

const pool = new Pool({ connectionString: process.env.DATABASE_URL, max: 3, connectionTimeoutMillis: 5000 });
attachDatabasePool(pool);
const issuer = new URL(process.env.NEON_AUTH_BASE_URL).origin;
const jwks = createRemoteJWKSet(new URL(process.env.NEON_AUTH_JWKS_URL));
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const hash = s => createHash('sha256').update(s).digest('hex');
const response = (data, status = 200) => Response.json(data, {status, headers:{'Cache-Control':'no-store'}});

export async function handle(request, db = pool, verify = async token => (await jwtVerify(token, jwks, {issuer})).payload) {
  const path = new URL(request.url).pathname.replace(/\/$/, '');
  if (path === '/health' && request.method === 'GET') return response({ok:true, service:'punk-accounts', version:1});
  const auth = request.headers.get('authorization') || '';
  if (!auth.startsWith('Bearer ') || auth.length > 16384) return response({error:'Unauthorized'}, 401);
  let owner;
  try { owner = (await verify(auth.slice(7))).sub; } catch { return response({error:'Unauthorized'}, 401); }
  if (!owner) return response({error:'Unauthorized'}, 401);
  // Authenticated quota, persisted across isolates. Anonymous requests never wake Postgres.
  const budget = await db.query(`insert into punk_account_limits values($1,now(),1)
    on conflict(owner_id) do update set window_start=case when punk_account_limits.window_start<now()-interval '1 minute' then now() else punk_account_limits.window_start end,
    hits=case when punk_account_limits.window_start<now()-interval '1 minute' then 1 else punk_account_limits.hits+1 end returning hits`, [owner]);
  if (budget.rows[0].hits > 90) return new Response('{"error":"Too many requests"}', {status:429,headers:{'Retry-After':'60','Content-Type':'application/json','Cache-Control':'no-store'}});
  if (request.method === 'GET' && path === '/computers') {
    const {rows} = await db.query('select id,name,endpoint,local_host,local_port,fingerprint,last_seen from punk_computers where owner_id=$1 order by last_seen desc limit 100',[owner]);
    return response(rows);
  }
  if (request.method !== 'POST') return response({error:'Not found'},404);
  const reader = request.body?.getReader();
  if (!reader) return response({error:'Invalid request'},400);
  const chunks=[]; let size=0;
  while (true) { const {done,value}=await reader.read(); if(done)break; size+=value.length;
    if(size>8192){await reader.cancel();return response({error:'Body too large'},413);} chunks.push(value); }
  let b;
  try { b=JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { return response({error:'Invalid request'},400); }
  if (!b || typeof b !== 'object') return response({error:'Invalid request'},400);
  if (path === '/rpc/punk_register_computer') {
    if (!uuid.test(b.p_id) || typeof b.p_name!=='string' || b.p_name.length<1 || b.p_name.length>100 ||
      typeof b.p_endpoint!=='string' || (b.p_endpoint!=='' && !/^https:\/\/[a-z0-9-]+\.trycloudflare\.com$/.test(b.p_endpoint)) ||
      typeof b.p_local_host!=='string' || !/^[A-Za-z0-9._:-]{0,200}$/.test(b.p_local_host) ||
      !Number.isInteger(b.p_local_port) || b.p_local_port<1 || b.p_local_port>65535 || !/^[a-f0-9]{64}$/.test(b.p_fingerprint)) return response({error:'Invalid computer'},400);
    const r=await db.query(`insert into punk_computers(id,owner_id,name,endpoint,local_host,local_port,fingerprint) values($1,$2,$3,$4,$5,$6,$7)
      on conflict(id) do update set name=excluded.name,endpoint=excluded.endpoint,local_host=excluded.local_host,local_port=excluded.local_port,
      fingerprint=excluded.fingerprint,last_seen=now() where punk_computers.owner_id=$2 returning id`,
      [b.p_id,owner,b.p_name,b.p_endpoint,b.p_local_host,b.p_local_port,b.p_fingerprint]);
    return r.rowCount ? response({ok:true}) : response({error:'Forbidden'},403);
  }
  if (!uuid.test(b.p_computer_id)) return response({error:'Invalid computer'},400);
  if (path === '/rpc/punk_create_connect_grant') {
    if (!uuid.test(b.p_installation_id)) return response({error:'Invalid installation'},400);
    const ticket = randomBytes(32).toString('hex');
    const r = await db.query(`insert into punk_connect_grants(digest,computer_id,owner_id,installation_id,expires_at)
      select $1,id,$2,$3,now()+interval '90 seconds' from punk_computers where id=$4 and owner_id=$2
      on conflict(owner_id,installation_id) do update set digest=excluded.digest,computer_id=excluded.computer_id,expires_at=excluded.expires_at returning digest`,
      [hash(ticket),owner,b.p_installation_id,b.p_computer_id]);
    return r.rowCount ? response(ticket) : response({error:'Forbidden'},403);
  }
  if (path === '/rpc/punk_consume_connect_grant') {
    if (typeof b.p_ticket!=='string' || !/^[a-f0-9]{64}$/.test(b.p_ticket)) return response({error:'Invalid ticket'},400);
    const r=await db.query(`delete from punk_connect_grants where digest=$1 and computer_id=$2 and owner_id=$3 and expires_at>now() returning installation_id`, [hash(b.p_ticket),b.p_computer_id,owner]);
    return r.rowCount ? response(r.rows[0].installation_id) : response({error:'Forbidden'},403);
  }
  return response({error:'Not found'},404);
}

export default { async fetch(request) {
  try { return await handle(request); } catch { return response({error:'Service unavailable'},503); }
}};
