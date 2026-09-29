// mongosh 'mongodb://localhost:27017/mini_kec?replicaSet=rs0' --file scripts/ensure-point-unique-index.js
// 모든 대상의 중복을 먼저 검사한다. 데이터 삭제나 병합은 하지 않는다.
const collections = db.getCollectionNames().filter(name => name.endsWith("_userPoint"));
for (const name of collections) {
    const duplicate = db.getCollection(name).aggregate([
        {$group: {_id: {userId: "$userId", pointKey: "$pointKey"}, count: {$sum: 1}}},
        {$match: {count: {$gt: 1}}},
        {$limit: 1}
    ]).hasNext();
    if (duplicate) throw new Error("Duplicate point data; index creation stopped: " + name);
}
for (const name of collections) {
    const collection = db.getCollection(name);
    const before = collection.countDocuments({});
    collection.createIndex({userId: 1, pointKey: 1}, {unique: true, name: "uk_userId_pointKey"});
    print(JSON.stringify({collection: name, countBefore: before, countAfter: collection.countDocuments({}),
        index: collection.getIndexes().find(index => index.name === "uk_userId_pointKey")}));
}
