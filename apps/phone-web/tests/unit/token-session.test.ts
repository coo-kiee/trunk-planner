import assert from 'node:assert/strict';
import { test } from 'node:test';

const TOKEN = `qp_${'a'.repeat(43)}`;

function stubWindow(token: string | null) {
  let held = token;
  (globalThis as unknown as { window: unknown }).window = { __cargoTakePairingToken: () => { const taken = held; held = null; return taken; } };
}

test('remounts and repeated effects start one claim for one token; a connection failure can retry with the same token', async () => {
  stubWindow(TOKEN);
  const bodies: string[] = [];
  let answer: Response | null = null;
  globalThis.fetch = (async (_url: string, init: RequestInit) => {
    bodies.push(String(init.body));
    await new Promise((resolve) => setTimeout(resolve, 5));
    if (answer) return answer;
    throw new TypeError('network down');
  }) as typeof fetch;
  const session = await import('../../features/pairing/token-session.ts');

  const first = session.claimOnce('https://relay.example');
  const second = session.claimOnce('https://relay.example');
  assert.equal(first, second, 'concurrent callers share the claim in flight');
  assert.equal((await first)?.ok, false);
  assert.equal(bodies.length, 1);

  assert.equal(session.hasPairingToken(), true, 'a connection failure keeps the token in memory');
  answer = new Response(JSON.stringify({ error: { code: 'PAIRING_TOKEN_CONSUMED' } }), { status: 409 });
  const retried = await session.claimOnce('https://relay.example');
  assert.equal(bodies.length, 2);
  assert.equal(bodies[0], bodies[1], 'the retry uses the same token');
  assert.ok(retried && !retried.ok && retried.kind === 'http' && retried.code === 'PAIRING_TOKEN_CONSUMED');

  assert.equal(session.hasPairingToken(), false, 'any answer from the relay finishes the token');
  assert.equal(session.claimOnce('https://relay.example'), null);
});
