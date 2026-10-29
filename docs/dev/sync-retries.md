# Retrying requests lost on the way

On a bad network the sync sometimes fails with `java.net.SocketException: Broken pipe` (the server
closed the connection while the app was writing to it), `Connection reset`, `unexpected end of
stream` or a timeout. These are accidents of the network, not answers of the server, and sending
the request again usually works.

## Why it has to happen in the data source

The FHIR library (engine 1.2.0) counts **every** failure it hears of against the sync, and a sync
with one failure is repeated from the start by WorkManager (3 times, 30 s apart), however the
request ended. Retrying a request later (for instance when the download manager is asked for the
next one) recovers the data but not the status: the pass still ends as failed and everything is
downloaded again (about 5 minutes for the first download). So the request is sent again *inside* the
call, before the library hears of the failure.

## How

- `RetryingFhirSyncWorker` (Java) is the base of `BaseFhirSyncWorker`. It overrides
  `FhirSyncWorker.getDataSource()` and wraps the library's data source in `RetryingRequests`.
  This is Java on purpose: the library declares that method and the `DataSource` interface `internal`,
  so Kotlin outside the library cannot override or implement them, but in the compiled library they
  are public (`getDataSource$engine_release`). If a newer library changes that, the file stops
  compiling (the `@Override` finds nothing) instead of the retries silently going away.
- `RetryingRequests` (Kotlin, `Requests` is its public interface) sends a request again up to 3
  times, after 2, 5 and 10 s, and at most 20 times in one sync (a server that is down must not hold
  the sync for every request). Cancelling the sync is never retried.
- `SyncRetries` tells what is worth sending again:
  - **downloads** (they only read): any request lost on the way (broken pipe, reset, unexpected end
    of stream, timeout, cannot connect) and the answers 408, 429, 500, 502, 503, 504;
  - **uploads** (they create and change records): only the failures that prove the server never got
    the request (`Broken pipe`, cannot connect, unknown host). After a reset in the middle of an
    exchange the server may have done the work, and sending a POST again could create it twice, so
    the sync fails as before and the next sync sends it.

Each retry is logged (`A request of the download was lost on the way (Connection reset): sending it
again in 5 s (2 of 3): ...`), so a bad network can be told from the diagnostics log.

## Checked

Unit tests (`SyncRetriesTest`, `RetryingRequestsTest`), and on the emulator against the real backend
through a proxy that reset the first three attempts of some requests: each was sent again after 2, 5
and 10 s and the 4th went through, and the first sync finished in one pass (`LocationFhirSyncWorker`
SUCCESS, no `RETRY`). Without the retries the same run restarted the whole download 4 times.
