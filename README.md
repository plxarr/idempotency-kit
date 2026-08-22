# Idempotency Kit

Idempotency for Spring Boot 3.x, backend agnostic.

Annotate a method with a SpEL key. The first call runs; repeats with the same key get its
cached result back without executing again — or wait for it, or are rejected, while it's
still in flight. State lives in Redis, Caffeine, or anything you write an adapter for.

Errors are part of the deal: a **pluggable exception strategy** lets you decide which
failures are cached and replayed, and which stay retryable.

## Install

Pick the adapter for the cache you already run. Each one brings `idempotency-kit-core` with
it, so it's a single dependency:

```xml
<dependency>
  <groupId>io.github.plxarr</groupId>
  <artifactId>idempotency-kit-redis</artifactId>
  <version>1.0.0</version>
</dependency>
```

| Artifact | Use it when |
|---|---|
| `idempotency-kit-redis` | You run Redis (or Valkey, KeyDB, Garnet, Dragonfly, DiceDB). **The only option with a real lock across a cluster.** |
| `idempotency-kit-caffeine` | Single instance, or tests. In-process. |
| `idempotency-kit-core` | Only if you're writing your own adapter. |

Requires **Java 21+** and **Spring Boot 3.4+**.

## Quick start

### 1. Declare a manager — a backend plus the defaults

```java
@Configuration
public class IdempotencyConfig {

  @Bean
  public IdempotencyManager idempotencyManager(StringRedisTemplate redis) {
    return IdempotencyManager.builder()
        .storage(new RedisIdempotencyStorage(redis))
        .defaultTtlMs(30_000)
        .defaultOnConcurrent(ConcurrentStrategy.WAIT)
        .build();
  }
}
```

**`.storage(...)` is the only required setting.** Leaving any other one out is never an
error — it just takes its default:

| Setting | Default           | Meaning |
|---|-------------------|---|
| `storage` | **required**      | `build()` throws without it |
| `defaultTtlMs` | `300_000` (5 min) | How long a result stays cached — and how long a crashed holder can block the key |
| `defaultOnConcurrent` | `REJECT`          | A duplicate arriving mid-flight is turned away rather than made to wait |
| `defaultConcurrentWaitTimeoutMs` | `10_000` (10 seg) | Wait budget for `WAIT` |
| `defaultExceptionStrategy` | caches nothing    | Failures propagate and stay retryable |

Swapping the backend means swapping one line:

```java
// In-process, for a single instance or tests. Honours the same per-call TTL as Redis.
.storage(new CaffeineIdempotencyStorage())

// Same, with the cache sized to taste. Don't set an expiry: each entry gets its own.
.storage(new CaffeineIdempotencyStorage(Caffeine.newBuilder().maximumSize(50_000)))
```

Declare several managers if you want different backends or defaults, and pick one per method
with `manager = "..."`. With more than one and no `@Primary`, that attribute is required.

### 2. Annotate the method

```java
@Idempotent(key = "#request.email", ttlMs = 30_000)
public PersonDto createPerson(CreatePersonRequest request) { ... }
```

### 3. Map the exceptions

```java
@RestControllerAdvice
public class IdempotencyExceptionHandler {

  @ExceptionHandler(IdempotencyConflictException.class)
  ProblemDetail onConflict(IdempotencyConflictException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
  }

  @ExceptionHandler(IdempotencyKeyException.class)
  ProblemDetail onBadKey(IdempotencyKeyException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
  }
}
```

Runnable versions of all of this: **[idempotency-kit-demo](https://github.com/plxarr/idempotency-kit-demo)**.

## `@Idempotent`

| Parameter | Default | What it does |
|---|---|---|
| `key` | **required** | SpEL over the method arguments — what makes two calls "the same call" |
| `manager` | the only one / `@Primary` | Which `IdempotencyManager` bean to use |
| `ttlMs` | from the manager | How long the result stays cached, and the ceiling on a stuck lock |
| `onConcurrent` | from the manager | `REJECT` or `WAIT` when a duplicate arrives mid-flight |
| `concurrentWaitTimeoutMs` | from the manager | Wait budget for `WAIT` |
| `onException` | from the manager | Strategy class deciding which failures get cached |

Leave an attribute out and it inherits from the manager. That's what the `-1` on the numbers
and `UNSET` on the enum mean — you never write those yourself.

### `key` — what makes two calls the same call

A SpEL expression over the method's arguments, coerced to a String. Two calls with the same
key are the same operation.

```java
@Idempotent(key = "#requestId")            // a parameter
@Idempotent(key = "#request.email")        // a field of a parameter
@Idempotent(key = "#request?.email")       // safe navigation: null request -> key error
```

Referring to parameters by name needs `-parameters` at compile time. Spring Boot's parent POM
enables it; if you don't use that parent, set it yourself or the expression won't resolve.

**A key that resolves to null or blank throws `IdempotencyKeyException`, before the method
runs.** There is no "let it through unlimited" mode: without a key there is nothing to
deduplicate on, and silently executing would defeat the annotation. Note the difference
between `#request.email` and `#request?.email` — the first *fails the expression* on a null
request, the second yields null and gives you the key error instead.

### `onConcurrent` — a duplicate arrives while the first is still running

| Value | Behaviour |
|---|---|
| `REJECT` (default) | Throws `IdempotencyConflictException` immediately. Map it to `409`. |
| `WAIT` | Polls until the first call finishes, then returns its result — or rethrows its cached error. Polling backs off exponentially from 20 ms, capped at a tenth of the wait budget. |

`WAIT` is what you want when the caller is a client that will retry anyway: it turns a
duplicate into a slow success instead of an error the client has to handle. `REJECT` is
cheaper and honest when the caller can cope with a `409`.

When the wait budget runs out, `WAIT` also throws `IdempotencyConflictException`. So does a
wait whose in-flight operation vanishes — the node running it died and its lock expired.

### `onException` — which failures are remembered

By default **nothing is cached**: a failure propagates and the key stays retryable, as if the
call had never happened. That's the safe default, and usually what you want for a timeout or
a downstream outage.

Business errors are different. If a payment is declined, replaying the decline is more correct
than re-running the charge. Implement three methods and the library replays it:

```java
@Component
public class DeclineStrategy implements ExceptionHandlingStrategy {

  public boolean shouldCache(Throwable ex) { return ex instanceof PaymentException; }
  public String  serialize(Throwable ex)   { return ((PaymentException) ex).getReason().name(); }
  public Throwable deserialize(String s)   { return new PaymentException(Reason.valueOf(s)); }
}
```

```java
@Idempotent(key = "#request.id", onException = DeclineStrategy.class)
public Receipt charge(ChargeRequest request) { ... }
```

The library never deserializes arbitrary types by class name — that would be a
remote-code-execution shaped hole in something that reads from a shared cache. You reconstruct
your own exceptions instead, from whatever you chose to store.

## Behaviour worth knowing

**It only works through the Spring proxy.** The stereotype doesn't matter — `@Service`,
`@Component`, a class registered with `@Bean`, all the same. What is never advised: an object
you `new` up, a `static` method, or an internal `this.method(...)` call from inside the same
class. Same rule as `@Transactional`.

**The TTL covers the lock too, not just the result.** One value governs both, so if the
process holding a key dies mid-flight, the key frees itself when that TTL expires — no manual
cleanup, but also no faster recovery than the TTL you chose. Keep it long enough to be a
useful cache and short enough to be a tolerable outage for one key.

**The result is returned, never reinvented.** A cached hit is deserialized against the
method's actual generic return type, so `List<Person>` comes back as `List<Person>` and not
`List<LinkedHashMap>`. Primitives, generics, `void` and a genuine `null` result all round-trip
correctly — a cached `null` is a hit, not a miss.

**Quota-free retries after an uncached failure.** When the exception strategy declines to
cache an error, the lock is released, so the next call is a real retry rather than a replay.

## Choosing a backend

All adapters use **one entry per key that evolves state**: `PROCESSING` → `RESULT:…` /
`ERROR:…`. The lock *is* the `PROCESSING` entry, with a token embedded in it so a release only
ever removes its own lock — never one that has since been taken by someone else.

- **Redis and compatible** — a real distributed lock via `SET NX PX`, released through an
  atomic Lua script that checks the token first. The one to use across several instances.
- **Caffeine** — exclusion within one JVM only. Fine for a single instance and for tests;
  across N instances, N callers can each think they hold the lock.

Any backend with atomic compare-and-set and TTL expiry can be an adapter: implement
`IdempotencyStorage`'s four methods — `get`, `store`, `acquireLock`, `releaseLock`.

## Exceptions

| Exception | Meaning | Map to |
|---|---|---|
| `IdempotencyKeyException` | The key resolved to null or blank | `400` |
| `IdempotencyConflictException` | A duplicate is in flight (`REJECT`), or the wait ran out | `409` |
| `IdempotencyConfigurationException` | Ambiguous manager, or none defined | — (startup bug) |
| `IdempotencySerializationException` | A result couldn't be cached or read back | — (bug) |

All extend `IdempotencyException`, so you can catch the family in one handler if you prefer.

## Building it

```bash
mvn install
```

`mvn test` runs the unit suite and needs nothing. The Redis integration tests run under
`mvn verify` and skip themselves unless something is listening on port **6380** (deliberately
not 6379, to stay clear of whatever you already have running):

```bash
docker run -d --name idempotency-kit-redis -p 6380:6379 redis:7-alpine
```
