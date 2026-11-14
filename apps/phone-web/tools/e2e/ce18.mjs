// CE-18-03: duplicates, lost responses, resends, expiry and revocation as the outbox shows them.
import { button, fact, goStep, openApp, readStore, registerItem, shot, statusState, waitForStatus } from './flows.mjs';
import { VehicleFixture } from './vehicle-fixture.mjs';

const patchEntry = (page, patch) => page.evaluate((changes) => new Promise((resolve, reject) => {
  const open = indexedDB.open('cargo-phone');
  open.onsuccess = () => {
    const db = open.result;
    const tx = db.transaction('outbox', 'readwrite');
    const store = tx.objectStore('outbox');
    const all = store.getAll();
    all.onsuccess = () => {
      for (const entry of all.result) {
        if (changes.name) entry.draft.payload.name = changes.name;
        if (changes.consentVersion) entry.consent.consentVersion = changes.consentVersion;
        entry.status = 'pending';
        entry.nextAttemptAt = new Date().toISOString();
        store.put(entry);
      }
    };
    tx.oncomplete = () => { db.close(); resolve(); };
    tx.onerror = () => reject(tx.error);
  };
}), patch);

const paired = async (env, stack = env.stack, next = env.next, viewport = { width: 390, height: 844 }) => {
  const context = await env.browser.newContext({ viewport });
  const page = await context.newPage();
  const vehicle = await VehicleFixture.pair(stack.base);
  await openApp(page, vehicle.qrUrl);
  await waitForStatus(page, 'paired');
  return { context, page, vehicle };
};

export async function runCe18(rec, env) {
  const grade = 'actual_browser+real_local_relay+simulated_vehicle';
  const group = 'CE-18-03';
  const shots = [];

  await rec.scenario(group, '응답 유실 뒤 재전송: 같은 키로 한 번만 반영', grade, async () => {
    const { context, page, vehicle } = await paired(env);
    try {
      const statuses = [];
      await page.route('**/phone/v1/drafts', async (route) => {
        if (route.request().method() !== 'POST') return route.continue();
        const response = await route.fetch();
        const body = await response.json();
        statuses.push({ status: response.status(), duplicate: body.duplicate });
        if (statuses.length === 1) return route.abort('connectionreset'); // The relay stored it; the phone never hears.
        return route.fulfill({ response });
      });
      await registerItem(page);
      await waitForStatus(page, 'received', 30_000);
      rec.check('첫 응답 유실, 재전송은 동일 영수증', [{ status: 201, duplicate: false }, { status: 200, duplicate: true }], statuses);
      rec.check('릴레이의 초안은 1건', 1, (await vehicle.drafts()).length);
    } finally {
      await context.close();
    }
  });

  await rec.scenario(group, '전송 중 새로고침: 안전하게 대기로 돌아가 재전송', grade, async () => {
    const { context, page, vehicle } = await paired(env);
    try {
      let release;
      const held = new Promise((resolve) => { release = resolve; });
      let posts = 0;
      await page.route('**/phone/v1/drafts', async (route) => {
        if (route.request().method() !== 'POST') return route.continue();
        posts += 1;
        if (posts === 1) await held;
        return route.continue().catch(() => undefined);
      });
      await registerItem(page);
      await waitForStatus(page, 'sending');
      shots.push(await shot(page, 'ce18-sending'));
      const reload = page.reload();
      release();
      await reload;
      await page.waitForSelector('main[data-loaded="true"]');
      await waitForStatus(page, 'received', 30_000);
      rec.check('재시작 뒤에도 초안 1건', 1, (await vehicle.drafts()).length);
    } finally {
      await context.close();
    }
  });

  await rec.scenario(group, '중복 ack: 차량 결과가 바뀌지 않음', grade, async () => {
    const { context, page, vehicle } = await paired(env);
    try {
      await registerItem(page);
      await waitForStatus(page, 'received');
      const draft = await vehicle.waitForDraft();
      const first = await vehicle.accept(draft, 7);
      const replay = await vehicle.accept(draft, 7);
      rec.check('ack 재전송은 최초 내용 재생', [200, 200, true], [first.status, replay.status, replay.body.replayed]);
      const different = await vehicle.accept(draft, 8);
      rec.check('다른 ack는 거부', 409, different.status);
      await waitForStatus(page, 'accepted');
      rec.truthy('수락 revision 유지', (await fact(page, 'vehicle-revision').textContent()).startsWith('7'));
      await button(page, '상태 새로고침').click();
      await page.reload();
      await page.waitForSelector('main[data-loaded="true"]');
      await waitForStatus(page, 'accepted');
      rec.truthy('재열기 뒤 유지', (await fact(page, 'vehicle-revision').textContent()).startsWith('7'));
      rec.check('확정 저장소 revision', [7], (await readStore(page, 'confirmed')).map((item) => item.acceptedRevision));
    } finally {
      await context.close();
    }
  });

  await rec.scenario(group, 'QR 재사용·동시 사용: 한쪽만 성공, 나머지는 새 QR 안내', grade, async () => {
    const vehicle = await VehicleFixture.pair(env.stack.base);
    const contexts = await Promise.all([env.browser.newContext(), env.browser.newContext()]);
    try {
      const pages = await Promise.all(contexts.map((context) => context.newPage()));
      await Promise.all(pages.map((page) => openApp(page, vehicle.qrUrl)));
      const outcome = await Promise.all(pages.map(async (page) => {
        await page.locator('[data-state="paired"], [data-state="claim-PAIRING_TOKEN_CONSUMED"]').first().waitFor({ timeout: 15_000 });
        return page.locator('[data-state="paired"], [data-state="claim-PAIRING_TOKEN_CONSUMED"]').first().getAttribute('data-state');
      }));
      rec.check('정확히 하나만 연결', ['claim-PAIRING_TOKEN_CONSUMED', 'paired'], [...outcome].sort());
      shots.push(await shot(pages[outcome.indexOf('claim-PAIRING_TOKEN_CONSUMED')], 'ce18-qr-consumed'));
    } finally {
      await Promise.all(contexts.map((context) => context.close()));
    }
  });

  await rec.scenario(group, '차량 해제: 수신 항목은 미확인, 미전송은 해제, 재전송 거부', grade, async () => {
    const { context, page, vehicle } = await paired(env);
    try {
      await registerItem(page, { name: '수신된 항목', shape: 'BOX', x: '100', y: '100', z: '100', error: '1', source: 'USER_TAPE', rotation: 'ANY24', stackable: 'true', priority: '1' });
      await waitForStatus(page, 'received');
      await vehicle.waitForDraft();
      await context.setOffline(true);
      await registerItem(page, { name: '미전송 항목', shape: 'BOX', x: '200', y: '100', z: '100', error: '1', source: 'USER_TAPE', rotation: 'ANY24', stackable: 'true', priority: '1' });
      await waitForStatus(page, 'pending');
      rec.check('해제 응답', 200, (await vehicle.revoke()).status);
      await context.setOffline(false);
      await waitForStatus(page, 'revoked', 30_000);
      await waitForStatus(page, 'unknown', 30_000);
      rec.check('차량 반영으로 표시하지 않음', 0, await statusState(page, 'accepted').count());
      rec.truthy('미확인 문구', await page.getByText('반영되었는지 안 되었는지 알 수 없으니').first().isVisible());
      shots.push(await shot(page, 'ce18-revoked-unknown'));
      await goStep(page, 'P0');
      await waitForStatus(page, 'pairing-revoked');

      // New QR, explicit repack. The old results are not wiped and no acceptance is claimed.
      const second = await VehicleFixture.pair(env.stack.base);
      await page.goto(second.qrUrl);
      await waitForStatus(page, 'paired');
      await goStep(page, 'P3');
      await waitForStatus(page, 'repack-offer');
      shots.push(await shot(page, 'ce18-repack-offer'));
      rec.check('새 연결 뒤에는 새 QR을 다시 요구하지 않음', 0, await page.getByText('새 QR을 스캔').locator('visible=true').and(page.locator('[data-failure-code]')).count());
      rec.check('사용자 확인 전 새 연결로 안 감', 0, (await second.drafts()).length);
      await button(page, '지금 연결로 옮겨 보내기').click();
      await page.waitForFunction(() => document.querySelectorAll('[data-ds="status"][data-state="received"]').length >= 2, null, { timeout: 30_000 });
      const moved = await second.drafts();
      rec.check('두 항목 모두 새 pairing으로', [second.pairingId, second.pairingId], moved.map((draft) => draft.draft.pairingId));
      rec.truthy('미확인 재전송 경고 유지', await page.locator('[data-resent-unknown]').first().isVisible());
      rec.check('차량 반영 주장 없음', 0, await statusState(page, 'accepted').count());
    } finally {
      await context.close();
    }
  });

  await rec.scenario(group, '폰에서 연결 해제: 차량이 알게 되고 미전송 사본 유지', grade, async () => {
    const { context, page, vehicle } = await paired(env);
    try {
      await context.setOffline(true);
      await registerItem(page);
      await context.setOffline(false);
      await page.route('**/phone/v1/drafts', (route) => route.abort());
      await goStep(page, 'P0');
      await button(page, '연결 해제').click();
      await waitForStatus(page, 'pairing-revoked');
      await page.waitForTimeout(100);
      rec.check('릴레이의 pairing도 해제', 403, (await vehicle.status()).status);
      await goStep(page, 'P3');
      await waitForStatus(page, 'revoked');
      rec.check('미전송 사본 보존', 1, (await readStore(page, 'outbox')).length);
      rec.check('폰 자격 삭제', '', (await readStore(page, 'pairing'))[0].phoneToken);
      await goStep(page, 'P0');
      await button(page, '이 기기의 데이터 모두 지우기').click();
      await button(page, '정말 모두 지우기').click();
      await waitForStatus(page, 'not-paired');
      rec.check('로컬 삭제는 명시 확인 뒤 전부', [0, 0], [(await readStore(page, 'outbox')).length, (await readStore(page, 'pairing')).length]);
    } finally {
      await context.close();
    }
  });

  await rec.scenario(group, '같은 키 다른 내용: 멈춘 뒤 새 수정 번호로만 복구', grade, async () => {
    const { context, page, vehicle } = await paired(env);
    try {
      await registerItem(page);
      await waitForStatus(page, 'received');
      await patchEntry(page, { name: '변조된 내용' });
      await page.reload();
      await page.waitForSelector('main[data-loaded="true"]');
      await waitForStatus(page, 'blocked', 15_000);
      rec.check('오류 코드', 'DRAFT_CONTENT_CONFLICT', await page.locator('[data-failure-code]').first().getAttribute('data-failure-code'));
      shots.push(await shot(page, 'ce18-content-conflict'));
      await button(page, '새 수정 번호로 다시 보내기').click();
      await waitForStatus(page, 'received', 15_000);
      const latest = await vehicle.waitForDraft((draft) => draft.clientRevision === 2);
      rec.check('새 수정 번호로 전달', 2, latest.clientRevision);
    } finally {
      await context.close();
    }
  });

  await rec.scenario(group, '더 새로운 수정본 존재: 최신+1로 복구', grade, async () => {
    const { context, page, vehicle } = await paired(env);
    try {
      await context.setOffline(true);
      await registerItem(page);
      const [entry] = await readStore(page, 'outbox');
      const [pairing] = await readStore(page, 'pairing');
      const newer = { ...entry.draft, clientRevision: 5 };
      const direct = await fetch(`${env.stack.base}/phone/v1/drafts`, { method: 'POST', headers: { authorization: `Bearer ${pairing.phoneToken}`, 'content-type': 'application/json' }, body: JSON.stringify({ consent: entry.consent, draft: newer }) });
      rec.check('다른 탭 역할의 새 수정본', 201, direct.status);
      await context.setOffline(false);
      await waitForStatus(page, 'blocked', 15_000);
      rec.check('오류 코드', 'STALE_CLIENT_REVISION', await page.locator('[data-failure-code]').first().getAttribute('data-failure-code'));
      await button(page, '새 수정 번호로 다시 보내기').click();
      await vehicle.waitForDraft((draft) => draft.clientRevision === 6);
      rec.check('최신+1', [6], (await vehicle.drafts()).map((draft) => draft.clientRevision));
    } finally {
      await context.close();
    }
  });

  await rec.scenario(group, '지원하지 않는 동의 버전: 자동 재시도 없음', grade, async () => {
    const { context, page } = await paired(env);
    try {
      await context.setOffline(true);
      await registerItem(page);
      await patchEntry(page, { consentVersion: 'transfer-consent-v0' });
      let posts = 0;
      await page.route('**/phone/v1/drafts', (route) => { posts += 1; return route.continue(); });
      await context.setOffline(false);
      await page.reload();
      await page.waitForSelector('main[data-loaded="true"]');
      await waitForStatus(page, 'blocked', 15_000);
      rec.check('오류 코드', 'CONSENT_VERSION_UNSUPPORTED', await page.locator('[data-failure-code]').first().getAttribute('data-failure-code'));
      await page.waitForTimeout(5_000);
      rec.check('claim 응답에 없는 버전은 전송 자체를 안 함, 재시도 없음', 0, posts);
      rec.truthy('고치기 복구 제공', await button(page, '내용 열어 고치기').isVisible());
    } finally {
      await context.close();
    }
  });

  await rec.scenario(group, '실제 TTL 경계: QR·초안·연결 만료', 'actual_browser+real_local_relay_short_ttl_test_config+simulated_vehicle', async () => {
    const shortStack = env.shortStack;
    // QR older than its 2s TTL.
    {
      const vehicle = await VehicleFixture.pair(shortStack.base);
      await new Promise((resolve) => setTimeout(resolve, 2_300));
      const context = await env.browser.newContext();
      const page = await context.newPage();
      try {
        await openApp(page, vehicle.qrUrl);
        await waitForStatus(page, 'claim-PAIRING_EXPIRED');
        shots.push(await shot(page, 'ce18-qr-expired'));
      } finally {
        await context.close();
      }
    }
    // Draft TTL 3s, credential TTL 8s, on the real clock.
    const { context, page } = await paired(env, shortStack, env.shortNext);
    try {
      const claimedAt = Date.now();
      const item = (name) => ({ name, shape: 'BOX', x: '100', y: '100', z: '100', error: '1', source: 'USER_TAPE', rotation: 'ANY24', stackable: 'true', priority: '1' });
      await registerItem(page, item('곧 만료될 초안'));
      await waitForStatus(page, 'received');
      await waitForStatus(page, 'expired', 15_000);
      shots.push(await shot(page, 'ce18-draft-expired'));
      rec.check('만료를 차량 반영으로 표시하지 않음', 0, await statusState(page, 'accepted').count());

      const wait = 6_000 - (Date.now() - claimedAt);
      if (wait > 0) await page.waitForTimeout(wait);
      await registerItem(page, item('수신 뒤 자격 만료'));
      await waitForStatus(page, 'received', 5_000);
      await context.setOffline(true);
      await registerItem(page, item('미전송 자격 만료'));
      await waitForStatus(page, 'pending', 5_000);
      // Still offline when the credential's own clock runs out: the phone must conclude this without the relay.
      await waitForStatus(page, 'unknown', 12_000);
      rec.truthy('자격 만료 뒤 수신 항목은 미확인', await statusState(page, 'unknown').first().isVisible());
      await page.waitForFunction(() => document.querySelectorAll('[data-ds="status"][data-state="expired"]').length === 2, null, { timeout: 5_000 });
      rec.check('미전송 항목은 만료 표시(초안+미전송 2건)', 2, await statusState(page, 'expired').count());
      await context.setOffline(false);
      shots.push(await shot(page, 'ce18-credential-expired'));
      await goStep(page, 'P0');
      await waitForStatus(page, 'pairing-expired');
      rec.note('ttl_config', 'services/relay/test/fixtures/config-short-ttl.json: pairing 2s, draft 3s, credential 8s. 제품 TTL 300/86400/86400이 아닌 경계 시험 값.');
    } finally {
      await context.close();
    }
  });

  return shots;
}

