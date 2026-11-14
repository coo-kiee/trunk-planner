// CE-22-04: decoding a QR in a desktop browser proves the URL round-trips. It is not a phone camera scan, and the record says so.
import { createRequire } from 'node:module';
import QRCode from 'qrcode';
import { openApp, waitForStatus } from './flows.mjs';
import { VehicleFixture } from './vehicle-fixture.mjs';

const require = createRequire(import.meta.url);
const jsQR = require('jsqr');

export async function runCe22(rec, env) {
  await rec.scenario('CE-22-04', 'QR 이미지 브라우저 디코드 → 같은 URL로 연결', 'actual_browser_qr_decode_not_phone_camera', async () => {
    const vehicle = await VehicleFixture.pair(env.stack.base);
    const context = await env.browser.newContext({ viewport: { width: 390, height: 844 } });
    const decoderPage = await context.newPage();
    const page = await context.newPage();
    try {
      const png = await QRCode.toDataURL(vehicle.qrUrl, { errorCorrectionLevel: 'M', width: 512 });
      const pixels = await decoderPage.evaluate(async (url) => {
        const image = new Image();
        image.src = url;
        await image.decode();
        const canvas = document.createElement('canvas');
        canvas.width = image.naturalWidth;
        canvas.height = image.naturalHeight;
        const drawing = canvas.getContext('2d');
        drawing.drawImage(image, 0, 0);
        const data = drawing.getImageData(0, 0, canvas.width, canvas.height);
        return { width: data.width, height: data.height, data: [...data.data] };
      }, png);
      const decoded = jsQR(Uint8ClampedArray.from(pixels.data), pixels.width, pixels.height);
      rec.check('디코드 URL은 QR 내용과 동일', vehicle.qrUrl, decoded?.data);
      await openApp(page, decoded.data);
      await waitForStatus(page, 'paired');
      rec.check('fragment 제거', '', new URL(page.url()).hash);
      rec.note('boundary', '합성 PNG를 데스크톱 Chrome에서 픽셀로 읽어 jsQR로 디코드했다. 실제 폰 카메라·차량 화면 촬영·폰 브라우저는 관여하지 않았다.');
    } finally {
      await context.close();
    }
  });
}

export const NOT_TESTED = [
  { item: '실제 폰 카메라로 차량 화면 QR 스캔', reason: '실제 폰 미사용' },
  { item: '실제 폰 LAN 접속과 로컬 TLS 신뢰 설치', reason: '실제 폰 미사용, 신뢰 설치 안 함' },
  { item: '실제 폰 사진 선택·촬영과 업로드', reason: '데스크톱 Chrome의 파일 입력으로만 시험' },
  { item: 'iOS Safari·Android Chrome에서의 동작', reason: '데스크톱 Chrome 156만 사용' },
  { item: '실제 차량 앱과의 결합(Room 수락·ack)', reason: '차량 역할은 프로토콜 fixture. 결합은 후속 e2e 담당' },
  { item: '실차', reason: '실차 없음' },
  { item: '브라우저 저장소 축출(eviction)', reason: '실제 축출을 유발하지 않음. quota 오류는 주입으로만 시험' },
  { item: '오프라인 상태의 최초 접속·새로고침', reason: 'service worker를 쓰지 않으므로 지원하지 않음. 열린 페이지의 오프라인만 지원' },
  { item: '공인 HTTPS 호스팅·release 페어링', reason: '외부 호스팅 미승인, 실행 안 함' },
];
