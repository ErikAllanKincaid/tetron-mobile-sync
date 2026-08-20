// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.bridge

/** One bridge call, uncached. Production impl is [ProviderStatusCaller];
 *  tests use fakes (MeshBridgeCacheTest). */
fun interface StatusCaller {
    fun call(): BridgeResponse
}

/**
 * SYNC-003: the bridge client UI and gates talk to. A short TTL cache so
 * polling screens do not hammer the provider on every recomposition; every
 * response kind is cached for the TTL (5s default -- the provider itself
 * refreshes on a ~30s tick, so repeated calls inside 5s would return the
 * same answer anyway). Thread-safe: the cache is guarded, so concurrent
 * pollers collapse onto one provider call.
 */
class MeshBridge(
    private val caller: StatusCaller,
    private val cacheTtlMillis: Long = DEFAULT_CACHE_TTL_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var cachedAtMillis: Long? = null
    private var cachedResponse: BridgeResponse = BridgeResponse.Unavailable

    @Synchronized
    fun current(): BridgeResponse {
        val now = nowMillis()
        val cachedAt = cachedAtMillis
        if (cachedAt != null && now - cachedAt < cacheTtlMillis) return cachedResponse
        val fresh = caller.call()
        cachedAtMillis = now
        cachedResponse = fresh
        return fresh
    }

    companion object {
        const val DEFAULT_CACHE_TTL_MILLIS = 5_000L
    }
}