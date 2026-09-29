# 참여 트랜잭션 실행 가이드

## 로컬 MongoDB 준비

개발용 1노드 Replica Set 구성이다. 고가용성 구성은 아니다.
기존 mongodb_data 볼륨은 그대로 사용한다. `docker compose down -v`는 데이터를 삭제하므로 실행하지 않는다.

```bash
docker compose up -d mongodb redis
```

MongoDB가 시작된 후 최초 1회 초기화한다. 이미 rs0이면 재초기화하지 않는다.

```bash
docker exec mini-kec-mongodb mongosh --quiet --eval 'try { printjson(rs.status().ok) } catch(e) { if(e.code === 94) printjson(rs.initiate({_id:"rs0",members:[{_id:0,host:"localhost:27017"}]})); else throw e; }'
mongosh 'mongodb://localhost:27017/?replicaSet=rs0' --quiet --eval 'printjson(db.hello())'
```

`setName: rs0`, `isWritablePrimary: true`를 확인한 후 앱을 실행한다.
멤버 주소 localhost:27017은 호스트에서 실행하는 앱을 위한 설정이다. 앱을 별도 컨테이너로 옮기면 주소 구성도 변경해야 한다.

## 저장 흐름

1. 이벤트 검증 및 기존 참여자 조회
2. 트랜잭션 밖에서 이벤트별 user / user_point 컬렉션 및 인덱스 준비
3. 새 트랜잭션에서 이벤트 기간 재검증 → 사용자 저장 → 모든 초기 포인트 저장 → 커밋
4. 사용자 중복 저장 오류라면 롤백 후 트랜잭션 밖에서 기존 참여자를 조회하여 반환

사용자 저장 Adapter를 직접 호출할 때는 먼저 `ParticipationUnitOfWork.prepare`로 스키마를 준비해야 한다.
포인트 컬렉션의 userId + pointKey 인덱스는 조회용이며 Unique Index는 아니다.
포인트 저장 실패는 사용자 중복 참여로 바꾸지 않는다. 사용자와 먼저 저장된 포인트도 롤백한다.
TransientTransactionError는 새 트랜잭션으로 최대 5회 실행하며 재시도 사이에 200/400/800/1600ms 대기한다. UnknownTransactionCommitResult는 전체 작업을 재실행하지 않고 오류를 전달한다. 커밋 성공 여부가 불명확한 오류는 성공/롤백을 단정하면 안 된다.
현재 트랜잭션에는 MongoDB 저장만 포함된다. 외부 API 호출은 넣지 않는다.
기존에 생성된 포인트 누락 데이터는 이 변경으로 자동 복구되지 않는다.

## 테스트

```bash
MINIKEC_MONGO_TEST_URI='mongodb://localhost:27017/?replicaSet=rs0' ./gradlew test --rerun-tasks --console=plain
open build/reports/tests/test/index.html
```

통합 테스트는 임시 DB를 만들고 테스트 종료 시 그 DB만 삭제한다.
동시 참여 8개, 두 번째 포인트 실패 시 전체 롤백, 실패 후 재참여, 트랜잭션 재시도 제한을 검증한다.

## 재참여 응답

일반 재참여와 중복 저장 복구 모두 사용자별 현재 포인트를 조회해 반환한다. 조회는 해당 game/event 컬렉션에서 userId로 제한하며 pointKey 순으로 정렬한다. 저장된 포인트가 없으면 빈 목록을 반환하고 자동 초기화하지 않는다. 신규 참여 응답의 포인트 순서는 이벤트 정의 순서를 유지한다.
