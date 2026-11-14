// CE-20-04: the real relay process goes down and comes back. Its database stays; no launcher that wipes data is used.
import { button, fact, fieldInput, goStep, openApp, readStore, registerItem, shot, startItem, statusState, waitForStatus } from './flows.mjs';
import { VehicleFixture } from './vehicle-fixture.mjs';

const item = (name) => ({ name, shape: 'BOX', x: '120', y: '130', z: '140', error: '2', source: 'REPEATED_TAPE', rotation: 'FLAT_ONLY', stackable: 'false', priority: '3' });

export async function runCe20(rec, env) {
  const grade = 'actual_browser+real_local_relay+simulated_vehicle';
  const group = 'CE-20-04';
  const shots = [];

  await rec.scenario(group, '실제 릴레이 중단 → 대기·로컬 복원 → 재시작 → 전경 재전송', grade, async () => {
    const context = await env.browser.newContext({ viewport: { width: 360, height: 780 } });
    let page = await context.newPage();
    const vehicle = await VehicleFixture.pair(env.stack.base);
    try {
      await openApp(page, vehicle.qrUrl);
      await waitForStatus(page, 'paired');
      await registerItem(page, item('중단 전 수신'));
      await waitForStatus(page, 'received');
      await vehicle.waitForDraft();

      const stopped = await env.stack.relay.stop();
      rec.truthy('릴레이 프로세스 실제 종료', stopped.code !== null || stopped.signal !== null, stopped);
      const refused = await fetch(`${env.stack.base}/healthz`).then(() => 'answered', () => 'refused');
      rec.check('포트가 실제로 닫힘', 'refused', refused);

      await registerItem(page, item('중단 중 작성 A'));
      await page.locator('[data-failure-code="CONNECTION_FAILED"]').first().waitFor({ timeout: 15_000 });
      rec.truthy('전송함에 대기 표시', await statusState(page, 'pending').first().isVisible());
      rec.check('수신 항목은 그대로(성공도 실패도 아님)', 1, await statusState(page, 'received').count());
      rec.check('차량 반영 표시 없음', 0, await statusState(page, 'accepted').count());
      shots.push(await shot(page, 'ce20-relay-down'));

      // A half-written draft while the relay is down, then closing and reopening the app.
      await startItem(page, item('이어 쓸 초안'));
      await waitForStatus(page, 'save-saved');
      const reopened = await context.newPage();
      await page.close();
      page = reopened;
      await openApp(page, env.next.origin);
      rec.check('재열기: 대기 항목 유지', 1, (await readStore(page, 'outbox')).filter((entry) => entry.status === 'pending' || entry.status === 'sending').length);
      await goStep(page, 'P1');
      rec.check('재열기: 작성 초안 복원', '이어 쓸 초안', await fieldInput(page, 'name').inputValue());
      await goStep(page, 'P3');
      await waitForStatus(page, 'pending');
      shots.push(await shot(page, 'ce20-reopened'));

      // Relay back, same port and database.
      await env.stack.relay.start();
      await button(page, '지금 다시 시도').click();
      await page.waitForFunction(() => document.querySelectorAll('[data-state="received"]').length === 2, null, { timeout: 30_000 });
      rec.check('수동 재시도 뒤 릴레이에 정확히 2건', ['중단 전 수신', '중단 중 작성 A'], (await vehicle.drafts()).map((draft) => draft.draft.payload.name));

      // Automatic foreground backoff, no user action this time.
      await env.stack.relay.stop();
      await goStep(page, 'P1');
      await fieldInput(page, 'name').fill('자동 재전송 항목');
      await fieldInput(page, 'shapeKind').selectOption('BOX');
      await button(page, '다음: 치수와 취급조건').click();
      const auto = item('자동 재전송 항목');
      await fieldInput(page, 'dimX').fill(auto.x);
      await fieldInput(page, 'dimY').fill(auto.y);
      await fieldInput(page, 'dimZ').fill(auto.z);
      await fieldInput(page, 'errorMm').fill(auto.error);
      await fieldInput(page, 'measurementSource').selectOption(auto.source);
      await fieldInput(page, 'rotationRule').selectOption(auto.rotation);
      await fieldInput(page, 'stackable').selectOption(auto.stackable);
      await fieldInput(page, 'removalPriority').fill(auto.priority);
      await fieldInput(page, 'confirmedDimensions').check();
      await fieldInput(page, 'dimensionsConsent').check();
      await button(page, '전송함에 넣기').click();
      await page.locator('[data-failure-code="CONNECTION_FAILED"]').first().waitFor({ timeout: 15_000 });
      await env.stack.relay.start();
      const startedWaiting = Date.now();
      await page.waitForFunction(() => document.querySelectorAll('[data-state="received"]').length === 3, null, { timeout: 45_000 });
      rec.note('auto_retry_ms_after_restart', Date.now() - startedWaiting);
      rec.check('사용자 조작 없이 재전송됨', 3, (await vehicle.drafts()).length);

      // The received draft survived the restart in PostgreSQL and the vehicle can still answer it.
      const first = (await vehicle.drafts()).find((draft) => draft.draft.payload.name === '중단 전 수신');
      rec.check('재시작 뒤 ack 가능', 200, (await vehicle.accept(first, 9)).status);
      await waitForStatus(page, 'accepted', 40_000);
      rec.truthy('차량 revision 표시', (await fact(page, 'vehicle-revision').textContent()) !== null);
      rec.check('service worker 없음', 0, await page.evaluate(async () => (await navigator.serviceWorker.getRegistrations()).length));
    } finally {
      await context.close();
      await env.stack.relay.start().catch(() => undefined);
    }
  });

  await rec.scenario(group, '브라우저 오프라인: 화면 이동·저장·복원, online 이벤트로 재개', grade, async () => {
    const context = await env.browser.newContext({ viewport: { width: 390, height: 844 } });
    const page = await context.newPage();
    const vehicle = await VehicleFixture.pair(env.stack.base);
    try {
      await openApp(page, vehicle.qrUrl);
      await waitForStatus(page, 'paired');
      await context.setOffline(true);
      await waitForStatus(page, 'offline');
      await registerItem(page, item('오프라인 등록'));
      await waitForStatus(page, 'pending');
      rec.check('오프라인 전송 시도 없음(실패 기록 없음)', null, (await readStore(page, 'outbox'))[0].lastFailure);
      shots.push(await shot(page, 'ce20-offline-P3'));
      await startItem(page, item('오프라인 초안'));
      await waitForStatus(page, 'save-saved');
      shots.push(await shot(page, 'ce20-offline-P1'));
      await goStep(page, 'P2');
      await goStep(page, 'P3');
      await goStep(page, 'P1');
      rec.check('오프라인 화면 이동 뒤 초안 유지', '오프라인 초안', await fieldInput(page, 'name').inputValue());

      const reload = await page.reload({ timeout: 5_000 }).then(() => 'loaded', () => 'failed');
      const reloadedApp = reload === 'loaded' && await page.locator('main[data-loaded]').count() > 0;
      rec.check('오프라인 새로고침은 service worker 없이 보장하지 않음(문서화된 한계)', false, reloadedApp);
      rec.note('offline_reload', { attempt: reload, app_rendered: reloadedApp });

      await context.setOffline(false);
      await openApp(page, env.next.origin);
      await goStep(page, 'P3');
      await waitForStatus(page, 'received', 20_000);
      rec.check('온라인 복귀 뒤 전송', ['오프라인 등록'], (await vehicle.drafts()).map((draft) => draft.draft.payload.name));
      await goStep(page, 'P1');
      rec.check('온라인 복귀 뒤 초안 복원', '오프라인 초안', await fieldInput(page, 'name').inputValue());

      // Stays open through offline and back: the online event alone resumes it.
      await context.setOffline(true);
      await goStep(page, 'P1');
      await fieldInput(page, 'name').fill('online 이벤트 항목');
      const auto = item('x');
      await button(page, '다음: 치수와 취급조건').click();
      await fieldInput(page, 'dimX').fill(auto.x);
      await fieldInput(page, 'dimY').fill(auto.y);
      await fieldInput(page, 'dimZ').fill(auto.z);
      await fieldInput(page, 'errorMm').fill(auto.error);
      await fieldInput(page, 'measurementSource').selectOption(auto.source);
      await fieldInput(page, 'rotationRule').selectOption(auto.rotation);
      await fieldInput(page, 'stackable').selectOption(auto.stackable);
      await fieldInput(page, 'removalPriority').fill(auto.priority);
      await fieldInput(page, 'confirmedDimensions').check();
      await fieldInput(page, 'dimensionsConsent').check();
      await button(page, '전송함에 넣기').click();
      await waitForStatus(page, 'pending');
      await context.setOffline(false);
      await page.waitForFunction(() => document.querySelectorAll('[data-state="received"]').length === 2, null, { timeout: 20_000 });
      rec.check('online 이벤트만으로 재개', 2, (await vehicle.drafts()).length);
    } finally {
      await context.close();
    }
  });

  return shots;
}

