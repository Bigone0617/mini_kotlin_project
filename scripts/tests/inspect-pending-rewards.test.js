// Run from repository root: mongosh 'mongodb://localhost:27017/?replicaSet=rs0' --quiet --file scripts/tests/inspect-pending-rewards.test.js
// Creates and drops only a unique test database. Does not select mini_kec.
(() => {
    const originalDb = db;
    const testName = `mini_kec_reward_inspect_test_${new ObjectId()}`;
    const originalPrint = print;
    const envNames = ['KEC_GAME_KEY', 'KEC_EVENT_KEY', 'KEC_PENDING_MINUTES', 'KEC_PENDING_LIMIT', 'KEC_QUERY_TIMEOUT_MS'];
    const originalEnv = Object.fromEntries(envNames.map(name => [name, process.env[name]]));
    let passed = 0;
    const check = (condition, message) => { if (!condition) throw new Error(message); };
    const run = () => {
        const output = [];
        globalThis.print = value => output.push(JSON.parse(value));
        try { load('scripts/inspect-pending-rewards.js'); }
        finally { globalThis.print = originalPrint; }
        check(output.length === 1, 'expected one JSON report');
        return output[0];
    };
    const rejects = action => {
        let failed = false;
        try { action(); } catch (_) { failed = true; }
        check(failed, 'expected validation failure');
    };
    try {
        db = originalDb.getSiblingDB(testName);
        process.env.KEC_GAME_KEY = 'g';
        process.env.KEC_EVENT_KEY = 'e';
        process.env.KEC_PENDING_MINUTES = '10';
        process.env.KEC_PENDING_LIMIT = '100';
        process.env.KEC_QUERY_TIMEOUT_MS = '5000';
        const collection = db.getCollection('g_e_rewardExecution');
        const now = Date.now();
        const row = (id, minutes, status = 'PENDING') => ({_id: id, userId: 'u', actionId: 'reward', requestId: id,
            status, createdAt: new Date(now - minutes * 60000), result: {secret: 'must-not-print'}});
        collection.insertMany([row('oldest', 60), row('old', 20), row('recent', 1), row('completed', 60, 'COMPLETED'),
            {_id: 'missing-date', status: 'PENDING'}, {_id: 'invalid-date', status: 'PENDING', createdAt: '2020-01-01'}]);
        db.getCollection('g_other_rewardExecution').insertOne(row('other-event', 60));
        const before = EJSON.stringify(collection.find().sort({_id: 1}).toArray());
        const indexes = EJSON.stringify(collection.getIndexes());

        let report = run();
        check(report.returnedCount === 2 && report.requests.map(r => r.requestId).join(',') === 'oldest,old', 'age/status/scope/order filter');
        check(report.hasMore === false && !JSON.stringify(report).includes('must-not-print'), 'projection and complete report');
        check(report.assessment === 'REVIEW_REQUIRED_NOT_CONFIRMED_FAILURE', 'must not assert failed');
        passed++;

        process.env.KEC_PENDING_LIMIT = '1';
        report = run();
        check(report.returnedCount === 1 && report.hasMore && report.requests[0].requestId === 'oldest', 'bounded report with truncation');
        passed++;

        process.env.KEC_PENDING_MINUTES = '120';
        report = run();
        check(report.returnedCount === 0 && !report.hasMore, 'empty report');
        passed++;

        for (const [name, value] of [['KEC_PENDING_LIMIT', '0'], ['KEC_PENDING_LIMIT', '1001'], ['KEC_PENDING_LIMIT', '1.5'],
            ['KEC_PENDING_MINUTES', '-1'], ['KEC_QUERY_TIMEOUT_MS', '30001'], ['KEC_GAME_KEY', ''], ['KEC_EVENT_KEY', '$bad']]) {
            const previous = process.env[name];
            process.env[name] = value;
            rejects(run);
            process.env[name] = previous;
        }
        passed++;

        process.env.KEC_EVENT_KEY = 'missing';
        rejects(run);
        check(db.getCollectionInfos({name: 'g_missing_rewardExecution'}).length === 0, 'must not create missing collection');
        process.env.KEC_EVENT_KEY = 'e';
        passed++;

        db = originalDb.getSiblingDB('admin');
        rejects(run);
        db = originalDb.getSiblingDB(testName);
        passed++;

        check(before === EJSON.stringify(collection.find().sort({_id: 1}).toArray()), 'documents changed');
        check(indexes === EJSON.stringify(collection.getIndexes()), 'indexes changed');
        passed++;
        originalPrint(JSON.stringify({passed, database: testName, documentsUnchanged: true, indexesUnchanged: true}));
    } finally {
        globalThis.print = originalPrint;
        originalDb.getSiblingDB(testName).dropDatabase();
        db = originalDb;
        for (const name of envNames) {
            if (originalEnv[name] === undefined) delete process.env[name];
            else process.env[name] = originalEnv[name];
        }
    }
})();
