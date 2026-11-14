// P0~P3 as a person uses them, at each viewport, against the real relay. The vehicle role is a protocol fixture.
import { VIEWPORTS } from '../lib/browser.mjs';
import { auditLayout, button, confirmAndEnqueue, DEFAULT_ITEM, fact, fieldInput, fillMeasurements, openApp, readStore, shot, startItem, waitForStatus } from './flows.mjs';
import { EXIF_MARKER, inspectPixels, jpegSegments, makeExifPhoto, ORIGINAL_FILE_NAME } from './photo-fixtures.mjs';
import { VehicleFixture } from './vehicle-fixture.mjs';

const LONG_NAME = 'ㅁ'.repeat(118) + '🧳🧳';

export async function runJourneys(rec, env) {
  const grade = 'actual_browser+real_local_relay+simulated_vehicle';
  const exifPhoto = await makeExifPhoto(env.browser);
  const screenshots = [];

  for (const viewport of VIEWPORTS) {
    await rec.scenario('journey', `P0~P3 전체 입력·사진·재열기 @${viewport.name}`, grade, async () => {
      const context = await env.browser.newContext({ viewport: { width: viewport.width, height: viewport.height } });
      const page = await context.newPage();
      const problems = [];
      page.on('console', (message) => { if (message.type() === 'error') problems.push(message.text()); });
      page.on('pageerror', (error) => problems.push(error.message));
      const capture = async (name) => {
        screenshots.push({ viewport: viewport.name, file: await shot(page, `${viewport.name}-${name}`) });
        const layout = await auditLayout(page);
        rec.check(`${name}: 가로 스크롤 없음`, false, layout.horizontalScroll);
        rec.check(`${name}: 화면 밖 요소 없음`, [], layout.overflowing);
        rec.check(`${name}: 44px 미만 터치 대상 없음`, [], layout.smallTargets);
      };
      try {
        // P0 without a QR: honest "not connected" state.
        await openApp(page, env.next.origin);
        await waitForStatus(page, 'not-paired');
        rec.check('QR 없는 P0는 미연결 표시', 'P0', await page.locator('main').getAttribute('data-screen'));
        await capture('P0-not-paired');
        const link = page.getByRole('link', { name: '전체 안내 보기' });
        await link.click();
        await page.waitForSelector('main[data-screen="privacy"]');
        rec.check('개인정보 링크 도착', '/privacy', new URL(page.url()).pathname);
        await capture('privacy');
        await page.getByRole('link', { name: '물품 등록으로 돌아가기' }).click();
        await page.waitForSelector('main[data-loaded="true"]');

        // P0 through a real QR URL.
        const vehicle = await VehicleFixture.pair(env.stack.base);
        await page.goto(vehicle.qrUrl); // Same document: only the fragment changes, as when a QR opens in the app's own tab.
        await waitForStatus(page, 'paired');
        rec.check('열린 탭의 새 QR도 fragment 제거', '', new URL(page.url()).hash);
        await capture('P0-paired');

        // P1: required-field errors, then photo failures that never block the flow.
        await button(page, '물품 등록하기').click();
        await button(page, '새 물품 시작').click();
        await button(page, '다음: 치수와 취급조건').click();
        rec.check('이름·형상 없이 다음 불가', 'P1', await page.locator('main').getAttribute('data-screen'));
        rec.check('이름 오류가 필드에 연결됨', 'true', await fieldInput(page, 'name').getAttribute('aria-invalid'));
        await capture('P1-errors');

        const picker = page.locator('input[type=file][name=photo]');
        await picker.setInputFiles({ name: 'note.txt', mimeType: 'text/plain', buffer: Buffer.from('not an image') });
        await waitForStatus(page, 'photo-failed');
        await picker.setInputFiles({ name: 'broken.jpg', mimeType: 'image/jpeg', buffer: Buffer.from('broken') });
        await page.waitForTimeout(150);
        await waitForStatus(page, 'photo-failed');
        await capture('P1-photo-failed');
        rec.check('사진 실패 뒤에도 입력 가능', true, await fieldInput(page, 'name').isEnabled());
        rec.check('미리보기 없으면 사진 동의 불가', true, await fieldInput(page, 'photoConsent').isDisabled());

        // A valid photo: nothing about the dimensions may change.
        await picker.setInputFiles({ name: ORIGINAL_FILE_NAME, mimeType: 'image/jpeg', buffer: exifPhoto });
        await page.locator('[data-photo-preview]').waitFor({ timeout: 20_000 });
        const withPhotoConsent = viewport.name !== 'phone390';
        if (withPhotoConsent) await fieldInput(page, 'photoConsent').check();
        await fieldInput(page, 'name').fill(`${LONG_NAME}x`);
        await fieldInput(page, 'shapeKind').selectOption('BOX');
        await button(page, '다음: 치수와 취급조건').click();
        rec.truthy('121자 이름 거부', await page.getByText('이름은 120자까지').first().isVisible());
        await fieldInput(page, 'name').fill(LONG_NAME);
        await capture('P1-photo-long-name');
        await button(page, '다음: 치수와 취급조건').click();

        // P2: a photo filled nothing; no default values exist.
        await page.waitForSelector('main[data-screen="P2"]');
        rec.check('사진 뒤에도 치수 기본값 없음', ['', '', ''], [await fieldInput(page, 'dimX').inputValue(), await fieldInput(page, 'dimY').inputValue(), await fieldInput(page, 'dimZ').inputValue()]);
        rec.check('미확정 상태에서 전송 버튼 비활성', true, await button(page, '전송함에 넣기').isDisabled());
        await capture('P2-empty');

        // Saved state survives closing the tab mid-way.
        await fieldInput(page, 'dimX').fill('0');
        await fieldInput(page, 'dimY').fill('300');
        await fieldInput(page, 'dimZ').fill('10001');
        await fieldInput(page, 'confirmedDimensions').check();
        await fieldInput(page, 'dimensionsConsent').check();
        await button(page, '전송함에 넣기').click();
        await page.waitForSelector('[role=alert]');
        rec.check('경계 밖 치수 오류 표시', 'true', await fieldInput(page, 'dimX').getAttribute('aria-invalid'));
        rec.check('치수 오류 시 전송함 이동 안 함', 'P2', await page.locator('main').getAttribute('data-screen'));
        await capture('P2-errors');

        await fieldInput(page, 'dimX').fill('400');
        rec.check('치수 변경은 확정을 해제함', false, await fieldInput(page, 'confirmedDimensions').isChecked());
        rec.check('확정 해제 후 다시 비활성', true, await button(page, '전송함에 넣기').isDisabled());
        await fieldInput(page, 'dimZ').fill('200');
        await fieldInput(page, 'errorMm').fill('5');
        await fieldInput(page, 'measurementSource').selectOption('USER_TAPE');
        await fieldInput(page, 'rotationRule').selectOption('KEEP_UPRIGHT');
        await fieldInput(page, 'stackable').selectOption('true');
        await fieldInput(page, 'removalPriority').fill('2');
        await waitForStatus(page, 'save-saved');

        const reopened = await context.newPage();
        await page.close();
        await openApp(reopened, env.next.origin);
        rec.check('재열기: P2로 복원', 'P2', await reopened.locator('main').getAttribute('data-screen'));
        rec.check('재열기: 입력값 복원', ['400', '300', '200', '5'], [await fieldInput(reopened, 'dimX').inputValue(), await fieldInput(reopened, 'dimY').inputValue(), await fieldInput(reopened, 'dimZ').inputValue(), await fieldInput(reopened, 'errorMm').inputValue()]);
        const focused = await (async () => {
          await fieldInput(reopened, 'dimX').focus();
          await reopened.keyboard.press('Tab');
          return reopened.evaluate(() => {
            const style = getComputedStyle(document.activeElement);
            return { name: document.activeElement.getAttribute('name'), outlineStyle: style.outlineStyle, outlineColor: style.outlineColor, width: style.outlineWidth };
          });
        })();
        rec.check('키보드 Tab 순서와 포커스 표시', { name: 'dimY', outlineStyle: 'solid' }, { name: focused.name, outlineStyle: focused.outlineStyle });
        await capture_on(reopened, capture, 'P2-filled', viewport, screenshots, rec);

        await confirmAndEnqueue(reopened);
        await waitForStatus(reopened, 'received');
        await capture_on(reopened, capture, 'P3-received', viewport, screenshots, rec);
        rec.check('수신 상태의 차량 확정 revision은 없음', '아직 없음', await fact(reopened, 'vehicle-revision').textContent());

        // Relay facts: what the vehicle can actually fetch.
        const draft = await vehicle.waitForDraft();
        rec.check('차량 수신 치수', { x: 400, y: 300, z: 200 }, draft.draft.payload.measuredDimensionsMm);
        rec.check('확정 플래그는 사용자가 확정해 true', true, draft.draft.payload.confirmedDimensions);
        rec.check('이름 코드포인트 120', 120, [...draft.draft.payload.name].length);
        const payloadText = JSON.stringify(draft);
        rec.check('전송 내용에 원본 파일명 없음', false, payloadText.includes(ORIGINAL_FILE_NAME));

        if (withPhotoConsent) {
          const photo = await vehicle.photo(draft);
          rec.check('사진 수신', 200, photo.status);
          rec.check('photoRef는 내용 hash', `sha256:${photo.headers.get('x-photo-sha256')}`, draft.draft.payload.photoRef);
          rec.check('EXIF 없음(APP1 마커)', false, jpegSegments(photo.buffer).includes('0xe1'));
          rec.check('GPS 표식 바이트 없음', false, photo.buffer.includes(EXIF_MARKER));
          rec.truthy('2MiB 이하', photo.buffer.length <= 2 * 1024 * 1024, photo.buffer.length);
          const pixels = await inspectPixels(env.browser, photo.buffer);
          rec.check('Orientation 6 반영 + 긴변 2048 축소', { width: 683, height: 2048 }, { width: pixels.width, height: pixels.height });
          rec.truthy('회전 뒤 빨간 모서리가 오른쪽 위', pixels.topRight[0] > 150 && pixels.topRight[2] < 100 && pixels.topLeft[0] < 100, pixels);
          rec.note('photo_sent', { bytes: photo.buffer.length, segments: jpegSegments(photo.buffer) });
        } else {
          rec.check('동의 없으면 차량에 사진 없음', null, draft.photo);
          rec.check('동의 없으면 photoRef 없음', undefined, draft.draft.payload.photoRef);
          rec.check('사진 경로도 404', 404, (await vehicle.photo(draft)).status);
        }
        const stored = await readStore(reopened, 'photos');
        rec.check('기기에 저장된 사진에 원본 파일명 없음', false, JSON.stringify(stored.map((photo) => ({ ...photo, blob: photo.blob?.name ?? null }))).includes(ORIGINAL_FILE_NAME));

        // Vehicle decides: only now does the phone say so.
        rec.check('차량 수락 ack', 200, (await vehicle.accept(draft, 7)).status);
        await waitForStatus(reopened, 'accepted');
        rec.truthy('차량 확정 revision 표시', (await fact(reopened, 'vehicle-revision').textContent()).startsWith('7'));
        await capture_on(reopened, capture, 'P3-accepted', viewport, screenshots, rec);
        rec.check('콘솔 오류 없음(CSP 위반 포함)', [], problems.filter((text) => !text.includes('net::ERR')));
        await vehicle.revoke();
      } finally {
        await context.close();
      }
    });
  }

  await rec.scenario('journey', 'ㄱ자 형상과 재전송 수정본', grade, async () => {
    const context = await env.browser.newContext({ viewport: { width: 390, height: 844 } });
    const page = await context.newPage();
    try {
      const vehicle = await VehicleFixture.pair(env.stack.base);
      await openApp(page, vehicle.qrUrl);
      await waitForStatus(page, 'paired');
      const lItem = { ...DEFAULT_ITEM, name: 'ㄱ자 선반', shape: 'L_SHAPE', x: '300', y: '100', z: '300', legX: '100', footZ: '100' };
      await startItem(page, lItem);
      await fillMeasurements(page, { ...lItem, legX: '300' });
      await fieldInput(page, 'confirmedDimensions').check();
      await fieldInput(page, 'dimensionsConsent').check();
      await button(page, '전송함에 넣기').click();
      await page.waitForSelector('[role=alert]');
      rec.check('전체 폭 이상 기둥 폭 거부', 'true', await fieldInput(page, 'legX').getAttribute('aria-invalid'));
      await fieldInput(page, 'legX').fill('100');
      await confirmAndEnqueue(page);
      await waitForStatus(page, 'received');
      const first = await vehicle.waitForDraft();
      rec.check('ㄱ자 proxy 두 상자(차량 core fixture와 동일 구조)', [
        { origin: { x: 0, y: 0, z: 0 }, size: { x: 100, y: 100, z: 300 } },
        { origin: { x: 100, y: 0, z: 0 }, size: { x: 200, y: 100, z: 100 } },
      ], first.draft.payload.shapeProxy);

      // Editing before the vehicle answered: a new clientRevision, and the old one is superseded at the relay.
      await button(page, '내용 고쳐 새 수정본 보내기').click();
      await page.waitForSelector('main[data-screen="P2"]');
      rec.check('재전송은 확정을 다시 요구', false, await fieldInput(page, 'confirmedDimensions').isChecked());
      await fieldInput(page, 'dimY').fill('110');
      await confirmAndEnqueue(page);
      await page.locator('[data-ds="status"][data-state="superseded"]').first().waitFor({ timeout: 20_000 });
      const second = await vehicle.waitForDraft((draft) => draft.clientRevision === 2);
      rec.check('같은 draft의 새 수정 번호', [first.draftId, 2], [second.draftId, second.clientRevision]);
      rec.check('차량 목록에는 최신 수정본만', 1, (await vehicle.drafts()).length);
    } finally {
      await context.close();
    }
  });
  await rec.scenario('journey', '대기 중인 사진 전송 철회: 사진 없는 새 수정본만 전달', grade, async () => {
    const context = await env.browser.newContext({ viewport: { width: 390, height: 844 } });
    const page = await context.newPage();
    try {
      const vehicle = await VehicleFixture.pair(env.stack.base);
      await openApp(page, vehicle.qrUrl);
      await waitForStatus(page, 'paired');
      await startItem(page);
      await page.locator('input[type=file][name=photo]').setInputFiles({ name: ORIGINAL_FILE_NAME, mimeType: 'image/jpeg', buffer: exifPhoto });
      await page.locator('[data-photo-preview]').waitFor({ timeout: 20_000 });
      await fieldInput(page, 'photoConsent').check();
      await fillMeasurements(page);
      await context.setOffline(true);
      await confirmAndEnqueue(page);
      await waitForStatus(page, 'pending');
      rec.check('철회 전 사진 표시', '함께 보냄(동의함)', await fact(page, 'photo').textContent());
      await button(page, '사진 빼고 다시 보내기').click();
      await page.locator('[data-state="superseded"]').waitFor();
      await context.setOffline(false);
      const draft = await vehicle.waitForDraft();
      rec.check('새 수정본만 전달', [2], (await vehicle.drafts()).map((candidate) => candidate.clientRevision));
      rec.check('photoRef 없음', undefined, draft.draft.payload.photoRef);
      rec.check('사진 동의 false', false, draft.consent.photoConsent);
      rec.check('사진 없음', null, draft.photo);
    } finally {
      await context.close();
    }
  });

  await rec.scenario('journey', '여러 탭: 저장 충돌 표시, 전송은 한 번만', grade, async () => {
    const context = await env.browser.newContext({ viewport: { width: 390, height: 844 } });
    const first = await context.newPage();
    try {
      const vehicle = await VehicleFixture.pair(env.stack.base);
      await openApp(first, vehicle.qrUrl);
      await waitForStatus(first, 'paired');
      await startItem(first);
      await waitForStatus(first, 'save-saved');
      const second = await context.newPage();
      await openApp(second, env.next.origin);
      await second.locator('[data-step="P1"]').click();
      await fieldInput(second, 'name').fill('두 번째 탭 수정');
      await waitForStatus(second, 'save-saved');
      await fieldInput(first, 'name').fill('첫 번째 탭의 늦은 수정');
      await waitForStatus(first, 'save-other-tab');
      await button(first, '다른 탭의 내용 불러오기').click();
      await first.locator('[name="name"]').and(first.locator('input')).evaluate((input) => new Promise((resolve) => {
        const poll = () => (input.value === '두 번째 탭 수정' ? resolve() : setTimeout(poll, 25));
        setTimeout(() => resolve(), 3000);
        poll();
      }));
      rec.check('덮어쓰지 않고 다른 탭 내용을 불러옴', '두 번째 탭 수정', await fieldInput(first, 'name').inputValue());

      await context.setOffline(true);
      await fillMeasurements(first, { ...DEFAULT_ITEM, name: '두 번째 탭 수정' });
      await confirmAndEnqueue(first);
      await second.locator('[data-step="P3"]').click();
      await second.locator('[data-state="pending"]').waitFor();
      await context.setOffline(false);
      await first.locator('[data-state="received"]').waitFor({ timeout: 20_000 });
      await second.locator('[data-state="received"]').waitFor({ timeout: 20_000 });
      rec.check('두 탭이 열려도 릴레이의 초안은 1건', 1, (await vehicle.drafts()).length);
    } finally {
      await context.close();
    }
  });
  return screenshots;
}

/** The page changes after reopening, so capture through a small adapter sharing the same audit. */
async function capture_on(page, _capture, name, viewport, screenshots, rec) {
  screenshots.push({ viewport: viewport.name, file: await shot(page, `${viewport.name}-${name}`) });
  const layout = await auditLayout(page);
  rec.check(`${name}: 가로 스크롤 없음`, false, layout.horizontalScroll);
  rec.check(`${name}: 화면 밖 요소 없음`, [], layout.overflowing);
  rec.check(`${name}: 44px 미만 터치 대상 없음`, [], layout.smallTargets);
}

