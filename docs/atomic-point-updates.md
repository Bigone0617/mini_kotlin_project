# 포인트 동시 변경 실습

## 변경 흐름

- 참여 시 초기 포인트 생성은 기존 트랜잭션과 `save`를 사용한다.
- 미션 적립은 `earn`으로 `currentPoint`, `totalPoint`를 같은 문서에서 `$inc`한다.
- 보상 차감은 `spendIfEnough`로 `currentPoint >= amount` 조건과 `$inc: -amount`를 하나의 `findAndModify` 명령에서 수행한다.
- 차감은 누적 적립량 `totalPoint`를 변경하지 않는다. 포인트 문서가 없거나 잔액이 부족하면 null이다. 0 이하 금액은 거부한다.
- 보상 사전 조회는 빠른 거절을 위한 확인이며, 실제 허용 여부는 DB의 조건부 차감이 결정한다.
- 조건부 차감 실패 시 기존 보상 예외 처리 흐름이 Resource와 Redis Counter를 반환한다.
- 조회 및 변경은 game/event 컬렉션과 userId/pointKey로 제한한다. 기존 User Lock은 유지한다.

## 동시 초기 적립

포인트 문서가 없으면 upsert로 생성한다. 반드시 `(userId, pointKey)` Unique Index가 준비되어 있어야 한다.
다른 요청이 먼저 생성해서 DuplicateKeyException이 발생한 경우, 이미 생성된 문서만 대상으로 자신의 적립분을 증가시킨다. 일반 DB 오류는 재실행하지 않는다.
미션 적립은 현재 트랜잭션 안에서 수행한다. 트랜잭션 안의 중복 키 오류는 복구 갱신 없이 전체 롤백으로 전달하며, 트랜잭션 밖의 직접 적립 호출만 기존 문서 갱신 복구를 수행한다.

## 검증

실제 MongoDB 테스트는 임시 DB에서 실행한다.

```bash
MINIKEC_MONGO_TEST_URI='mongodb://localhost:27017/?replicaSet=rs0' ./gradlew test --offline --no-daemon
```

- 잔액 10, 동시 8건의 3 차감: 3건 성공, 5건 거절, 최종 잔액 1
- 동시 최초 적립 8건 × 2: 문서 1개, 잔액과 누적 적립량 16
- 잔액 100, 동시 4건 × 5 적립과 4건 × 3 차감: 최종 잔액 108, 누적 적립량 120
- 잔액 부족, 없는 문서, 잘못된 금액, 다른 사용자/이벤트/게임에 대한 차감 거절
- 보상 서비스가 사전 조회 값 대신 DB 차감 결과를 응답하고, 차감 거절 시 자원을 반환

## 남아 있는 범위

이는 단일 포인트 문서의 동시 갱신을 안전하게 만든 작업이다. 동일 지급 요청을 두 번 실행하는 것을 막는 멱등성 구현은 아니다.
보상 자원 할당·포인트 차감·UserAction 저장은 후속 작업에서 트랜잭션으로 묶었다(reward-transactions.md 참고). 미션 완료 기록과 적립도 후속 작업에서 트랜잭션으로 묶었다(mission-transactions.md 참고).
금액을 조회한 뒤 `save`로 덮어쓰는 새 로직을 추가하면 원자적 갱신의 보장이 깨질 수 있으므로, 잔액 변경은 earn/spendIfEnough를 사용한다.
