# 리워드 PENDING 진단 및 확인된 롤백 복구

## 목적과 범위

`PENDING`은 실행 중, DB 커밋 결과 불명확, Redis 응답 유실, 프로세스 중단 등 여러 상황에서 남을 수 있다.
오래됐다는 사실만으로 실패나 미지급을 확정할 수 없다.
진단 스크립트는 읽기 전용이다. 별도의 `RecoverRewardService`가 확인된 롤백만 수동 복구한다. 공개 HTTP API나 자동 스케줄러에는 연결하지 않았다.

## 실행

프로젝트 루트에서 필요한 이벤트만 지정해 수동으로 실행한다. 서버 기동 시 실행되지 않는다.
가능하면 읽기 전용 DB 계정을 사용한다. URI에는 애플리케이션 DB를 명시한다.

```bash
KEC_GAME_KEY=game \
KEC_EVENT_KEY=event \
KEC_PENDING_MINUTES=10 \
KEC_PENDING_LIMIT=100 \
mongosh 'mongodb://localhost:27017/mini_kec?replicaSet=rs0' \
  --quiet --file scripts/inspect-pending-rewards.js
```

| 환경변수 | 의미 | 기본값 / 제한 |
| --- | --- | --- |
| KEC_GAME_KEY | 게임 키 | 필수 |
| KEC_EVENT_KEY | 이벤트 키 | 필수 |
| KEC_PENDING_MINUTES | 이 시간 이상 지난 요청 | 10분 / 1~525600 |
| KEC_PENDING_LIMIT | 최대 출력 건수 | 100 / 1~1000 |
| KEC_QUERY_TIMEOUT_MS | MongoDB 조회 실행 시간 제한 | 5000ms / 1~30000 |

조회 대상은 `{gameKey}_{eventKey}_rewardExecution` 한 컬렉션이다.
`status = PENDING`이고 BSON Date인 `createdAt <= 조회 시각 - 기준 시간`인 문서를 오래된 순으로 조회한다.
기준 시각은 스크립트를 실행한 PC의 시계이며 출력 시각은 UTC다. 운영 진단에서는 PC/서버의 시간 동기화 상태를 확인한다.
컬렉션이 없으면 오타나 환경 착오를 숨기지 않고 오류를 반환한다. 컬렉션을 생성하지 않는다.

출력은 단일 JSON이며 다음 내용을 포함한다.

- 조회 DB·컬렉션, 관측 시각, 기준 시각, 적용한 제한
- `returnedCount`: 출력된 건수. 전체 PENDING 건수가 아니다.
- `hasMore`: 제한을 초과하는 대상이 있는지. limit + 1건만 조회하며 전체 count는 하지 않는다.
- 각 요청의 문서 ID, userId, actionId, requestId, 생성 시각, 경과 초
- `assessment = REVIEW_REQUIRED_NOT_CONFIRMED_FAILURE`: 확인 대상이며 실패 확정이 아님

최초 응답 스냅샷과 보상 상세는 출력하지 않는다. 사용자·요청 식별자는 조사에 필요하므로 포함된다.
`createdAt`이 없거나 Date 타입이 아닌 문서는 제외한다. 따라서 0건 결과가 모든 데이터의 정상 상태를 의미하지 않는다.
조회 도중 요청이 완료될 수 있으므로 보고서는 관측 시점의 참고 자료다. 실제 조치 전 최신 상태를 다시 확인한다.

## 조회 부하

현재 요청 유니크 인덱스는 `(userId, actionId, requestId)`이며 상태·시간 조회에 맞는 인덱스는 아니다.
큰 컬렉션에서는 스캔/정렬 비용이 생길 수 있다. 반환 건수 제한이 스캔량까지 제한하지는 않는다.
`maxTimeMS`를 지정해 장시간 실행을 제한하며, 시간 초과는 조회 실패로 처리한다. 자동 재시도하지 않는다.
이 제한은 MongoDB 조회 실행 시간에 적용되며 연결 수립이나 네트워크 대기 전체의 타임아웃은 아니다.
실제 운영 적용 전 실행 계획과 컬렉션 규모를 확인하고, 필요하면 `(status, createdAt, _id)` 인덱스를 별도 검토한다.
스크립트는 인덱스나 기존 데이터를 변경하지 않는다.

## 결과를 확인하는 순서

1. 해당 requestId의 최신 요청 기록을 확인한다. 이미 COMPLETED이고 결과가 있으면 기존 결과 반환 경로를 사용한다.
2. PENDING이면 해당 사용자 요청이 아직 실행 중인지와 서버 오류 로그를 확인한다.
3. Redis 예약 요청/응답, MongoDB 커밋/롤백, 카운터 반환 로그를 대조한다.
4. 근거가 부족하면 PENDING과 카운터를 유지한다. 새 requestId로 재지급하거나 총 카운터를 임의로 감소시키지 않는다.

기존 요청에는 예약 증거가 없다. 새 `requestId` 보상 요청은 실행 시도별 UUID 토큰과 counterKey를 MongoDB receipt에 먼저 저장하고 Redis 예약 Hash에 토큰 상태를 남긴다. UserAction/Resource와 requestId의 직접 연결은 아직 제공하지 않는다.
같은 사용자의 다른 정상 지급이 있을 수 있으므로 사용자 ID와 총 카운트만으로 이 요청의 지급 여부를 확정하지 않는다.
복구는 `rollbackConfirmed=true`와 해당 토큰의 예약 증거를 모두 요구한다. 커밋 불명확 요청, 실행 중 요청, 기존 요청, Redis 증거가 사라진 요청은 REVIEW_REQUIRED로 유지한다.
단순 만료/TTL 삭제는 복구가 아니며, 실행 중인 요청과 충돌하거나 중복 지급을 허용할 수 있다.

## 검증

```bash
mongosh 'mongodb://localhost:27017/?replicaSet=rs0' \
  --quiet --file scripts/tests/inspect-pending-rewards.test.js
```

테스트는 ObjectId로 고유 이름을 만든 임시 DB에만 픽스처를 쓰고, 종료 시 그 DB를 삭제한다.
오래된 PENDING 선별, 최근/완료/잘못된 날짜 제외, 이벤트 격리, 정렬, 출력 제한, 잘못된 입력,
없는 컬렉션, 시스템 DB 거부, 문서와 인덱스 비변경을 검증한다.
실제 mini_kec DB에는 진단 실행이나 마이그레이션을 자동으로 적용하지 않는다.


## 요청별 예약과 복구 흐름

1. MongoDB에서 `(userId, actionId, requestId)` 요청을 선점한다.
2. 수량 제한이 있으면 UUID 예약 토큰과 counterKey를 receipt에 저장한다.
3. Redis Lua가 counter 증가와 token=RESERVED 기록을 함께 실행한다. 같은 token 재호출은 증가하지 않는다.
4. 자원 할당·조건부 포인트 차감·완료 기록·결과 receipt는 한 MongoDB 트랜잭션으로 처리한다.
5. 확정 실패 경로는 DB 작업 종료/롤백 후 rollbackConfirmed=true를 남긴다. Unknown commit에는 남기지 않는다.
6. 일반 실패 보상은 Redis 예약을 반환한다. Lua가 감소와 RELEASED 기록을 함께 실행하며, 재호출은 감소하지 않는다.
7. 반환 응답 유실/반환 실패면 receipt를 유지한다. 수동 recover가 같은 사용자 락을 획득하고 최신 receipt와 Redis token을 대조한다.
8. 롤백 확인 + 예약 증거가 있으면 token을 반환하고, 동일 token/롤백 상태인 receipt만 조건부 삭제한다.
9. RETRY_ALLOWED 이후 클라이언트가 **같은 requestId**로 재요청한다. 새 시도는 새 예약 토큰을 사용한다. 복구 서비스 자체는 포인트 적립·차감이나 보상 재지급을 수행하지 않는다.

호출 위치: `RecoverRewardService.recover(RewardRequestKey(gameKey, eventKey, userId, actionId, requestId))`.
외부에 노출하려면 운영자 인증·권한·감사 기록을 갖춘 별도 진입점을 먼저 설계해야 한다.

| 결과 | 의미 | 후속 조치 |
| --- | --- | --- |
| COMPLETED | 결과 receipt가 커밋됨 | 기존 응답을 반환. 재고 반환/재지급 금지 |
| RETRY_ALLOWED | 확인된 롤백 예약 반환과 조건부 정리 성공 | 같은 requestId로 재요청 가능 |
| REVIEW_REQUIRED | 롤백 또는 예약 증거 부족 | 기록 유지, 커밋·서버·Redis 상태 조사 |
| NOT_FOUND | 최신 receipt 없음 | 최초 요청 실패/이전 정리 등 원인을 확인. 성공을 의미하지 않음 |
| STATE_CHANGED | 조건부 삭제가 0건 | 최신 receipt 재조회. 재시도 허용으로 간주하지 않음 |
| 예외 | Redis/DB 처리 결과 불명확 | 같은 복구 요청 재조회·재시도. 총 counter 직접 감소 금지 |

## 적용 범위와 운영 제한

- 실습 프로젝트의 **requestId가 있는 수량 제한 보상**에 적용한다. requestId 없는 기존 경로는 기존 counter API를 유지하며 요청별 복구를 제공하지 않는다.
- 수량 제한 없는 보상, 이전 PENDING, 커밋 불명확/프로세스 중단 요청은 자동 복구하지 않는다.
- Redis `counterKey:reservations` Hash에 RESERVED/RELEASED/REJECTED를 저장한다. 같은 token의 재사용을 막기 위해 TTL로 자동 삭제하지 않는다. 이벤트 종료 후 보존·정리 정책은 별도 과제다.
- 현재 Docker Compose의 단일 Redis를 기준으로 한다. 두 key Lua이므로 Redis Cluster 사용 시 동일 hash slot 배치를 설계해야 한다.
- Redis key의 독립 삭제/유실, counter 초기화/재구축, 동시 DB 수동 변경은 금지한다. 증거가 없으면 복구를 중단한다.
- 롤백 확인은 클라이언트 입력으로 받지 않는다. 커밋 불명확 예외를 제외한 서버 실패 경로에서만 저장한다.
- 복구와 실행은 같은 user-lock key를 사용한다. 응답 유실 후에는 최신 receipt 확인부터 진행한다.
- 실제 게임 외부 지급 API는 이 실습의 보상 트랜잭션에 없다. 외부 지급 성공 여부까지 복구했다는 의미는 아니다.
- 코드만 변경했고 기존 애플리케이션 DB에 진단/마이그레이션을 적용하지 않는다.

## Kotlin 검증

```bash
MINIKEC_MONGO_TEST_URI='mongodb://localhost:27017/?replicaSet=rs0' \
MINIKEC_REDIS_TEST_HOST=localhost \
./gradlew test --offline --no-daemon
```

MongoDB 테스트는 UUID 이름의 임시 DB를 만들고 삭제한다. Redis 테스트는 UUID 접두어의 key만 생성하고 제거한다.
동일 token 동시 예약/반환, 서로 다른 token의 최대 수량, 증거 누락, 반환 응답 유실, 정리 실패, 동시 복구,
실제 MongoDB+Redis로 롤백 → 예약 반환 응답 유실 → 수동 복구 → 동일 requestId 재시도의 잔액/자원/완료 기록/receipt를 검증한다.
