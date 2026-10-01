# 리워드 PENDING 진단 — 복구 준비 단계

## 목적과 범위

`PENDING`은 실행 중, DB 커밋 결과 불명확, Redis 응답 유실, 프로세스 중단 등 여러 상황에서 남을 수 있다.
오래됐다는 사실만으로 실패나 미지급을 확정할 수 없다.
이번 단계는 오래된 요청을 찾는 읽기 전용 진단이다. 자동 복구, 요청 삭제, Redis 반환, 지급 재실행은 수행하지 않는다.

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

현재 Redis에는 요청별 예약 토큰이 없고, UserAction/Resource와 requestId의 직접 연결도 충분하지 않다.
같은 사용자의 다른 정상 지급이 있을 수 있으므로 사용자 ID와 총 카운트만으로 이 요청의 지급 여부를 확정하지 않는다.
자동 복구를 구현하려면 요청별 예약 증거와 실행 종료 여부를 보장하는 절차가 먼저 필요하다.
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
