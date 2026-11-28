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
- No compatibility bridge that leaves the throw-based methods callable. This is a source and binary break for
  `IdempotencyStore` implementers and belongs in the next major release.
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
- `Available(null)` from `lookup` means the key is absent; `Unavailable` means the store could not answer. Pelican
  never treats unavailability as absence or attempts lookup-release-reserve recovery.
- An unavailable reserve answers a documented 503 tagged `idempotency_store_unavailable`, with `Retry-After: 1`;
  the handler has not run, so an opted-in default retry transport may safely send the keyed request again.
- An unavailable completion or release answers a documented 500 tagged `idempotency_completion_unknown`, without
  `Retry-After`; the handler has run, so Pelican must not invite an automatic retry that could repeat its effect.
- Pelican never calls `release` after an unavailable completion. If completion did not commit, releasing would
  prematurely free the in-flight reservation; if it did, the completed row remains replayable. The ambiguous state
  is left untouched, producing either a replay or an in-flight reservation until its lease expires.
- Every keyed endpoint documents both failures beside its existing 409 and 422.
- Store implementations return `Unavailable`; they do not throw operational failures.

## Why this shape

A Pelican-owned sealed result keeps the failure explicit without adding Surefold or Arrow to core. Reusing
`Outcome` would mix persistence availability with an endpoint's declared response identity. Letting stores throw
keeps the current hidden contract. The small local result lets Pelican alone translate it to HTTP.

## Stack

- [ ] **`spec-0109-core`** — add `StoreResult`; migrate the store, in-memory implementation, keyed wrapper and ABI.
      Done when: all operations are exhaustive typed results; tests pin pre-handler 503, post-handler 500,
      `Available(null)`, both lost-completion states, and a retryable handler failure plus unavailable release.
- [ ] **`spec-0109-contract`** — declare and import the two new responses, including generated fixtures.
      Done when: old 409/422 documents still import, new 500/503 responses round-trip, and collisions with an
      endpoint's own responses are explicit and tested.
- [ ] **`spec-0109-docs`** — update the idempotency reference and changelog.
      Done when: the public contract describes the four typed operations and the post-handler retry boundary.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Decisions

1. If the handler fails and `release` is unavailable, answer the non-retryable completion-unknown 500 and retain
   both causes for reporting. The handler may have committed an effect before producing a retryable `ApiException`;
   preserving that response could repeat the effect.
2. Completion failure is a 500 rather than a 503. Pelican's default client retries 503 for a keyed request but
   deliberately does not retry 500; once the handler ran, the response is unknown rather than safely unavailable.
   Reserve failure remains a retryable 503 with `Retry-After: 1`.
3. Doorlist migrates in a separate PR after the Pelican API lands, under Doorlist's own spec and dependency controls.
4. Status 500 prevents Pelican's default retry policy from retrying the completion-unknown response; `Retry-After`
   does not decide status eligibility. A custom policy may retry it, and a lost transport response has no status to
   inspect, so an opted-in transport may retry that failure. After lease expiry, replay is not guaranteed if
   completion did not commit.
5. The SPI break is deliberate: retaining bare compatibility methods would retain the hidden failure channel. The
   ABI change is recorded in the core slice and released only with the next major version.

## Open questions

None.
