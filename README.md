# 트렁크 플래너

| 항목 | 내용 |
|---|---|
| 앱 | 휴대폰에서 보낸 짐을 Pleos에서 3D로 배치·저장 |
| 제출 APK | [장민우_트렁크플래너_release.apk](apk/장민우_트렁크플래너_release.apk) |
| 시험 환경 | Pleos Connect v2.1.0 · Android Automotive 34 · ARM64 · 2560 × 1440 |
| APK 구분 | 시험 서명 · 차량 단독 실행 · 웹 연동 비활성 |
| 웹 연동 시연 | 별도 `demoLocalDebug` APK · Chrome · 로컬 PostgreSQL |
| 개발 예정 | 휴대폰 3D 편집과 완성된 배치 계획 전송 |
| 미시험 | 실제 휴대폰·실차·외부 HTTPS 연동 |

## 1. 제출 APK 실행

```bash
adb devices
export PLEOS_SERIAL=emulator-5554  # adb devices에 표시된 Pleos 번호로 교체
adb -s "$PLEOS_SERIAL" install -r "apk/장민우_트렁크플래너_release.apk"
adb -s "$PLEOS_SERIAL" shell am start -n ai.cargo.planner/ai.cargo.planner.MainActivity
```

- 설치 후 앱 목록에서 **트렁크 플래너**로 표시.
- 정차 상태 → 차량 공간 선택 → 짐 등록 → 3D 배치·50mm 이동·고정 → 저장 → 다시 열기.

## 2. 웹 연동 환경 준비

- 실행 위치: 저장소 루트. Node.js 24 이상, JDK 17, Android SDK 35, Pleos Vehicle SDK 2.3.2.
- 공식 Pleos SDK와 에뮬레이터 이미지는 별도 설치.
- PostgreSQL: 로컬 시연 명령이 npm의 내장 PostgreSQL로 임시 DB를 시작. 별도 DB 계정 불필요.
- 아래 주소는 **같은 컴퓨터의 Chrome과 Pleos 에뮬레이터**용.

| 연결 | 기본 주소 |
|---|---|
| Chrome → 휴대폰 웹 | `http://127.0.0.1:3211/` |
| Chrome → 중계 API | `http://127.0.0.1:3210/` |
| Pleos 에뮬레이터 → 중계 API | `http://10.0.2.2:3210/` |

```bash
npm --prefix apps/phone-web ci --no-workspaces
npm --prefix services/relay ci --no-workspaces
mkdir -p node_modules/@cargo packages/contracts/node_modules
ln -sfn ../../packages/contracts node_modules/@cargo/contracts
ln -sfn ../../packages/design-tokens node_modules/@cargo/design-tokens
ln -sfn ../../../apps/phone-web/node_modules/ajv packages/contracts/node_modules/ajv
./apps/phone-web/node_modules/.bin/tsc -p packages/contracts/tsconfig.json
(
  cd apps/phone-web
  node tools/generate-validator.mjs
  ./node_modules/.bin/next typegen
  ./node_modules/.bin/tsc --noEmit
  ./node_modules/.bin/next build
)
```

### 터미널 A · 중계 서버와 임시 PostgreSQL

```bash
RELAY_PORT=3210 \
PHONE_WEB_BASE_URL=http://127.0.0.1:3211/ \
PHONE_RELAY_BASE_URL=http://127.0.0.1:3210/ \
VEHICLE_RELAY_BASE_URL=http://10.0.2.2:3210/ \
npm --prefix services/relay run dev:local
```

- `local_stack_ready` 출력 확인. 종료: Ctrl+C. 테스트용 임시 DB는 정상 종료 시 정리.

### 터미널 B · 휴대폰 웹

```bash
PHONE_RELAY_BASE_URL=http://127.0.0.1:3210/ \
npm --prefix apps/phone-web run start -- -H 127.0.0.1 -p 3211
```

- Chrome: `http://127.0.0.1:3211/`.
- Pleos의 **휴대폰 연결 → QR 만들기**. QR에서 읽은 링크를 **같은 컴퓨터의 Chrome**에서 열기. 홈 주소만 열면 페어링되지 않음.
- 짐 등록 → 웹 전송함에서 서버 수신 확인 → Pleos에서 **차량에 반영** → 3D 배치·저장.

## 3. Pleos 연결 주소 변경

| 항목 | 지정할 값 |
|---|---|
| `demoRelayUrl` / `relay_url` | Pleos에서 접속 가능한 **중계 API 주소** |
| `demoPhoneUrl` / `phone_url` | 브라우저에서 열 **휴대폰 웹 주소**. QR에 사용 |
| 웹의 `PHONE_RELAY_BASE_URL` | 브라우저에서 접속 가능한 **중계 API 주소** |
| 중계 서버의 `PHONE_WEB_BASE_URL` | 웹의 실제 origin과 일치: 프로토콜·호스트·포트 |

### 새 개발용 APK를 빌드할 때

```bash
export PRODUCT_JAVA_HOME="/path/to/jdk-17"
export PRODUCT_ANDROID_HOME="/path/to/android-sdk"

bash tools/product-env/gradle.sh :app:assembleDemoLocalDebug \
  -PdemoRelayUrl=http://10.0.2.2:3210/ \
  -PdemoPhoneUrl=http://127.0.0.1:3211/

adb -s "$PLEOS_SERIAL" install -r \
  apps/vehicle-android/app/build/outputs/apk/demoLocalDebug/app-demoLocalDebug.apk
adb -s "$PLEOS_SERIAL" shell am start -n ai.cargo.planner.demolocal/ai.cargo.planner.MainActivity
```

### 이미 설치한 개발용 APK의 주소만 바꿀 때

```bash
pkg=ai.cargo.planner.demolocal
user_id=$(adb -s "$PLEOS_SERIAL" shell am get-current-user | tr -d '\r')
adb -s "$PLEOS_SERIAL" shell "run-as $pkg --user $user_id sh -c 'cd /data/user/$user_id/$pkg && mkdir -p files/debug-flags && printf %s http://10.0.2.2:3210/ > files/debug-flags/relay_url && printf %s http://127.0.0.1:3211/ > files/debug-flags/phone_url'"
adb -s "$PLEOS_SERIAL" shell am force-stop --user "$user_id" "$pkg"
adb -s "$PLEOS_SERIAL" shell am start --user "$user_id" -n "$pkg/ai.cargo.planner.MainActivity"
```

- 주소 변경 후: 앱 재시작 → 기존 연결이 남아 있으면 해제 → 새 QR로 연결.
- 빌드 값으로 되돌리기: 같은 앱 폴더의 `files/debug-flags/relay_url`, `files/debug-flags/phone_url` 제거 → 앱 재시작.
- 제출용 release에는 주소 변경 메뉴가 없으며 이 명령은 **개발용 APK에만** 적용.
- 외부 서버 시험: 위 주소를 실제 HTTPS URL로 교체하고 웹·중계 서버 설정도 함께 변경. 로컬 HTTP 주소는 실제 휴대폰에서 사용 불가.

## 4. 소스 경로

| 경로 | 역할 |
|---|---|
| `apps/vehicle-android` | Pleos 차량 앱 |
| `apps/phone-web` | 휴대폰 웹 |
| `services/relay` | 중계 API·PostgreSQL |
| `packages/` | 공통 데이터 계약·디자인 값 |
