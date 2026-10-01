// Read-only diagnostic. See docs/reward-reconciliation.md for invocation and limits.
(() => {
    const required = name => {
        const value = process.env[name];
        if (!value || value.trim() !== value || /[\0.$]/.test(value)) {
            throw new Error(`${name} must be nonempty without surrounding whitespace, NUL, dot or dollar sign`);
        }
        return value;
    };
    const integer = (name, fallback, min, max) => {
        const raw = process.env[name] ?? String(fallback);
        const value = Number(raw);
        if (!/^\d+$/.test(raw) || !Number.isSafeInteger(value) || value < min || value > max) {
            throw new Error(`${name} must be an integer from ${min} to ${max}`);
        }
        return value;
    };
    const gameKey = required('KEC_GAME_KEY');
    const eventKey = required('KEC_EVENT_KEY');
    const minAgeMinutes = integer('KEC_PENDING_MINUTES', 10, 1, 525600);
    const limit = integer('KEC_PENDING_LIMIT', 100, 1, 1000);
    const maxTimeMS = integer('KEC_QUERY_TIMEOUT_MS', 5000, 1, 30000);
    if (['admin', 'config', 'local'].includes(db.getName())) {
        throw new Error('Select the application database explicitly in the MongoDB URI');
    }
    const collectionName = `${gameKey}_${eventKey}_rewardExecution`;
    if (db.getCollectionInfos({name: collectionName}).length !== 1) {
        throw new Error(`Reward execution collection does not exist: ${collectionName}`);
    }
    const observedAt = new Date();
    const cutoff = new Date(observedAt.getTime() - minAgeMinutes * 60000);
    // Limit + 1 detects truncation without a separate, potentially expensive count.
    // No writes, index creation, TTL changes, Redis calls or automatic retries.
    const records = db.getCollection(collectionName).find(
        {status: 'PENDING', createdAt: {$type: 'date', $lte: cutoff}},
        {_id: 1, userId: 1, actionId: 1, requestId: 1, createdAt: 1}
    ).sort({createdAt: 1, _id: 1}).limit(limit + 1).maxTimeMS(maxTimeMS).toArray();
    print(JSON.stringify({
        database: db.getName(), collection: collectionName,
        observedAt: observedAt.toISOString(), cutoff: cutoff.toISOString(),
        minAgeMinutes, limit, maxTimeMS,
        hasMore: records.length > limit,
        returnedCount: Math.min(records.length, limit),
        assessment: 'REVIEW_REQUIRED_NOT_CONFIRMED_FAILURE',
        warning: 'Age does not prove failure or Redis reservation. Do not delete or release automatically. Missing or invalid createdAt values are excluded.',
        requests: records.slice(0, limit).map(record => ({
            id: String(record._id), userId: record.userId, actionId: record.actionId,
            requestId: record.requestId, createdAt: record.createdAt.toISOString(),
            ageSeconds: Math.floor((observedAt.getTime() - record.createdAt.getTime()) / 1000)
        }))
    }));
})();
