// Real insecure and secure origins, token hygiene, CSP and storage failure handling.
import { button, fieldInput, goStep, openApp, readStore, registerItem, shot, startItem, statusState, waitForStatus } from './flows.mjs';
import { VehicleFixture } from './vehicle-fixture.mjs';

const TOKEN_TEXT = /(qp|vh|ph)_[A-Za-z0-9_-]{43}/;

async function dumpStorages(page) {
  return page.evaluate(async () => {
    const stores = {};
    const db = await new Promise((resolve, reject) => {
      const open = indexedDB.open('cargo-phone');
      open.onsuccess = () => resolve(open.result);
      open.onerror = () => reject(open.error);
    });
    for (const name of db.objectStoreNames) {
      stores[name] = await new Promise((resolve) => {
        const request = db.transaction(name).objectStore(name).getAll();
        request.onsuccess = () => resolve(request.result.map((value) => JSON.stringify(value, (key, inner) => (inner instanceof Blob ? '[blob]' : inner))));
      });
    }
    db.close();
    return { stores, local: JSON.stringify({ ...localStorage }), session: JSON.stringify({ ...sessionStorage }) };
  });
}

export async function runSecurity(rec, env) {
  const grade = 'actual_browser+real_local_relay+simulated_vehicle';
  const shots = [];

  await rec.scenario('security', '토큰 위생: URL·history·DOM·저장소·Referer·콘솔·서버로그', grade, async () => {
    const context = await env.browser.newContext({ viewport: { width: 390, height: 844 } });
    const page = await context.newPage();
    const requests = [];
    const consoleText = [];
    page.on('console', (message) => consoleText.push(message.text()));
    const wire = [];
    page.on('request', (request) => {
      requests.push({ url: request.url(), method: request.method(), auth: request.headers().authorization ?? null, body: request.postData() });
      // allHeaders() is what actually went on the wire; headers() is a provisional view that can show an empty Referer.
      wire.push(request.allHeaders().then((headers) => ({ url: request.url(), referer: headers.referer ?? null })).catch(() => null));
    });
    try {
      const vehicle = await VehicleFixture.pair(env.stack.base);
      await openApp(page, vehicle.qrUrl);
      await waitForStatus(page, 'paired');
      rec.check('주소창에 토큰 없음', false, page.url().includes(vehicle.qrToken));
      rec.check('history에서 제거(길이 유지, 현재 항목 fragment 없음)', '', await page.evaluate(() => location.hash));
      rec.check('DOM에 토큰 없음', false, (await page.content()).includes(vehicle.qrToken));
      const stored = await dumpStorages(page);
      rec.check('IDB에 QR 토큰 없음', false, JSON.stringify(stored).includes(vehicle.qrToken));
      rec.check('localStorage 비어 있음', '{}', stored.local);
      rec.check('sessionStorage 비어 있음', '{}', stored.session);
      const pairing = (await readStore(page, 'pairing'))[0];
      rec.truthy('폰 자격은 pairing 저장소 한 곳', pairing.phoneToken?.startsWith('ph_'));
      const elsewhere = Object.entries(stored.stores).filter(([name]) => name !== 'pairing').flatMap(([, values]) => values).join('');
      rec.check('폰 자격이 다른 저장소에 없음', false, elsewhere.includes(pairing.phoneToken));
      await registerItem(page);
      await waitForStatus(page, 'received');

      const claimRequests = requests.filter((request) => request.url.endsWith('/phone/v1/pairing/claim') && request.method === 'POST');
      rec.check('claim 요청 1회(이중 claim 없음)', 1, claimRequests.length);
      rec.check('요청 URL에 토큰 없음', [], requests.filter((request) => TOKEN_TEXT.test(decodeURIComponent(request.url))).map((request) => request.url));
      const sent = (await Promise.all(wire)).filter(Boolean);
      rec.truthy('Referer 검사 대상 존재', sent.length > 5, sent.length);
      rec.check('실제 전송 헤더에 Referer 없음', [], sent.filter((request) => request.referer !== null).map((request) => request.url));
      rec.check('QR 토큰은 claim 본문에만', 1, requests.filter((request) => request.body?.includes(vehicle.qrToken)).length);
      rec.check('제출 요청은 Authorization 사용', true, requests.filter((request) => request.url.endsWith('/phone/v1/drafts')).every((request) => request.auth === `Bearer ${pairing.phoneToken}`));
      rec.check('콘솔에 토큰 없음', [], consoleText.filter((text) => TOKEN_TEXT.test(text)));
      rec.check('Next 서버 출력에 토큰 없음', false, TOKEN_TEXT.test(env.next.output.join('')));
      rec.check('릴레이 출력에 토큰 없음', false, TOKEN_TEXT.test(env.stack.output.join('')));
    } finally {
      await context.close();
    }
  });

  await rec.scenario('security', 'fragment 제거는 hydration 없이도 동작', grade, async () => {
    const context = await env.browser.newContext();
    const page = await context.newPage();
    try {
      await page.route('**/_next/static/**', (route) => route.abort());
      const vehicle = await VehicleFixture.pair(env.stack.base);
      await page.goto(vehicle.qrUrl, { waitUntil: 'domcontentloaded' });
      rec.check('앱 JS 없이도 fragment 제거', '', await page.evaluate(() => location.hash));
      rec.check('claim 미발생: 토큰이 아직 유효', 'WAITING_CLAIM', (await vehicle.status()).body.state);
    } finally {
      await context.close();
    }
  });

  await rec.scenario('security', 'CSP·헤더·service worker 없음', grade, async () => {
    const context = await env.browser.newContext();
    const page = await context.newPage();
    try {
      const response = await page.goto(env.next.origin, { waitUntil: 'load' });
      const headers = response.headers();
      const csp = headers['content-security-policy'] ?? '';
      rec.truthy('CSP nonce 스크립트', /script-src 'self' 'nonce-[^']+' 'strict-dynamic'/.test(csp), csp);
      rec.check('unsafe-eval 없음', false, csp.includes('unsafe-eval'));
      rec.check('unsafe-inline 없음', false, csp.includes('unsafe-inline'));
      rec.truthy('worker-src none', csp.includes("worker-src 'none'"));
      rec.truthy('릴레이만 연결 허용', csp.includes(`connect-src 'self' ${env.stack.base}`));
      rec.check('Referrer-Policy', 'no-referrer', headers['referrer-policy']);
      rec.truthy('no-store', (headers['cache-control'] ?? '').includes('no-store'));
      await page.waitForSelector('main[data-loaded="true"]');
      rec.check('service worker 등록 0', 0, await page.evaluate(async () => (await navigator.serviceWorker.getRegistrations()).length));
      rec.check('service worker 등록 시도는 CSP가 차단', 'rejected', await page.evaluate(() => navigator.serviceWorker.register('/privacy').then(() => 'registered', () => 'rejected')));
      const nonceAgain = (await (await context.request.get(env.next.origin)).headers())['content-security-policy'];
      rec.check('요청마다 다른 nonce', false, nonceAgain === csp);
      const external = await page.evaluate(() => performance.getEntriesByType('resource').map((entry) => new URL(entry.name).origin));
      rec.check('제3자 리소스 없음', [env.next.origin], [...new Set(external)].filter((origin) => origin !== env.stack.base));
    } finally {
      await context.close();
    }
  });

  await rec.scenario('security', '실제 비보안 origin(isSecureContext=false): 차단과 토큰 미사용', grade, async () => {
    const context = await env.browser.newContext({ viewport: { width: 360, height: 780 } });
    const page = await context.newPage();
    const claims = [];
    page.on('request', (request) => { if (request.url().includes('/phone/v1/')) claims.push(request.url()); });
    try {
      const vehicle = await VehicleFixture.pair(env.stack.base);
      await openApp(page, `http://cargo-insecure.test:${env.next.port}/#pairing=${vehicle.qrToken}`);
      rec.check('실제 비보안 컨텍스트', { secure: false, subtle: false }, await page.evaluate(() => ({ secure: isSecureContext, subtle: Boolean(crypto.subtle) })));
      await waitForStatus(page, 'env-INSECURE_CONTEXT');
      await waitForStatus(page, 'claim-ENVIRONMENT_BLOCKED');
      rec.check('fragment 제거', '', await page.evaluate(() => location.hash));
      rec.check('릴레이 호출 없음', [], claims);
      rec.check('QR 토큰이 소비되지 않음', 'WAITING_CLAIM', (await vehicle.status()).body.state);
      shots.push(await shot(page, 'security-insecure-P0'));
      await button(page, '연결 없이 먼저 작성하기').click();
      await button(page, '새 물품 시작').click();
      await fieldInput(page, 'name').fill('비보안 작성');
      await waitForStatus(page, 'save-saved');
      rec.check('사진 없이 로컬 작성은 가능', '비보안 작성', await fieldInput(page, 'name').inputValue());
      await goStep(page, 'P3');
      await waitForStatus(page, 'outbox-env-blocked');
      rec.check('비보안에서 릴레이 호출 계속 없음', [], claims);
      shots.push(await shot(page, 'security-insecure-P3'));
    } finally {
      await context.close();
    }
  });

  await rec.scenario('security', '자체 TLS 보안 origin + HTTPS 릴레이 실제 전송', 'actual_browser+local_tls_test_trust+real_local_relay+simulated_vehicle', async () => {
    const context = await env.tlsBrowser.newContext({ viewport: { width: 390, height: 844 } });
    const page = await context.newPage();
    try {
      const vehicle = await VehicleFixture.pair(env.tlsStack.directBase);
      const url = new URL(vehicle.qrUrl);
      rec.check('QR은 https 페이지를 가리킴', 'https:', url.protocol);
      await openApp(page, vehicle.qrUrl);
      await waitForStatus(page, 'paired');
      rec.check('비-loopback 호스트의 실제 보안 컨텍스트', { secure: true, subtle: true, protocol: 'https:', host: 'phone.cargo.test' }, await page.evaluate(() => ({ secure: isSecureContext, subtle: Boolean(crypto.subtle), protocol: location.protocol, host: location.hostname })));
      await registerItem(page);
      await waitForStatus(page, 'received');
      const draft = await vehicle.waitForDraft();
      rec.check('HTTPS로 초안 전달', 400, draft.draft.payload.measuredDimensionsMm.x);
      rec.note('trust', '격리 브라우저에 이 인증서 SPKI만 임시 허용한 시험. 실제 폰 신뢰 설치는 미시험.');
      rec.note('tls_cert_fingerprint', env.tlsStack.fingerprint256);
    } finally {
      await context.close();
    }
  });

  await rec.scenario('security', '릴레이 주소 평문 비-loopback 설정 차단', grade, async () => {
    const context = await env.browser.newContext();
    const page = await context.newPage();
    try {
      await openApp(page, env.badNext.origin);
      await waitForStatus(page, 'env-RELAY_CLEARTEXT');
      rec.truthy('CSP에 잘못된 릴레이 없음', !((await (await context.request.get(env.badNext.origin)).headers())['content-security-policy'] ?? '').includes('192.168.'));
    } finally {
      await context.close();
    }
  });

  const failingEnvironment = async (name, initScript, state) => {
    await rec.scenario('security', name, grade, async () => {
      const context = await env.browser.newContext({ viewport: { width: 360, height: 780 } });
      await context.addInitScript(initScript);
      const page = await context.newPage();
      const claims = [];
      page.on('request', (request) => { if (request.url().includes('/phone/v1/')) claims.push(request.url()); });
      try {
        const vehicle = await VehicleFixture.pair(env.stack.base);
        await openApp(page, vehicle.qrUrl);
        await waitForStatus(page, state);
        rec.check('연결 차단: 릴레이 호출 없음', [], claims);
        shots.push(await shot(page, `security-${state}`));
      } finally {
        await context.close();
      }
    });
  };
  await failingEnvironment('WebCrypto 없음 표시', () => { Object.defineProperty(Crypto.prototype, 'subtle', { get: () => undefined }); }, 'env-WEBCRYPTO_MISSING');
  await failingEnvironment('IndexedDB 없음 표시', () => { Object.defineProperty(window, 'indexedDB', { get: () => undefined }); }, 'env-INDEXEDDB_MISSING');

  await rec.scenario('security', '저장소 열기 실패 표시', grade, async () => {
    const context = await env.browser.newContext();
    await context.addInitScript(() => { IDBFactory.prototype.open = () => { throw new DOMException('blocked', 'SecurityError'); }; });
    const page = await context.newPage();
    try {
      await openApp(page, env.next.origin);
      await waitForStatus(page, 'storage-UNAVAILABLE');
    } finally {
      await context.close();
    }
  });

  await rec.scenario('security', '미래 버전 DB는 보존하고 쓰기 중단', grade, async () => {
    const context = await env.browser.newContext({ viewport: { width: 360, height: 780 } });
    const page = await context.newPage();
    try {
      await page.goto(`${env.next.origin}/privacy`);
      await page.evaluate(() => new Promise((resolve, reject) => {
        const open = indexedDB.open('cargo-phone', 99);
        open.onupgradeneeded = () => open.result.createObjectStore('fromFuture').put('keep me', 'k');
        open.onsuccess = () => { open.result.close(); resolve(); };
        open.onerror = () => reject(open.error);
      }));
      await openApp(page, env.next.origin);
      await waitForStatus(page, 'storage-FUTURE_VERSION');
      shots.push(await shot(page, 'security-future-version'));
      const kept = await page.evaluate(() => new Promise((resolve) => {
        const open = indexedDB.open('cargo-phone');
        open.onsuccess = () => {
          const db = open.result;
          const request = db.transaction('fromFuture').objectStore('fromFuture').get('k');
          request.onsuccess = () => { const out = { version: db.version, value: request.result }; db.close(); resolve(out); };
        };
      }));
      rec.check('DB 버전과 내용 보존', { version: 99, value: 'keep me' }, kept);
    } finally {
      await context.close();
    }
  });

  await rec.scenario('security', '저장 실패(quota)는 저장됨으로 표시하지 않고 회복 뒤 저장', grade, async () => {
    const context = await env.browser.newContext({ viewport: { width: 360, height: 780 } });
    await context.addInitScript(() => {
      const original = IDBObjectStore.prototype.put;
      IDBObjectStore.prototype.put = function put(...args) {
        if (window.__failPut) throw new DOMException('full', 'QuotaExceededError');
        return original.apply(this, args);
      };
    });
    const page = await context.newPage();
    try {
      await openApp(page, env.next.origin);
      await startItem(page);
      await waitForStatus(page, 'save-saved');
      await page.evaluate(() => { window.__failPut = true; });
      await fieldInput(page, 'name').fill('저장 안 되는 이름');
      await waitForStatus(page, 'save-failed');
      rec.check('저장됨 표시 없음', 0, await statusState(page, 'save-saved').count());
      shots.push(await shot(page, 'security-save-failed'));
      await page.evaluate(() => { window.__failPut = false; });
      await fieldInput(page, 'name').fill('회복 뒤 이름');
      await waitForStatus(page, 'save-saved');
      await page.reload();
      await page.waitForSelector('main[data-loaded="true"]');
      rec.check('회복 뒤 내용 복원', '회복 뒤 이름', await (async () => { await goStep(page, 'P1'); return fieldInput(page, 'name').inputValue(); })());
    } finally {
      await context.close();
    }
  });

  await rec.scenario('security', '손상 기록은 지우지 않고 격리 표시', grade, async () => {
    const context = await env.browser.newContext({ viewport: { width: 360, height: 780 } });
    const page = await context.newPage();
    try {
      await openApp(page, env.next.origin);
      await page.evaluate(() => new Promise((resolve, reject) => {
        const open = indexedDB.open('cargo-phone');
        open.onsuccess = () => {
          const db = open.result;
          const tx = db.transaction('outbox', 'readwrite');
          tx.objectStore('outbox').put({ key: 'broken-record', status: 42 });
          tx.oncomplete = () => { db.close(); resolve(); };
          tx.onerror = () => reject(tx.error);
        };
      }));
      await page.reload();
      await page.waitForSelector('main[data-loaded="true"]');
      await waitForStatus(page, 'storage-CORRUPT');
      shots.push(await shot(page, 'security-corrupt'));
      rec.check('손상 기록 보존', ['broken-record'], (await readStore(page, 'outbox')).map((value) => value.key));
    } finally {
      await context.close();
    }
  });
  return shots;
}

