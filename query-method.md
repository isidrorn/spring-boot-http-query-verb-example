# The HTTP `QUERY` method

This repo is the submission for a "mini Doodle" scheduling-backend take-home (see
[`README.md`](README.md) for the actual application — domain model, API, how to run it). Separately,
it also doubles as a playground for the HTTP `QUERY` method
([draft-ietf-httpbis-safe-method-w-body](https://www.ietf.org/archive/id/draft-ietf-httpbis-safe-method-w-body-09.html)):
`QUERY /api/users/{userId}/slots` is the one route that uses it, filtering slots by `status`/
`from`/`to` in a request body instead of query-string parameters.

This file is the front door for that side of the project — why it's here, how it's wired, and what
it took to document a non-standard HTTP method in Swagger UI. It's split out from the README so the
README can stay focused on the scheduling app itself; nothing here is required reading to understand
or run that app.

## Why QUERY?

`GET` cannot carry a body (by convention, and many proxies strip it). `POST` carries a body but is
neither safe nor idempotent. `QUERY` fills that gap: a **safe, idempotent, cacheable** read
operation that needs a structured filter in the request body — exactly the case of "search my
calendar slots between these dates, with this status."

## How it's wired

Spring's `HttpMethod` is an open value object (`HttpMethod.valueOf("QUERY")`), so routing on it
doesn't require a custom annotation or touching `RequestMappingHandlerMapping`. This project uses
**Functional Route Definitions** (`WebMvc.fn`) instead of `@RestController` — not just for the
`QUERY` route, but for the whole API, so every route reads consistently side-by-side:

```java
route()
    .GET(SLOTS,  accept(APPLICATION_JSON), slots::listAll)
    .route(method(QUERY).and(path(SLOTS)).and(accept(APPLICATION_JSON)), slots::query)
    .POST(SLOTS, contentType(APPLICATION_JSON), slots::create)
    .build()
    .filter(routerExceptionFilter::filter);
```

See [`SlotRouterConfig`](src/main/java/io/irn/minidoodle/web/SlotRouterConfig.java) and
[`SlotHandler`](src/main/java/io/irn/minidoodle/web/SlotHandler.java).

Other ways this could have been done (and why they were discarded here):
- **Servlet filter rewriting `QUERY` → `POST`**: works on any Spring version but is a workaround,
  hides the real method from logs/metrics/tracing.
- **Custom `RequestCondition` + custom annotation**: closer to `@RestController` ergonomics, but
  needs extra infrastructure (a custom `HandlerMapping`) for a single extra verb.
- **Functional routes (chosen)**: standard Spring API, no custom infra, and it reads cleanly next
  to `GET`/`POST` on the same resource.

`@RestControllerAdvice` does not intercept exceptions thrown from a `HandlerFunction` (it only
targets `HandlerMethod`/`@Controller`), so error handling is done by a `HandlerFilterFunction`
chained onto the `RouterFunction` instead — see
[`RouterExceptionFilter`](src/main/java/io/irn/minidoodle/web/RouterExceptionFilter.java).

Embedded Tomcat accepts `QUERY` on the wire; this is verified end-to-end with `TestRestTemplate`
(a real socket, not MockMvc) — see [`SlotRouteIT`](src/test/java/io/irn/minidoodle/web/SlotRouteIT.java).

### The Content-Length gotcha

Some HTTP clients (including `TestRestTemplate`, used in this project's own tests) don't set a
`Content-Length` header for a body sent with a non-standard method like `QUERY`, even when a body is
genuinely present. Don't gate body parsing on `Content-Length` being present — just attempt to parse
the body, and treat a genuinely empty/absent one as "no filter" via the parse failure itself. A
*present but malformed* body (bad JSON, an unparseable date, an invalid enum) still has to 400, not
be silently folded into "no filter" — see
[`SlotHandler.parseFilter`](src/main/java/io/irn/minidoodle/web/SlotHandler.java) for both halves of
that distinction.

## Documenting QUERY in Swagger UI / `/api-docs`

The short version: springdoc-openapi and swagger-core (this project's pinned versions) can only
represent the 8 classic HTTP methods in their Java object model — there's no `QUERY` constant and no
generic "any other method" field, so there's no annotation-based way to make it show up in
`/api-docs` automatically, and getting there at all first required fixing an unrelated pre-existing
bug where springdoc's route introspection 500'd on encountering `QUERY` at all. The eventual fix
works below the Java object model entirely: a servlet filter post-processes the already-serialized
`/api-docs` JSON, promoting a hand-built `x-query` extension into a real `query` key and bumping the
declared `openapi` version to `3.2.0` — the exact two things this project's bundled Swagger UI build
(5.32.2, confirmed by reading its actual bundled JS) needs to render it as a normal operation card,
even though the backend tooling doesn't understand OpenAPI 3.2 yet.

That's a compressed summary of a long investigation with several dead ends (including a proposed fix
from an external AI tool that turned out not to work, evaluated and debunked with evidence rather
than taken on faith). The full story, in two forms:

- [`design-decisions-v2.md`](design-decisions-v2.md#why-query-itself-has-no-live-api-docs-entry-and-how-its-documented-instead) —
  the narrative version: what was tried, why each attempt fell short, and the reasoning behind the
  fix that worked.
- [`troubleshooting.md`](troubleshooting.md) — the same ground organized as a quick-reference,
  numbered by error: symptom → root cause → fix → how it was verified, for all 14 errors hit along
  the way (not just the QUERY-specific ones).
- [`query-endpoint.openapi.yaml`](query-endpoint.openapi.yaml) — a standalone, hand-authored OpenAPI
  document for just this one route, with fuller examples and parameter notes than fit in
  `/api-docs`'s generated schema.

**How important is this, given Swagger UI already works for the other 12 routes?** Low-stakes by
design: this was pursued as a documentation-completeness exercise once the QUERY-caused 500 was
already fixed, not because the app was left in a broken state without it.
[`api-examples.md`](api-examples.md) and [`demo.sh`](demo.sh) both fully document and exercise the
`QUERY` route with runnable `curl` examples regardless of what Swagger UI can render.
