# 릴레이 운영 안내 v1

## 설치

```bash
npm run build --workspace=@cargo/contracts    # 저장소 루트. 없으면 릴레이가 명시적으로 기동을 거부
npm --prefix services/relay ci --no-workspaces
```

`@cargo/contracts`는 레지스트리에서 받지 않고 루트 `node_modules`의 로컬 workspace 링크로 읽는다. 릴레이 의존성은 `services/relay/package-lock.json`으로 고정한다. 루트 lock 동기화는 foundation 담당이다.

## 로컬 시험과 데모

```bash
npm --prefix services/relay run test:postgres   # 실제 PostgreSQL. evidence/product/relay/postgres.json
npm --prefix services/relay run test:protocol   # unit과 실제 DB 프로토콜. protocol.json
npm --prefix services/relay run dev:local       # 격리 DB + 릴레이. 종료 시 자기 데이터 삭제
npm --prefix services/relay run report          # 증거에서 report.html 생성
```

PostgreSQL은 exact-pin 개발 의존성 `embedded-postgres@18.6.0-beta.19`(MIT, PostgreSQL 18.6)다. **beta 배포판이며 시험 전용 격리 도구**로, 운영 DB 배포 경로가 아니다. 시스템 설치, brew, Docker를 쓰지 않는다. 데이터는 `services/relay/.local-pg/` 안에만 있고 임의의 빈 포트의 127.0.0.1 TCP와 SCRAM 인증만 쓰며 3100/3101 등 예약 포트는 피한다. 하네스는 자기가 띄운 자식만 종료한다.

도구가 동작하지 않으면 `test:postgres`는 0이 아닌 코드와 `status: blocked`, `postgres-checkpoint.json`을 남긴다. 메모리나 SQLite로 대체하지 않는다.

## 환경 변수

| 변수 | 설명 |
|---|---|
| `RELAY_DATABASE_URL` | runtime role 연결. 원격은 `sslmode=verify-full` 필수 |
| `RELAY_MIGRATION_DATABASE_URL` | owner role 연결 (migrate 전용) |
| `RELAY_RUNTIME_ROLE` | migrate가 권한을 줄 runtime role 이름 |
| `PHONE_WEB_BASE_URL`, `PHONE_RELAY_BASE_URL`, `VEHICLE_RELAY_BASE_URL` | 서로 독립. https 필수 (demoLocal만 http) |
| `RELAY_TLS_CERT_FILE`, `RELAY_TLS_KEY_FILE` | TLS. 함께 설정 |
| `RELAY_BIND_HOST`, `RELAY_PORT` | 기본 127.0.0.1, 빈 포트 |
| `RELAY_DEMO_LOCAL=1` | loopback 평문 허용. 공개 운영 금지 |
| `RELAY_CONFIG_FILE` | 기본 `config/relay-config-v1.json` |

## 역할과 migration

1. DBA가 비superuser `owner`와 `runtime` role, owner 소유 DB를 만든다.
2. `npm --prefix services/relay run migrate`를 owner로 실행한다. advisory lock으로 직렬화하고 checksum을 기록하며 재실행은 건너뛴다. 적용 후 변경된 파일은 거부한다.
3. 릴레이는 runtime role로만 접속한다. DDL, TRUNCATE, 설정 변경은 DB가 거부한다.

### Rollback 경계

`migrate --down-one --confirm-destroys-data`는 **모든 우편함 데이터를 파괴한다.** 격리 DB나 빈 DB에만 쓴다. 실데이터가 있으면 앞으로 가는 수정 migration을 추가한다. 내용 손실 영향: 폰은 outbox로 재전송할 수 있지만 토큰이 사라지므로 재페어링이 필요하고, 차량 scene은 영향이 없다. 비어 있지 않은 DB의 down은 백업 판단 후에만 수행한다.

## 설정 변경

설정 버전은 불변이다. 값을 바꾸려면 `relayConfigVersion`을 올린 새 파일로 migrate를 실행한다. 같은 버전에서 파일 hash가 DB 행과 다르면 기동을 거부한다. 기존 pairing은 생성 당시 버전의 TTL을 유지한다.

## TTL cleanup

프로세스가 주기적으로 실행하며, 수동 실행은 `npm --prefix services/relay run cleanup`이다. batch, 멱등, `SKIP LOCKED`이므로 여러 실행기가 공존한다. 조회는 cleanup과 무관하게 DB 시각으로 만료를 판정하므로 cleanup이 늦어도 내용 노출은 없고 물리 삭제만 늦어진다.

## 로그

릴레이 로그는 allowlist JSON으로 `event, requestId, method, route(템플릿), status, durationMs` 등만 남긴다. PostgreSQL도 값이 남지 않게 설정한다.

```
log_statement=none
log_parameter_max_length=0
log_parameter_max_length_on_error=0
log_min_error_statement=panic
```

## 백업

**기본은 백업 없음이다.** 우편함은 수명이 짧고 복구 대상은 폰 outbox와 차량 Room이다. embedded 배포판에는 `pg_dump`가 없어 백업·복원을 시험하지 않았다(`not_run`).

백업을 도입한다면 암호화, 보존 기간 TTL 이내, 삭제 절차가 필수다. **복원은 해제된 토큰과 삭제된 내용을 되살릴 수 있다.** 복원 전 시점 이후의 pairing은 모두 무효로 처리하고 재페어링한다. 운영 복구 절차는 미검증이다.

## 장애 시 동작 (실측)

- 릴레이 SIGTERM: 종료 코드 0, 재시작 후 대기 초안·ack·해제 유지.
- 릴레이 SIGKILL: 진행 중 요청은 클라이언트 오류. 재시도는 멱등이며 키마다 한 행만 존재.
- PostgreSQL SIGKILL: 릴레이는 살아서 `503 DATABASE_UNAVAILABLE`, DB 복구 후 재시작 없이 재연결. 커밋된 데이터 유지.

휴대폰 outbox 대기 표시와 차량 단독 진행은 이 담당의 시험 범위가 아니다.

## 미실행

공인 HTTPS, 호스팅, 디스크·백업 암호화, pg_dump/restore, 실폰, 실차는 모두 `not_run`이다. 호스팅은 별도 승인 전 수행하지 않는다.
