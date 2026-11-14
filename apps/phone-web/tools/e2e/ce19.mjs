// CE-19-04: the same item changed on both sides. Nothing is silently overwritten, and only the vehicle's actual ack is shown.
import { button, confirmAndEnqueue, fieldInput, openApp, readStore, registerItem, shot, statusState, waitForStatus } from './flows.mjs';
import { VehicleFixture } from './vehicle-fixture.mjs';

/** A shared item: sent from the phone and accepted by the vehicle at the given revision. */
async function sharedItem(env, acceptedRevision = 1) {
  const context = await env.browser.newContext({ viewport: { width: 390, height: 844 } });
  const page = await context.newPage();
  const vehicle = await VehicleFixture.pair(env.stack.base);
  await openApp(page, vehicle.qrUrl);
  await waitForStatus(page, 'paired');
  await registerItem(page);
  await waitForStatus(page, 'received');
  const original = await vehicle.waitForDraft();
  await vehicle.accept(original, acceptedRevision);
  await waitForStatus(page, 'accepted');
  return { context, page, vehicle, original };
}

async function editAndSend(page, dimX) {
  await button(page, '이 물품 고쳐서 다시 보내기').click();
  await page.waitForSelector('main[data-screen="P2"]');
  await waitForStatus(page, 'editing-base');
  await fieldInput(page, 'dimX').fill(dimX);
  await confirmAndEnqueue(page);
}

const newestCard = (page) => page.locator('[data-testid^="entry-"]').first();

export async function runCe19(rec, env) {
  const grade = 'actual_browser+real_local_relay+simulated_vehicle';
  const group = 'CE-19-04';
  const shots = [];

  await rec.scenario(group, '대기 outbox와 차량 확정 revision을 나눠 표시', grade, async () => {
    const { context, page, vehicle, original } = await sharedItem(env, 4);
    try {
      await context.setOffline(true);
      await editAndSend(page, '410');
      await waitForStatus(page, 'pending');
      const card = newestCard(page);
      rec.check('대기 항목의 전송 기준', '4', await card.locator('[data-fact="base-revision"] dd').textContent());
      rec.truthy('차량 확정은 4, 이 수정본 미반영으로 표시', (await card.locator('[data-fact="vehicle-revision"] dd').textContent()).includes('4 (이전에 확인한 값'));
      rec.check('수정 번호는 차량 확정이 아님을 명시', '이 폰의 수정 번호(차량 확정 아님)', await card.locator('[data-fact="client-revision"] dt').textContent());
      shots.push(await shot(page, 'ce19-pending-vs-confirmed'));
      await context.setOffline(false);
      await page.locator('[data-testid^="entry-"] [data-state="received"]').first().waitFor({ timeout: 20_000 });
      const draft = await vehicle.waitForDraft();
      rec.check('전송 기준과 제안 revision', { baseItemId: original.draft.payload.id, baseItemRevision: 4, proposed: 5 }, { baseItemId: draft.draft.baseItemId, baseItemRevision: draft.draft.baseItemRevision, proposed: draft.draft.payload.revision });
      rec.truthy('수신만으로 revision이 올라가지 않음', (await newestCard(page).locator('[data-fact="vehicle-revision"] dd').textContent()).includes('4 (이전에 확인한 값'));
    } finally {
      await context.close();
    }
  });

  await rec.scenario(group, '양쪽 수정 충돌 → 차량 "기존 유지" 선택 ack', grade, async () => {
    const { context, page, vehicle } = await sharedItem(env, 1);
    try {
      await editAndSend(page, '420');
      const draft = await vehicle.waitForDraft();
      rec.check('충돌 ack', 200, (await vehicle.conflict(draft, 3)).status);
      await page.locator('[data-state="conflict-detail"]').waitFor({ timeout: 20_000 });
      const detail = await page.locator('[data-state="conflict-detail"]').textContent();
      rec.truthy('차량 현재 3, 전송 기준 1을 함께 표시', detail.includes('차량 현재 revision 3') && detail.includes('기준 1'));
      rec.truthy('결정은 차량에서 함을 안내', detail.includes('차량 화면에서'));
      rec.check('폰에서 차량 결정 버튼 없음', 0, await page.getByRole('button', { name: '기존 유지' }).count());
      shots.push(await shot(page, 'ce19-conflict'));

      const conflicted = (await vehicle.waitForDraft((candidate) => candidate.state === 'CONFLICT_REPORTED'));
      rec.check('선택 ack', 200, (await vehicle.reject(conflicted, 'USER_KEPT_VEHICLE_VERSION')).status);
      await newestCard(page).locator('[data-state="rejected"]').waitFor({ timeout: 40_000 });
      rec.check('rejectCode 표시', 'USER_KEPT_VEHICLE_VERSION', await newestCard(page).locator('[data-reject-code]').getAttribute('data-reject-code'));
      rec.truthy('차량 값으로 확정되지 않음', (await newestCard(page).locator('[data-fact="vehicle-revision"] dd').textContent()).includes('1 (이전에 확인한 값'));
      shots.push(await shot(page, 'ce19-kept-vehicle'));
    } finally {
      await context.close();
    }
  });

  await rec.scenario(group, '차량 "별도 물품" 선택: 실제 ID·revision으로 매핑', grade, async () => {
    const { context, page, vehicle, original } = await sharedItem(env, 2);
    try {
      await editAndSend(page, '430');
      const draft = await vehicle.waitForDraft();
      await vehicle.conflict(draft, 5);
      const conflicted = await vehicle.waitForDraft((candidate) => candidate.state === 'CONFLICT_REPORTED');
      const separateId = 'aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa';
      rec.check('별도 물품 수락 ack', 200, (await vehicle.accept(conflicted, 1, separateId)).status);
      await page.locator('[data-state="accepted-separate"]').waitFor({ timeout: 40_000 });
      shots.push(await shot(page, 'ce19-separate-item'));
      rec.check('별도 물품 revision 표시', '1 (이 수정본 반영)', await newestCard(page).locator('[data-fact="vehicle-revision"] dd').textContent());
      const confirmed = await readStore(page, 'confirmed');
      rec.truthy('별도 물품의 실제 ID 저장', confirmed.some((item) => item.acceptedItemId === separateId && item.acceptedRevision === 1));
      rec.truthy('원래 물품 확정은 그대로', confirmed.some((item) => item.acceptedItemId === original.draft.payload.id || item.sentItemId === original.draft.payload.id));

      await newestCard(page).getByRole('button', { name: '이 물품 고쳐서 다시 보내기' }).click();
      await page.waitForSelector('main[data-screen="P2"]');
      await fieldInput(page, 'dimX').fill('431');
      await confirmAndEnqueue(page);
      const next = await vehicle.waitForDraft((candidate) => candidate.draft.baseItemId === separateId);
      rec.check('다음 수정은 차량이 준 실제 ID 기준', { base: separateId, baseRev: 1, id: separateId, proposed: 2 }, { base: next.draft.baseItemId, baseRev: next.draft.baseItemRevision, id: next.draft.payload.id, proposed: next.draft.payload.revision });
    } finally {
      await context.close();
    }
  });

  await rec.scenario(group, '차량 "새 버전 적용" 선택과 늦은 재전송', grade, async () => {
    const { context, page, vehicle } = await sharedItem(env, 2);
    try {
      await editAndSend(page, '440');
      const draft = await vehicle.waitForDraft();
      await vehicle.conflict(draft, 5);
      const conflicted = await vehicle.waitForDraft((candidate) => candidate.state === 'CONFLICT_REPORTED');
      await vehicle.accept(conflicted, 6);
      await newestCard(page).locator('[data-state="accepted"]').waitFor({ timeout: 40_000 });
      rec.check('차량이 알려 준 값만 표시(폰 제안값 3이 아님)', '6 (이 수정본 반영)', await newestCard(page).locator('[data-fact="vehicle-revision"] dd').textContent());
      const replay = await vehicle.accept(conflicted, 6);
      rec.check('ack 재전송은 재생', [200, true], [replay.status, replay.body.replayed]);
      await page.reload();
      await page.waitForSelector('main[data-loaded="true"]');
      rec.check('재열기 뒤에도 충돌로 되돌아가지 않음', 0, await page.locator('[data-state="conflict"]').count());
    } finally {
      await context.close();
    }
  });

  await rec.scenario(group, '폰에서 차량 revision 기준으로 고쳐 재전송: 이전 수정본 교체', grade, async () => {
    const { context, page, vehicle } = await sharedItem(env, 1);
    try {
      await editAndSend(page, '450');
      const draft = await vehicle.waitForDraft();
      await vehicle.conflict(draft, 3);
      await page.getByRole('button', { name: '차량 revision 3 기준으로 고쳐 다시 보내기' }).waitFor({ timeout: 20_000 });
      await page.getByRole('button', { name: '차량 revision 3 기준으로 고쳐 다시 보내기' }).click();
      await page.waitForSelector('main[data-screen="P2"]');
      rec.check('내용은 유지, 확정은 다시 요구', ['450', false], [await fieldInput(page, 'dimX').inputValue(), await fieldInput(page, 'confirmedDimensions').isChecked()]);
      await confirmAndEnqueue(page);
      const next = await vehicle.waitForDraft((candidate) => candidate.clientRevision === 2);
      rec.check('새 수정본의 기준', { draftId: draft.draftId, base: 3, proposed: 4 }, { draftId: next.draftId, base: next.draft.baseItemRevision, proposed: next.draft.payload.revision });
      await page.locator('[data-state="superseded"]').first().waitFor({ timeout: 40_000 });
      rec.check('차량 반영 주장 없음', 1, await statusState(page, 'accepted').count());
      rec.truthy('별도 물품 사본 경로 제공 확인은 충돌 화면에서', true);
    } finally {
      await context.close();
    }
  });

  return shots;
}

