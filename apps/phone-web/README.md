# 휴대폰 물품 등록 (phone-web)

차량 적재 플래너로 보낼 물품을 휴대폰 브라우저에서 등록합니다. Next.js 앱이며 P0~P3 네 화면으로 구성됩니다.

- **P0 연결**: 차량 QR로 연결, 환경 점검, 만료·해제, 개인정보 안내
- **P1 사진·형상**: 사진(선택), 이름, 형상. 사진 전송 동의는 별도입니다.
- **P2 치수·취급조건**: 직접 잰 치수 확정, 측정 출처, 오차, 회전·적층·꺼냄 우선순위
- **P3 전송함**: 대기, 전송 중, 서버 수신, 충돌, 차량 반영(revision), 거부, 교체, 만료, 해제, 미확인

폰은 물품 초안만 보냅니다. 트렁크 scene을 고치지 않고, 3D나 판정을 그리지 않으며, geometry 판정을 복제하지 않습니다. 측정앱 연동은 아직 없습니다(TODO).

## 설치와 실행

저장소 루트에서 실행합니다. Node 24 이상이 필요합니다.

```bash
npm run build --workspace=@cargo/contracts            # 공통 계약 타입. 없으면 빌드가 명시적으로 실패합니다
npm --prefix apps/phone-web ci --no-workspaces
npm --prefix apps/phone-web run build
PHONE_RELAY_BASE_URL=https://릴레이주소 npm --prefix apps/phone-web run start -- -p 3210 -H 127.0.0.1
```

`PHONE_RELAY_BASE_URL`은 요청마다 서버에서 읽으므로 주소가 바뀌어도 다시 빌드하지 않습니다. https만 허용하며, http는 이 컴퓨터 안(loopback) 개발 시험에서만 허용합니다. 비밀값이 아닌 공개 주소입니다.

릴레이의 `PHONE_WEB_BASE_URL`은 이 앱의 origin과 정확히 같아야 합니다. 다르면 릴레이가 CORS를 거부하고, 앱은 "연결 실패(네트워크 또는 설정)"로 표시합니다.

## 로컬 HTTPS와 실제 폰 시험

연결과 전송에는 보안 컨텍스트가 필요합니다. 휴대폰은 `http://LAN주소`에서 보안 컨텍스트가 아니므로 앱이 전송을 막습니다.

```bash
# next start 앞단의 HTTPS 프록시. 기본은 loopback이고, LAN 공개는 --lan-host를 줄 때만입니다.
npm --prefix apps/phone-web run start:tls -- --target-port 3210 --port 3443 --lan-host 192.168.0.5
```

인증서는 git에서 제외된 `.local-tls/`에 만들어지며, 어떤 신뢰 저장소에도 자동 설치하지 않습니다. 실제 폰 시험(G-NET2)을 하려면 사람이 직접 다음을 수행해야 합니다.

1. 폰에 `.local-tls/cert.pem`을 신뢰 인증서로 설치합니다.
2. 릴레이도 같은 인증서로 HTTPS 기동합니다(`RELAY_TLS_CERT_FILE`, `RELAY_TLS_KEY_FILE`, `PHONE_WEB_BASE_URL=https://192.168.0.5:3443/`).
3. 차량 화면의 QR을 폰 카메라로 스캔해 연결·사진 선택·전송을 확인합니다.

**실제 폰 스캔, LAN TLS 신뢰, 폰 사진 업로드, iOS Safari, Android Chrome은 아직 시험하지 않았습니다.** 데스크톱 Chrome의 QR 디코드는 실폰 스캔이 아닙니다.

## 시험

```bash
npm --prefix apps/phone-web run test:unit    # 순수 모델, 경계값, 검증기 동치
npm --prefix apps/phone-web run build        # evidence/product/phone-web/build.json
npm --prefix apps/phone-web run test:e2e     # 실제 Chrome + 실제 릴레이·새 PostgreSQL. browser.json, CE-*.json
node apps/phone-web/tools/check-screen-ds.mjs # 실제 화면의 공통 DS 소비. ds-consumption.json
npm --prefix apps/phone-web run report       # 위 증거로 report.html 생성
```

E2E의 등급은 `actual_browser+real_local_relay+simulated_vehicle`입니다. 차량 역할은 릴레이 프로토콜을 그대로 호출하는 fixture이며, 실제 차량 앱과의 결합은 후속 e2e가 맡습니다. 시스템 Chrome을 새 임시 프로필로 쓰고(`CHROME_PATH`로 지정 가능), 빈 포트만 사용하며, 자신이 띄운 프로세스와 데이터만 정리합니다.

## 알려진 한계

- service worker를 쓰지 않습니다. 열려 있는 페이지는 오프라인에서도 작성·저장·화면 이동이 되지만, 오프라인 최초 접속이나 새로고침은 되지 않습니다.
- 앱이 열려 있을 때만 다시 보내고 차량 결과를 읽습니다. 백그라운드 전송은 없습니다.
- 브라우저 저장소의 연결 정보와 작성 내용은 암호화를 보장하지 않습니다. 브라우저가 저장소를 비우면 사라질 수 있습니다.
- 연결 해제나 만료 뒤에는 차량 결과를 읽을 수 없어 "차량 반영 여부 미확인"으로 표시합니다.
- 릴레이는 종단간 암호화가 아닙니다. 위협 모델은 `services/relay/docs/threat-model-v1.md`에 있습니다.

## 구조

| 경로 | 책임 |
|---|---|
| `app/`, `proxy.ts` | Next 조립, 요청별 CSP nonce, 런타임 설정 |
| `screens/` | P0~P3 실제 화면 |
| `features/` | 연결, 편집, 사진, 전송함의 효과 |
| `entities/` | 물품·전송함·연결의 순수 모델과 상태 전이 |
| `shared/ui/` | Button, Field, Status, Panel. `packages/design-tokens` 토큰만 소비 |
| `shared/api/`, `shared/storage/` | 릴레이 어댑터, IndexedDB 트랜잭션 |
| `shared/contracts/` | 공통 schema에서 빌드 때 생성한 검증기(`unsafe-eval` 불필요) |
| `tools/` | 빌드, E2E, DS 검사, 로컬 TLS |

## 저장 손상과 여러 탭

- 브라우저 저장소의 기록은 읽을 때 구조 전체를 검사합니다(`shared/storage/integrity.ts`). 읽을 수 없는 전송함·확정 기록은 제자리에 두고, 다음 저장이 덮어쓸 초안·연결 기록은 `quarantine` 저장소로 옮깁니다. 어느 쪽도 화면에 그리거나 전송하지 않습니다. 삭제는 사용자가 "읽을 수 없는 기록만 지우기"를 확인할 때만 합니다.
- 읽을 수 있는 나머지 항목은 계속 전송합니다. 손상된 연결 기록은 쓰지 않으므로 새 QR이 필요합니다.
- 초안 저장과 전송함 넣기는 모두 저장마다 고유한 ID로 비교-교체합니다. 다른 탭이 먼저 바꿨으면 아무것도 지우지 않고 최신 내용을 불러오도록 안내합니다.
