# 0108 — Count a document's `$ref`s once, not once per tagged failure

## Problem

`declareTags` (spec 0066) checks that each tagged failure's component has
exactly one `$ref` per tagged use. For that, `refsTo` walks every rendered
operation once for **each** tagged component. The cost is the number of
tagged components times the size of the document, and it is paid on every
`openApi()` call: `openApiJson`, `docsHandler`, audience projections and
`changesFrom`.

A real service sees this. Doorlist has a few hundred operations and many
tagged failures. In its suites that build its documents (an api's golden
files, the operation-ownership graph and the local path checks), JFR puts
`refsTo` at 27% of the test JVM's CPU: 314 of 1,147 samples, and 87% of the
time inside `openApi`. Before Doorlist stopped rendering its documents until
one is asked for, `refsTo` was 17% of the CPU of its whole api test run.

## Not doing

- The check itself. The same documents are refused, with the same messages.
- Caching a rendered document, or rendering it lazily. That is the caller's
  choice: Doorlist already renders on request.
- Anything else in `openApi`. Once this is fixed, nothing else in it stands
  out in the profile.

## Shape

No API change. Inside `declareTags`, one walk counts every `$ref` by target,
and each component reads its count from that map:

```kotlin
val refs = refCounts(rendered) // Map<String, Int>, one walk of the document
// ...
require((refs["$COMPONENT_PREFIX$name"] ?: 0) == uses.size) { /* message as today */ }
```

`refCounts` counts a `$ref` key whose value is a string, and walks into
everything else, as `refsTo` does now. So a discriminator's mapping, which
names the component as a value, is still not counted.

## Why this shape

The count was already a property of the whole document. Walking it once
makes the check linear in the document's size, and leaves the walk's
definition of a reference as it is. The alternative, counting `$ref`s while
rendering, would cost less but would tie the check to the renderer's
internals. Recommend the separate walk.

## Stack

- [x] **`spec-0108-ref-counts`** ([#478](https://github.com/surefold/pelican/pull/478)): `refCounts` in place of `refsTo` in
      `TaggedComponents.kt`, and a test.
      Done when: `TaggedFailuresDocumentTest` passes unchanged, and a new
      case shows that a component referenced from many operations under
      many tags renders the same document, with the same refusals, as
      before.

## Acceptance

```bash
./gradlew :pelican-openapi:test
./gradlew build
```

The prototype was checked against Doorlist. With the change compiled into
pelican-openapi 2.0.0, `ApiByAreaGoldensSuite`, `OperationOwnershipGraphSuite`
and `OperationalPathsSuite` all passed with byte-identical golden documents.
`declareTags` samples fell from 332 to 21, and the JVM's from 1,147 to 822.

## Open questions

1. Release it as 2.0.1, or wait for the next minor? Recommend 2.0.1: it is
   internal, and it is all a caller notices.
2. Should a benchmark under `benchmarks/` hold the gain? Recommend no: a
   test that would fail with a quadratic walk is enough. For example, time
   a generated spec of a few thousand operations against a generous bound.
