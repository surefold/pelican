# 0109 — Idempotency store failures are values

## Problem

`IdempotencyStore` is a blocking persistence SPI whose four methods return bare values. A database-backed store
therefore has to throw when its database cannot reserve, complete, inspect or release a key. Pelican treats that as
an undeclared server error, so the SPI hides a failure every real shared implementation must have.

Doorlist hits this with `PostgresIdempotency`: JDBC failures escape its Pelican adapter and reach the catch-all 500
handler. The store contract should make unavailability explicit and Pelican should decide the HTTP response.

## Not doing

- No Postgres adapter in Pelican; persistent implementations remain in service or integration modules.
- No retry inside Pelican. A store owns atomicity; the caller decides whether and when to retry the request.
- No Surefold dependency in `pelican-core`.
- No change to the existing 409 in-flight or 422 reused-key semantics.

## Shape

```kotlin
sealed interface StoreResult<out A> {
    data class Available<A>(val value: A) : StoreResult<A>
    data class Unavailable(val cause: Throwable) : StoreResult<Nothing>
}

interface IdempotencyStore {
    fun reserve(scope: IdempotencyScope, fingerprint: String, lease: Duration): StoreResult<Reservation>
    fun complete(scope: IdempotencyScope, response: StoredResponse, ttl: Duration): StoreResult<Unit>
    fun lookup(scope: IdempotencyScope): StoreResult<Reservation?>
    fun release(scope: IdempotencyScope): StoreResult<Unit>
}
```

- `InMemoryIdempotencyStore` always answers `Available`.
- An unavailable reserve answers a documented 503 tagged `idempotency_store_unavailable`, with `Retry-After: 1`;
  the handler has not run, so the generated client's normal idempotent retry is safe.
- An unavailable completion answers a documented 500 tagged `idempotency_completion_unknown`, without
  `Retry-After`; the handler has run, so Pelican must not invite an automatic retry that could repeat its effect.
- Every keyed endpoint documents both failures beside its existing 409 and 422.
- Store implementations return `Unavailable`; they do not throw operational failures.

## Why this shape

A Pelican-owned sealed result keeps the failure explicit without adding Surefold or Arrow to core. Reusing
`Outcome` would mix persistence availability with an endpoint's declared response identity. Letting stores throw
keeps the current hidden contract. The small local result lets Pelican alone translate it to HTTP.

## Stack

- [ ] **`spec-0109-store-result`** — add `StoreResult`, migrate `IdempotencyStore` and its in-memory implementation.
      Done when: all four operations are exhaustive typed results and a throwing test store is no longer needed.
- [ ] **`spec-0109-serve-unavailable`** — translate unavailable reserve, complete and release results at the wrapper.
      Done when: contract and backend-parity tests pin the pre-handler 503, post-handler 500, handler execution and
      reservation behaviour.
- [ ] **`spec-0109-docs`** — update the reference, OpenAPI golden and changelog.
      Done when: a keyed endpoint documents 409, 422, 500 and 503 and the imported contract still round-trips.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Decisions

1. If the handler throws and `release` is unavailable, preserve the handler failure and attach the store failure for
   the error reporter. The caller already receives an undeclared 500; changing it to 503 would imply the handler had
   not run.
2. Completion failure is a 500 rather than a 503. Pelican's default client retries 503 for a keyed request but
   deliberately does not retry 500; once the handler ran, the response is unknown rather than safely unavailable.
   Reserve failure remains a retryable 503 with `Retry-After: 1`.
3. Doorlist migrates in a separate PR after the Pelican API lands, under Doorlist's own spec and dependency controls.
