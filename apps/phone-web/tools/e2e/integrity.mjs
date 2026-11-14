// Stored data that is damaged, and tabs that disagree. Originals are preserved and nothing is silently lost.
import { button, DEFAULT_ITEM, fieldInput, fillMeasurements, goStep, openApp, readStore, registerItem, shot, startItem, statusState, waitForStatus } from './flows.mjs';
import { VehicleFixture } from './vehicle-fixture.mjs';

const writeRecords = (page, writes) => page.evaluate((list) => new Promise((resolve, reject) => {
  const open = indexedDB.open('cargo-phone');
  open.onerror = () => reject(open.error);
  open.onsuccess = () => {
    const db = open.result;
    const tx = db.transaction([...new Set(list.map((write) => write.store))], 'readwrite');
    for (const write of list) {
      if (write.key === undefined) tx.objectStore(write.store).put(write.value);
      else tx.objectStore(write.store).put(write.value, write.key);
    }
    tx.oncomplete = () => { db.close(); resolve(); };
    tx.onerror = () => reject(tx.error);
  };
}), writes);

const reopen = async (page) => {
  await page.reload();
  await page.waitForSelector('main[data-loaded="true"]');
};

/** Copies an intact stored entry with one kind of damage, under its own key. */
function damagedCopy(entry, index, change) {
  const copy = structuredClone(entry);
  copy.draft.draftId = `${String(index).padStart(8, '0')}-2222-4222-8222-222222222222`;
  copy.key = `${copy.draft.draftId}:1`;
  change(copy);
  return copy;
}

export async function runIntegrity(rec, env) {
  const grade = 'actual_browser+injected_corrupt_IDB+real_local_relay+simulated_vehicle';
  const group = 'integrity';
  const shots = [];

  await rec.scenario(group, 'PW-R01 부분 손상 outbox: 전체 중단 없이 격리·보존, 정상 항목 계속', grade, async () => {
    const context = await env.browser.newContext({ viewport: { width: 360, height: 780 } });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', (error) => errors.push(error.message));
    const vehicle = await VehicleFixture.pair(env.stack.base);
    try {
      await openApp(page, vehicle.qrUrl);
      await waitForStatus(page, 'paired');
      // Sends fail for now, so the intact entry stays pending across the reloads below.
      await context.route('**/phone/v1/drafts', (route) => route.abort());
      await registerItem(page, { ...DEFAULT_ITEM, name: '정상 항목' });
      const [entry] = await readStore(page, 'outbox');
      const damages = [
        (copy) => { delete copy.draft.payload.measuredDimensionsMm; },
        (copy) => { delete copy.draft.payload.shapeProxy; },
        (copy) => { copy.status = 'nonsense'; },
        (copy) => { delete copy.consent; },
        (copy) => { copy.status = 'accepted'; },
        (copy) => { copy.status = 'conflict'; },
        (copy) => { copy.receipt = 'yes'; },
      ];
      const copies = damages.map((change, index) => damagedCopy(entry, index + 1, change));
      await writeRecords(page, copies.map((value) => ({ store: 'outbox', value })));
      await reopen(page);
      await goStep(page, 'P3');
      await waitForStatus(page, 'storage-CORRUPT');
      rec.check('화면 오류 없음', [], errors);
      rec.check('프레임워크 오류 화면 없음', 'P3', await page.locator('main').getAttribute('data-screen'));
      rec.check('손상 항목은 카드로 그리지 않음, 정상 항목만', ['정상 항목'], await page.locator('[data-testid^="entry-"] h3').allTextContents());
      rec.truthy('한국어 복구 안내', (await statusState(page, 'storage-CORRUPT').textContent()).includes(`읽지 못한 기록 ${copies.length}개`));
      shots.push(await shot(page, 'integrity-corrupt-outbox'));
      const stored = await readStore(page, 'outbox');
      rec.check('손상 원본 보존', copies.length + 1, stored.length);
      rec.check('손상 원본 내용 그대로', copies.map((copy) => copy.key).sort(), stored.filter((value) => value.key !== entry.key).map((value) => value.key).sort());

      // Reopening again still reaches the recovery screen, and only the intact entry is sent.
      await reopen(page);
      await goStep(page, 'P3');
      await waitForStatus(page, 'storage-CORRUPT');
      await context.unroute('**/phone/v1/drafts');
      await button(page, '지금 다시 시도').click();
      await waitForStatus(page, 'received', 20_000);
      rec.check('정상 항목만 전송', ['정상 항목'], (await vehicle.drafts()).map((draft) => draft.draft.payload.name));

      await button(page, '읽을 수 없는 기록만 지우기').click();
      rec.check('확인 전에는 지우지 않음', copies.length + 1, (await readStore(page, 'outbox')).length);
      await button(page, '손상 기록 지우기 확인').click();
      await statusState(page, 'storage-CORRUPT').waitFor({ state: 'detached' });
      rec.check('명시 확인 뒤 손상 기록만 삭제', [entry.key], (await readStore(page, 'outbox')).map((value) => value.key));
    } finally {
      await context.close();
    }
  });

  await rec.scenario(group, 'PW-R01 손상 초안·연결·확정 기록: 격리 보존, 덮어쓰기와 잘못된 자격 사용 없음', grade, async () => {
    const context = await env.browser.newContext({ viewport: { width: 390, height: 844 } });
    const page = await context.newPage();
    const errors = [];
    const authHeaders = [];
    page.on('pageerror', (error) => errors.push(error.message));
    page.on('request', (request) => { if (request.url().includes('/phone/v1/')) authHeaders.push(request.headers().authorization ?? null); });
    try {
      await openApp(page, env.next.origin);
      await startItem(page);
      await waitForStatus(page, 'save-saved');
      const [editor] = await readStore(page, 'editor');
      const brokenEditor = { ...editor, state: { ...editor.state, dimX: 400, shapeKind: 'SPHERE' } };
      const brokenPairing = { pairingId: 'p', phoneToken: 12345, state: 'ACTIVE' };
      const brokenConfirmed = { acceptedItemId: 'broken-confirmed', acceptedRevision: 'seven' };
      await writeRecords(page, [
        { store: 'editor', key: 'current', value: brokenEditor },
        { store: 'pairing', key: 'current', value: brokenPairing },
        { store: 'confirmed', value: brokenConfirmed },
      ]);
      await reopen(page);
      await waitForStatus(page, 'storage-CORRUPT');
      await waitForStatus(page, 'not-paired');
      rec.check('화면 오류 없음', [], errors);
      rec.check('손상 자격으로 요청하지 않음', [], authHeaders);
      const quarantine = await readStore(page, 'quarantine');
      rec.check('손상 초안·연결 원본을 격리 저장소에 보존', [brokenEditor, brokenPairing].map((value) => JSON.stringify(value)).sort(), quarantine.map((record) => JSON.stringify(record.value)).sort());
      rec.check('손상 확정 기록은 제자리 보존', ['broken-confirmed'], (await readStore(page, 'confirmed')).map((value) => value.acceptedItemId));
      shots.push(await shot(page, 'integrity-corrupt-singletons'));

      await goStep(page, 'P1');
      await button(page, '새 물품 시작').click();
      await fieldInput(page, 'name').fill('격리 뒤 새 작성');
      await waitForStatus(page, 'save-saved');
      rec.check('격리 뒤 새 작성 저장 가능', '격리 뒤 새 작성', (await readStore(page, 'editor'))[0].state.name);
      rec.check('새 작성이 격리 원본을 덮지 않음', 2, (await readStore(page, 'quarantine')).length);
    } finally {
      await context.close();
    }
  });

  await rec.scenario(group, '예상 못 한 화면 오류도 한국어 안내로 표시하고 데이터 보존', grade, async () => {
    const context = await env.browser.newContext({ viewport: { width: 360, height: 780 } });
    const page = await context.newPage();
    const vehicle = await VehicleFixture.pair(env.stack.base);
    try {
      await openApp(page, vehicle.qrUrl);
      await waitForStatus(page, 'paired');
      await goStep(page, 'P1');
      await page.evaluate(() => { Date.prototype.toLocaleString = () => { throw new Error('injected render failure'); }; });
      await goStep(page, 'P0');
      await waitForStatus(page, 'render-error');
      rec.check('한국어 오류 화면', 'error', await page.locator('main').getAttribute('data-screen'));
      shots.push(await shot(page, 'integrity-render-error'));
      rec.check('저장 데이터 보존', 1, (await readStore(page, 'pairing')).length);
    } finally {
      await context.close();
    }
  });

  await rec.scenario(group, 'PW-R02 오래된 탭의 enqueue: 최신 초안 삭제 없이 충돌 안내', grade, async () => {
    const context = await env.browser.newContext({ viewport: { width: 390, height: 844 } });
    const stale = await context.newPage();
    try {
      await openApp(stale, env.next.origin);
      await startItem(stale);
      await fillMeasurements(stale);
      await fieldInput(stale, 'confirmedDimensions').check();
      await fieldInput(stale, 'dimensionsConsent').check();
      await waitForStatus(stale, 'save-saved');

      const fresh = await context.newPage();
      await openApp(fresh, env.next.origin);
      await goStep(fresh, 'P1');
      await fieldInput(fresh, 'name').fill('다른 탭에서 저장한 새 이름');
      await waitForStatus(fresh, 'save-saved');
      const before = await readStore(fresh, 'editor');

      await button(stale, '전송함에 넣기').click();
      await waitForStatus(stale, 'enqueue-failed');
      rec.check('전송함에 들어가지 않음', 0, (await readStore(stale, 'outbox')).length);
      rec.check('최신 초안 그대로 보존', before, await readStore(stale, 'editor'));
      rec.check('옛 탭에 저장됨 표시 없음', 0, await statusState(stale, 'save-saved').count());
      rec.check('옛 탭은 P2에 머묾', 'P2', await stale.locator('main').getAttribute('data-screen'));
      shots.push(await shot(stale, 'integrity-stale-tab-enqueue'));

      await button(stale, '다른 탭의 최신 내용 불러오기').click();
      await statusState(stale, 'enqueue-failed').waitFor({ state: 'detached' });
      await goStep(stale, 'P1');
      await stale.waitForFunction(() => document.querySelector('[name="name"]')?.value === '다른 탭에서 저장한 새 이름');
      await goStep(stale, 'P2');
      await button(stale, '전송함에 넣기').click();
      await stale.waitForSelector('main[data-screen="P3"]');
      rec.check('복구 뒤 최신 내용으로 1건', ['다른 탭에서 저장한 새 이름'], (await readStore(stale, 'outbox')).map((entry) => entry.draft.payload.name));
      rec.check('그 뒤에야 초안 정리', 0, (await readStore(stale, 'editor')).length);
    } finally {
      await context.close();
    }
  });

  await rec.scenario(group, 'PW-R02 두 탭의 중복 enqueue와 끝난 초안의 늦은 저장', grade, async () => {
    const context = await env.browser.newContext({ viewport: { width: 390, height: 844 } });
    const first = await context.newPage();
    try {
      await openApp(first, env.next.origin);
      await startItem(first);
      await fillMeasurements(first);
      await fieldInput(first, 'confirmedDimensions').check();
      await fieldInput(first, 'dimensionsConsent').check();
      await waitForStatus(first, 'save-saved');
      const second = await context.newPage();
      await openApp(second, env.next.origin);
      await goStep(second, 'P2');
      await waitForStatus(second, 'save-saved');

      await button(first, '전송함에 넣기').click();
      await first.waitForSelector('main[data-screen="P3"]');
      await button(second, '전송함에 넣기').click();
      await waitForStatus(second, 'enqueue-failed');
      rec.check('중복 enqueue 없음', 1, (await readStore(second, 'outbox')).length);

      // A new item starts in the first tab. The second tab's late typing must not overwrite it (no id reuse).
      await button(first, '새 물품 등록').click();
      await fieldInput(first, 'name').fill('새 물품');
      await waitForStatus(first, 'save-saved');
      await fieldInput(second, 'dimX').fill('999');
      await waitForStatus(second, 'save-other-tab');
      rec.check('끝난 초안의 늦은 저장이 새 초안을 덮지 않음', '새 물품', (await readStore(second, 'editor'))[0].state.name);
    } finally {
      await context.close();
    }
  });

  return shots;
}
