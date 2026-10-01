# 리워드 요청 멱등성

## API

```http
POST /ec/v1/{gameKey}/events/{eventKey}/actions/{actionId}
Content-Type: application/json

{"externalUserId":"external-user","requestId":"reward-request-001"}
```

같은 논리적 실행의 재전송에는 같은 requestId를 사용한다. 새 보상 실행에는 새 ID를 사용한다.
ID는 선택 필드이며, 없으면 기존 흐름을 사용한다. 공백만인 ID와 128자를 초과하는 ID는 400이다.
범위는 게임·이벤트·사용자·Action·requestId다. 다른 범위에서는 같은 문자열을 사용할 수 있다.
새 ID도 이벤트 기간, 반복 정책, 잔액, 재고 검사를 통과해야 한다.

## 처리 흐름

1. User Lock 안에서 저장된 리워드 요청을 먼저 조회한다.
   - COMPLETED: 최초 응답을 반환한다. 티켓 차감/자원 할당/Redis 예약을 반복하지 않는다.
   - PENDING: 409 / REWARD_REQUEST_PENDING. 실행 중이거나 정합성 확인이 필요한 상태다.
2. 새 요청이면 기존 검증 후 필요한 컬렉션과 인덱스를 준비한다.
3. 트랜잭션 밖에서 `{gameKey}_{eventKey}_rewardExecution`에 PENDING을 insert한다.
   `(userId, actionId, requestId)` Unique Index `uk_reward_request`가 선점 경쟁에서 한 요청만 허용한다.
4. 선점한 요청만 Redis 재고를 확보한다. MongoDB 트랜잭션 재시도에도 선점/예약을 반복하지 않는다.
5. 트랜잭션에서 자원 할당 + 티켓 차감 + UserAction 저장 + 요청 결과/COMPLETED 변경을 함께 수행한다.
6. 커밋 후 응답한다. 최초 응답의 points는 당시 잔액 스냅샷이다.

동일 ID의 완료 결과는 이벤트 종료/비활성화 후에도 반환한다. 이벤트·사용자·Action 자체는 여전히 존재해야 한다.
User Lock을 생략한 경쟁에서도 선점으로 추가 예약/지급을 막는다. 경쟁 시 모든 요청이 즉시 200을 받는 것은 아니다.
처리 중이면 409를 받을 수 있고, 완료된 뒤 같은 ID로 재요청하면 저장된 결과를 받는다.

## 실패와 복구 경계

| 상황 | DB 요청 기록 | 재고와 재시도 |
| --- | --- | --- |
| 품절이 확정됨 | PENDING 삭제 | 남의 예약은 반환하지 않음. 같은 ID 재시도 가능 |
| DB 작업 실패, 롤백 완료, Redis 반환 성공 | PENDING 삭제 | 같은 ID로 다시 실행 가능 |
| DB 커밋 성공 후 응답 유실 | COMPLETED | 예약 유지. 같은 ID로 결과 조회 |
| 커밋/롤백 결과 불명확 | 기존 기록 유지 | 예약 유지. PENDING이면 재실행 차단 |
| Redis 예약 응답 불명확 또는 반환 실패 | PENDING 유지 | 자동 예약/반환 반복 금지 |
| 프로세스 중단 또는 PENDING 정리 실패 | PENDING이 남을 수 있음 | 상태 대조 후 복구 필요 |

Redis 스크립트 결과가 null인 경우에도 품절/반환 성공으로 취급하지 않고 오류로 전달한다.
PENDING은 실제 예약 여부를 증명하지 않는다. 요청 선점 직후 죽었을 수도, Redis 예약 후 죽었을 수도 있다.
시간이 지났다는 이유로 PENDING을 삭제하거나 Redis를 무조건 감소시키면 안 된다.
현재 Redis 카운터는 요청별 예약 토큰을 저장하지 않아 자동 복구에 필요한 증거가 부족하다.
오래된 PENDING을 찾는 [읽기 전용 진단 스크립트](reward-reconciliation.md)를 제공한다. 자동 정합성 복구, 예약 토큰, 운영 복구 API는 후속 작업이다. 409 응답을 새 requestId 발급으로 우회하지 않는다.

## 호환성과 데이터 영향

기존 요청 기록을 이관하거나 기존 지급 건을 소급 보호하지 않는다. 서버 기동 시 마이그레이션을 실행하지 않는다.
리워드 실행 준비 시 이벤트별 신규 컬렉션/인덱스를 만든다. 운영 배포 시 해당 DDL 권한과 준비 절차를 확인해야 한다.
결과 기록에 TTL은 없다. 기록 삭제는 해당 ID의 중복 방지를 제거한다.
구버전 서버는 리워드 requestId를 지원하지 않는다. 전체 서버 업데이트 후 클라이언트에서 활성화한다.
구버전으로 롤백하면 기록은 남아도 새 기능을 사용할 수 없다.
MongoDB와 Redis의 분산 트랜잭션이나 외부 게임 아이템 지급의 멱등성을 제공하는 것은 아니다.

## 검증

실제 MongoDB Replica Set의 UUID 임시 DB를 사용하고 종료 시 그 DB만 삭제한다.
Redis 카운터는 테스트 구현으로 대체하며 네트워크 응답 유실은 오류 주입으로 확인한다.

```bash
MINIKEC_MONGO_TEST_URI='mongodb://localhost:27017/?replicaSet=rs0' ./gradlew test --offline --no-daemon
```

원본 응답 재반환, 새 ID 실행, 종료 후 재조회, 결과 저장 실패의 전체 롤백, 트랜잭션 재시도,
동일 요청 동시 실행, 선점 Unique Index 경쟁, 커밋 응답 유실, Redis 예약/반환 불명확 상태,
품절 후 재시도, 요청 범위 분리, 409 응답을 검증한다. 실제 운영 DB 마이그레이션은 수행하지 않는다.
