# 보상 지급 트랜잭션

## 실행 순서

1. User Lock 안에서 이벤트, 반복 정책, 포인트, 보상 설정을 검사한다.
2. 트랜잭션 밖에서 포인트/실행 기록/자원 컬렉션과 필요한 인덱스를 준비한다.
3. 수량 제한이 있으면 Redis 카운터 자리를 한 번 확보한다.
4. MongoDB 트랜잭션에서 기간과 반복 정책 재검사 → Resource 할당 → 조건부 포인트 차감 → UserAction 완료 기록을 수행한다.
5. 커밋 성공 후 결과를 반환한다. 성공한 보상의 Redis 카운트는 유지한다.

`MongoRewardUnitOfWork`는 기존 `MongoParticipationUnitOfWork.execute`의 MongoDB 트랜잭션/재시도 로직을 재사용한다. 콜백에는 MongoDB 작업만 포함된다. 쓰기 충돌로 재시도해도 Redis 확보가 반복되지 않는다.
미션은 별도 MissionUnitOfWork로 완료 기록과 포인트 적립을 묶는다(mission-transactions.md 참고).

## 실패 처리

- 일반 작업 실패: MongoDB가 Resource, UserPoint, UserAction을 함께 롤백한 후 Redis 카운터를 반환한다.
- Resource를 별도 release 호출로 되돌리지 않는다. DB 트랜잭션 롤백이 수행하며, 다른 요청이 확보한 자원을 덮어쓸 위험도 줄인다.
- 커밋 결과 불명확 또는 트랜잭션 종료 오류: `RewardCommitUncertainException`으로 전달하고 카운터를 유지하며 오류 로그를 남긴다. 데이터가 커밋됐을 수 있으므로 재고를 임의로 반환하면 안 된다.
- Redis 반환 실패: 원래 오류를 유지하고 반환 오류는 suppressed exception 및 로그로 남긴다.

## 검증

```bash
MINIKEC_MONGO_TEST_URI='mongodb://localhost:27017/?replicaSet=rs0' ./gradlew test --offline --no-daemon
```

`RewardTransactionTest`는 임시 MongoDB의 실제 트랜잭션을 사용하며 Redis 카운터는 스레드 안전한 테스트 구현을 사용한다. 실제 Redis 네트워크 장애나 커밋 응답 유실은 재현하지 않고 오류를 주입한다.

- 완료 기록 저장 후 실패 → 세 컬렉션 변경 롤백 + 카운터 반환 + 재요청 성공
- 일시적 MongoDB 오류 → DB 작업 재시도, 카운터 확보 1회, 차감 1회
- 자원 1개에 사용자 8명 동시 요청 → 지급/차감/완료 기록 각 1건, 나머지 포인트 보존
- 커밋 후 결과 불명확 주입 → 지급 데이터와 카운터 유지
- 카운터 반환 실패 → DB 롤백 유지, 원래 오류와 반환 오류 보존

## 남은 한계와 복구

MongoDB와 Redis 사이의 분산 트랜잭션은 아니다. 카운터 확보 뒤 프로세스가 강제 종료되거나 Redis의 응답이 유실되면 예약 수량이 남을 수 있다.
로그에 남는 counterKey, userId와 UserAction/Resource를 확인하여 보상 성공 여부를 판단해야 한다. 특히 무한 반복 보상은 별도 실행 ID/예약 이력이 없어 자동 판별이 충분하지 않다. 영속적인 지급 ID/예약 이력 및 재정합 작업은 아직 구현하지 않았다.
기존에 발생한 포인트 손실/카운터 불일치 데이터는 자동 수정하지 않는다.
