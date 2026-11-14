# 릴레이 프로토콜 v1

릴레이는 휴대폰이 만든 물품 초안을 차량이 가져갈 때까지 잠시 보관하는 우편함이다. 물품과 트렁크 scene의 권위는 차량 Room DB에만 있으며, 릴레이에는 scene이나 차량 제어 경로가 없다. 안정 계약은 이 문서와 `packages/contracts`의 `ItemDraft`다.

## 주소와 인증

| 설정 | 용도 |
|---|---|
| `PHONE_WEB_BASE_URL` | QR이 여는 모바일웹 주소. fragment로 일회용 토큰을 담는다. 이 origin만 CORS 허용 |
| `PHONE_RELAY_BASE_URL` | 휴대폰이 부르는 릴레이 주소 |
| `VEHICLE_RELAY_BASE_URL` | 차량이 outbound로 부르는 주소 (에뮬레이터는 `10.0.2.2`, 실폰은 LAN이라 서로 다름) |

- 토큰은 OS CSPRNG 256비트 opaque bearer이며 역할별 접두사 `qp_`(QR), `vh_`(차량), `ph_`(폰)을 쓴다. DB에는 SHA-256 digest만 저장한다.
- 전달은 `Authorization: Bearer`만 허용하고 claim의 QR 토큰만 본문으로 받는다. URL의 토큰은 `400 URL_BEARER_FORBIDDEN`이며 인증에 쓰이지 않는다.
- 폰은 URL `#pairing=<qp_토큰>`을 읽어 곧바로 history에서 지워야 한다. 이 처리는 phone-web 소유다.
- 수명은 비연장이다. 미claim 차량 토큰과 QR은 5분, claim 뒤 차량·폰 토큰은 claim 시점부터 24시간(`relay-config-v1`, TTL은 `contracts configuration-v1` revision 1 값 300/86400/86400).

## 엔드포인트

| 메서드·경로 | 인증 | 설명 |
|---|---|---|
| `POST /vehicle/v1/pairings` | 없음 (속도·총량 제한) | pairing 생성. `pairingId, vehicleToken, qrUrl, claimExpiresAt, credentialExpiresAt, vehicleRelayBaseUrl, relayConfigVersion` |
| `GET /vehicle/v1/pairing` | 차량 | `WAITING_CLAIM` 또는 `CLAIMED` |
| `POST /vehicle/v1/pairing/revoke` | 차량 | 해제. `{revoked, pairingId, purgedDrafts}` |
| `GET /vehicle/v1/drafts?after=&limit=` | 차량 | 처리 대기 초안 목록. 항목의 `seq`를 `after`로 넘겨 다음 쪽을 읽는다 |
| `GET /vehicle/v1/drafts/{draftId}/{clientRevision}/photo` | 차량 | 사진 바이트 (`attachment`, `X-Photo-Sha256`) |
| `POST /vehicle/v1/drafts/{draftId}/{clientRevision}/ack` | 차량 | 차량 결정 전달 |
| `POST /phone/v1/pairing/claim` | 본문 `{pairingToken}` | 일회 소비. `pairingId, phoneToken, credentialExpiresAt, consentVersions, relayConfigVersion` |
| `POST /phone/v1/pairing/revoke` | 폰 | 해제 |
| `POST /phone/v1/drafts` | 폰 | 초안 제출 |
| `GET /phone/v1/drafts/{draftId}/{clientRevision}` | 폰 | 상태·`conflict`·`terminalAck` |
| `GET /healthz` | 없음 | DB 연결 확인 (비민감) |

## 초안 제출

```json
{
  "consent": { "consentVersion": "transfer-consent-v1", "dimensionsConsent": true, "photoConsent": false },
  "draft": { "...": "packages/contracts ItemDraft" },
  "photo": { "mime": "image/jpeg", "dataBase64": "..." }
}
```

- 기본은 치수·취급조건만이다. `photo`는 `photoConsent: true`일 때만 받는다.
- `draft.payload.photoRef`는 사진이 있으면 정확히 `sha256:<사진 sha256>`이고 없으면 없어야 한다. 파일명이나 URL은 거부한다.
- 알 수 없는 필드, scene, 전체 Envelope는 거부한다. `baseItemId`와 `baseItemRevision`은 함께 있거나 함께 없어야 한다.
- `payloadHash = SHA256(RFC8785 JCS({draft, photoSha256|null}))`. 키 순서가 달라도 같은 값이다.
- 영수증: `{pairingId, draftId, clientRevision, payloadHash, state, receivedAt, expiresAt, duplicate}`. 처음은 201, 동일 재전송은 200과 원래 값을 돌려준다.

**영수증은 차량 반영이 아니다.** `PENDING`은 릴레이 수신일 뿐이고 차량 반영은 `ACCEPTED`와 `terminalAck.acceptedRevision`만 뜻한다.

### 제출 규칙

| 상황 | 결과 |
|---|---|
| 같은 키, 같은 내용 | 200 동일 영수증 (현재 상태 포함). 재적용 없음 |
| 같은 키, 다른 내용 | 409 `DRAFT_CONTENT_CONFLICT` |
| 더 높은 clientRevision 존재 | 409 `STALE_CLIENT_REVISION` (`newestClientRevision`) |
| 새 clientRevision | 이전 활성 revision을 `SUPERSEDED`로 바꾸고 내용 삭제 |
| 다른 pairingId | 403 `PAIRING_MISMATCH`. 새 QR 뒤에는 outbox 초안의 pairingId를 다시 포장해야 함 |

## 상태와 ack

```
PENDING ──CONFLICT──> CONFLICT_REPORTED ──VEHICLE_ACCEPTED / VEHICLE_REJECTED──> 종료
PENDING ──VEHICLE_ACCEPTED / VEHICLE_REJECTED──> 종료
활성 상태 ──새 revision──> SUPERSEDED / ──TTL──> EXPIRED / ──해제──> REVOKED
```

종료 상태(`ACCEPTED, REJECTED, SUPERSEDED, EXPIRED, REVOKED`)는 전이하지 않으며 내용과 사진을 같은 transaction에서 지운다. DB check 제약이 이를 강제한다. TTL이 지난 활성 초안은 cleanup 전이라도 `EXPIRED`로 읽힌다.

```json
{ "ackVersion": 1, "expectedState": "PENDING", "payloadHash": "...", "result": "VEHICLE_ACCEPTED", "acceptedItemId": "uuid", "acceptedRevision": 7 }
{ "ackVersion": 1, "expectedState": "PENDING", "payloadHash": "...", "result": "CONFLICT", "authoritativeItemId": "uuid", "authoritativeRevision": 9 }
{ "ackVersion": 1, "expectedState": "CONFLICT_REPORTED", "payloadHash": "...", "result": "VEHICLE_REJECTED", "rejectCode": "USER_KEPT_VEHICLE_VERSION" }
```

`rejectCode`는 `USER_DISCARDED | USER_KEPT_VEHICLE_VERSION | VALIDATION_FAILED`다. 릴레이는 형식만 검사하고 revision 값을 비교하거나 결정하지 않는다.

| ack 상황 | 결과 |
|---|---|
| 같은 ack 재전송 | 200 최초 내용, `replayed: true` |
| 같은 단계에 다른 ack | 409 `ACK_MISMATCH` |
| 상태가 `expectedState`와 다름 | 409 `STATE_CONFLICT` (`currentState`) |
| `payloadHash` 불일치 | 409 `PAYLOAD_HASH_MISMATCH` |
| TTL 경과 | 410 `DRAFT_EXPIRED` |
| 해제 뒤 | 403 `PAIRING_REVOKED` |

## 오류

응답은 `{ "error": { "code", "requestId", ...typed } }`이며 입력 내용을 되풀이하지 않는다.

| 상태 | 코드 |
|---|---|
| 400 | `INVALID_JSON, BODY_ABORTED, ENVELOPE_INVALID, ENVELOPE_FIELD_FORBIDDEN, CONSENT_REQUIRED, CONSENT_INVALID, CONSENT_VERSION_UNSUPPORTED, PHOTO_CONSENT_REQUIRED, PHOTO_FIELD_FORBIDDEN, PHOTO_MALFORMED, URL_BEARER_FORBIDDEN, QUERY_INVALID` |
| 401 | `UNAUTHENTICATED` (미상 토큰에 균일), `CREDENTIAL_EXPIRED` |
| 403 | `ROLE_FORBIDDEN, PAIRING_REVOKED, PAIRING_MISMATCH, ORIGIN_FORBIDDEN` |
| 404/405 | `NOT_FOUND, DRAFT_NOT_FOUND, PHOTO_NOT_FOUND, METHOD_NOT_ALLOWED` |
| 409 | `PAIRING_TOKEN_CONSUMED, DRAFT_CONTENT_CONFLICT, STALE_CLIENT_REVISION, ACK_MISMATCH, STATE_CONFLICT, PAYLOAD_HASH_MISMATCH` |
| 410 | `PAIRING_EXPIRED, DRAFT_EXPIRED` |
| 413/415 | `BODY_TOO_LARGE, PHOTO_TOO_LARGE, PAIRING_BYTES_LIMIT, CONTENT_TYPE_UNSUPPORTED, PHOTO_TYPE_FORBIDDEN` |
| 422 | `DRAFT_SCHEMA_INVALID, DRAFT_BASE_INCOMPLETE, PHOTO_REF_INVALID, PHOTO_METADATA_FORBIDDEN, PHOTO_DIMENSIONS_TOO_LARGE, PHOTO_MALFORMED, PHOTO_TYPE_FORBIDDEN, ACK_INVALID` |
| 429 | `RATE_LIMITED, ACTIVE_PAIRING_LIMIT, PAIRING_DRAFT_LIMIT` (`Retry-After`) |
| 503 | `DATABASE_UNAVAILABLE` (`Retry-After`). 내부 정보 없음 |

## 사진 정책

JPEG·WebP, 디코드 후 2MiB 이하, 긴 변 2048px 이하만 받는다.

- JPEG는 SOI부터 마지막 EOI까지 모든 marker와 scan 경계를 끝까지 검사한다. 허용은 DQT·DHT·DRI, baseline/extended/progressive SOF 1개, SOS(다중 scan 가능)와 첫 scan 앞의 식별자 일치 APP0 `JFIF`, APP2 `ICC_PROFILE`, APP14 `Adobe`뿐이다. APP1, 주석, 그 외 APP, 첫 scan 뒤의 모든 APP, 알 수 없는 marker, EOI 뒤 데이터를 거부한다.
- WebP는 `VP8 `·`VP8L`·`VP8X`·`ALPH`·`ICCP` 청크만 허용하고 EXIF·XMP·애니메이션·기타 청크와 남는 바이트를 거부한다.

구조만 검사하고 픽셀은 디코드하지 않으므로 픽셀이나 허용된 ICC 영역 안의 내용까지 보증하지는 않는다. 축소·재인코딩·동의 UI는 phone-web, 실제 결합 확인은 e2e가 맡는다.

## 하류 담당이 지켜야 할 계약

- **phone-web**: 응답 유실로 `PAIRING_TOKEN_CONSUMED`를 받으면 비밀 재발급은 없다. 차량의 새 QR로 다시 연결하고 outbox 초안을 새 `pairingId`로 다시 포장한다.
- **phone-web**: 해제 뒤에는 ack를 읽을 수 없다. 전송함의 미확정 항목은 "차량 반영 여부 미확인"으로 표시하고 성공으로 표시하지 않는다.
- **phone-web**: 데스크톱 Chrome 156 canvas JPEG·WebP(ICC 포함)는 구조 검사 통과를 확인했다. Safari 등에서 APP1이나 다른 APP 세그먼트가 들어가면 거부되므로 실폰에서 확인하고 필요하면 세그먼트를 제거한다.
- **vehicle-platform**: Room commit 뒤 ack가 `PAIRING_REVOKED`로 거부되면 무한 재시도나 Room 되돌리기를 하지 않고 종료한다.
- **vehicle-platform**: `SUPERSEDED`로 ack가 거부되면(`STATE_CONFLICT`) 다음 poll의 새 revision을 차량 권위 revision과 비교해 처리한다.
- **vehicle-platform**: 새 QR 발급 전 이전 pairing을 명시적으로 revoke한다. 릴레이는 자동으로 해제하지 않는다.
