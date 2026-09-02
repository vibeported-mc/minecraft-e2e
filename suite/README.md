# :suite

A driver: what a test is, how long one may take, what a report looks like, and what happens when one
fails.

Deliberately replaceable. Nothing in `:core` or `:dsl` knows this module exists, and it reaches the
game through the same `server { }` and `client { }` calls a test does, with no privileged access to
anything. A JUnit console launcher could take its place without the framework noticing.

## What is in it

| | |
|---|---|
| `Suite.kt` | `suite("name") { e2e("name") { ... } }` -- collects tests, and nothing more |
| `Runner.kt` | Runs them, one at a time, with a timeout; catches assertion failures and remote invocation failures apart from crashes |
| `Report.kt` | The model: per test an outcome, a duration, the log lines each node emitted, and the failure if there was one |
| `Reporters.kt` | Writes it -- `report.json` beside the run, and the live PASS/FAIL lines |

## How a run ends up in the report

The orchestrator calls a `main` once the cluster is up (`:example` names one), that main calls
`Runner.run(suite)`, and from there it is ordinary code: each `e2e` body is a suspend function whose
`server { }` and `client { }` calls become round trips.

Log lines are interleaved by time rather than grouped by node, because a test that failed is usually
a story about what two processes did to each other in what order.

A failing client block is photographed on its way out -- that hook lives in `:core` as
`FailureArtifacts`, and `:dsl` is what registers something that knows how to take a picture.
