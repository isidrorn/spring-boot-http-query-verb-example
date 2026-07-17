# Troubleshooting: the HTTP QUERY method

Every error this project actually hit around the HTTP `QUERY` method — routing it, testing it, and
(the bulk of this file) documenting it — in the order they were found, with the real symptom, the
actual root cause once traced, the fix, and how each fix was verified. This is a companion to
[`design-decisions-v2.md`](design-decisions-v2.md), which has the full narrative reasoning; this file
is the quick-reference version organized by *error*, for whenever one of these resurfaces.

---

## 1. Some HTTP clients don't send `Content-Length` for a `QUERY` body

**Symptom**: a client sends a `QUERY` request with a genuine JSON body, but the server-side handler
either fails to read it or treats it as if no body were present — inconsistently, depending on which
HTTP client made the request.

**Root cause**: `QUERY` is a non-standard method as far as most HTTP client libraries are concerned.
Some (including `TestRestTemplate`, used in this project's own integration tests) don't set a
`Content-Length` header for a body sent with a non-standard method, even when a body is genuinely
present. Any code that gates body-parsing on `Content-Length` being present will incorrectly treat a
real body as absent for exactly the clients most likely to be used to call this API.

**Fix**: never check for `Content-Length` before attempting to parse the body. Just attempt the
parse (`request.body(SlotQueryFilter.class)`), and treat a parse failure — not a missing header — as
"no filter." See `SlotHandler.parseFilter()`.

**Verified by**: `SlotRouteIT.querySlots_withNoBody_treatedAsNoFilter_returnsAllSlots` — sends a
`QUERY` request with a `HttpEntity` carrying only headers, no body, and asserts it's treated as
"return everything," not rejected.

---

## 2. `GET /api-docs` and `/swagger-ui.html` return 500

**Symptom**: `GET /api-docs` returns `{"detail":"An unexpected error occurred.","status":500,...}`.
`/swagger-ui.html` itself 302-redirects fine, but the page it redirects to fails to render anything
useful, since it fetches `/api-docs` client-side and gets the same 500.

**Root cause**: `org.springdoc.core.fn.RouterFunctionData.getRequestMethod()` converts each route's
`HttpMethod` to Spring MVC's `RequestMethod` enum via an exhaustive `switch` covering only the 8
classic HTTP methods (GET/HEAD/POST/PUT/PATCH/DELETE/OPTIONS/TRACE). There's no `QUERY` case, and the
`default` branch throws `IllegalStateException: Unexpected value: QUERY` instead of falling back to
anything safe. This exception was invisible in the logs at first — see error #6 below.

**Confirmed pre-existing, not a regression**: reproduced on the commit *before* the v2 refactor that
was in progress at the time, using a throwaway `git worktree` on a separate port. Same 500, same
stack trace. Not something the refactor introduced.

**Fix, part 1 of 2** — `springdoc.paths-to-exclude` does **not** work here (tested directly, not
assumed): it filters the document *after* it's built, and this exception aborts route discovery
before that filtering ever runs. The actual fix needed a replacement `RouterFunctionProvider` — see
error #3 through #5 below for how that got wired in correctly.

**Verified by**: `curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api-docs` against a
live instance (both local H2 and `docker-compose` Postgres), before vs. after.

---

## 3. Replacing springdoc's `RouterFunctionProvider` bean throws `BeanDefinitionOverrideException`

**Symptom**: adding a custom `@Bean` meant to replace springdoc's default route-introspection bean
crashes the whole application at startup with `BeanDefinitionOverrideException: ... bean
'routerFunctionProvider' ... already ... bound`.

**Root cause**: the replacement bean's factory method was named `routerFunctionProvider()` —
identical to springdoc's own bean name. Bean-definition name collisions are resolved during bean
*registration*, which happens before Spring evaluates `@ConditionalOnMissingBean` on springdoc's own
bean method. Same name, hard conflict, regardless of what the conditional would have decided.

**Fix**: give the replacement bean method a different name (`resilientRouterFunctionProvider()`).
Necessary but not sufficient — see error #4.

---

## 4. Renaming the bean fixes the crash, but springdoc still uses its own default

**Symptom**: after fixing error #3 (different bean name), the app starts fine, but `/api-docs` still
500s with the exact same `IllegalStateException: Unexpected value: QUERY` — as if the replacement
provider were never used at all.

**Root cause**: springdoc's own bean method is annotated `@ConditionalOnMissingBean` with no explicit
type — Spring infers the target type from that method's *return type*, which is the concrete class
`RouterFunctionWebMvcProvider`, not the `RouterFunctionProvider` interface. The replacement bean
can't be typed as `RouterFunctionWebMvcProvider` (its `applicationContext` field and visitor inner
class are both `private`, so there's no subclassing hook), so that conditional never sees it as a
match — springdoc's own bean gets registered *in addition to* the replacement, not instead of it.

**Fix, part 1**: this alone doesn't throw an error — with two `RouterFunctionProvider` beans and no
tie-breaker, Spring silently resolved the ambiguity by matching the constructor parameter name in
springdoc's internal `SpringDocProviders` class (literally named `routerFunctionProvider`),
preferring springdoc's bean purely because its name matched, regardless of what the replacement was
named. No exception, no log line — just silently wrong behavior. Found by adding a `log.warn(...)`
inside the replacement provider and observing it never fired.

**Fix, part 2**: add `@Primary` to the replacement bean. This is what actually wins Spring's bean
selection when there are multiple candidates, overriding the parameter-name tie-break.

**Verified by**: the replacement provider's log line (`"Skipping OpenAPI documentation for router
function bean '...'"`) appearing in the startup logs once a request hits `/api-docs`.

---

## 5. `/api-docs` now returns 200, but with `"paths": {}` — every route missing, not just QUERY

**Symptom**: after error #4's fix, no more 500 — but the document is valid and completely empty. Not
just QUERY missing: every route in the application is gone from the spec.

**Root cause**: at this point, every route (QUERY included) lived in a single `RouterFunction` bean.
The replacement provider's per-bean try/catch (from error #4) works at the *bean* level — when the
visitor hits the QUERY route mid-traversal and throws, the whole bean's partial results are
discarded, not just the one bad route. With everything in one bean, "discard the bean" meant
"discard everything."

**Fix**: split the QUERY route into its own `@Bean`, separate from every other route
(`SlotRouterConfig.queryRoute()` vs. `SlotRouterConfig.routes()`). Spring Boot's
`RouterFunctionMapping` auto-configuration combines every `RouterFunction` bean in the context for
actual request dispatch regardless of how many separate `@Bean` methods declare them — so this has
no effect on routing, confirmed by rerunning the full test suite (84/84 green) after the split.

**Verified by**: `GET /api-docs` returning `"paths": {}` before the split, and non-empty after.

---

## 6. `/api-docs` still shows `"paths": {}` even for routes that visited successfully

**Symptom**: after error #5's fix, the non-QUERY `routes` bean visits without throwing — but
`/api-docs` *still* returns `"paths": {}`.

**Root cause**: springdoc's automatic documentation of `RouterFunction` routes isn't actually
automatic. Traced into `AbstractOpenApiResource.getRouterFunctionPaths()`: it only builds real
`Operation` entries for a route already carrying a `.withAttribute(OPERATION_ATTRIBUTE, ...)` marker,
or one matched against a manual `@RouterOperation`/`@RouterOperations` annotation on the bean.
Without either, `mergeRouters()` runs against an empty list of manual operations and silently
produces nothing — independent of the QUERY bug entirely. This project's functional routes would
have documented as empty even before the v2 refactor and even without the QUERY crash; the crash was
just turning that pre-existing silent emptiness into a 500 instead of a valid-but-useless 200.

**Fix**: added a `@RouterOperations` block to `SlotRouterConfig.routes()` with one
`@RouterOperation(path=..., method=..., beanClass=..., beanMethod=...)` entry per non-QUERY route (12
entries).

**Verified by**: `GET /api-docs` returning 7 real path templates covering all 12 operations, grouped
into `user-handler`/`slot-handler`/`meeting-handler` tags — checked the actual JSON, not just that
the app compiled and started.

---

## 7. `RouterOperation.method()` still can't express QUERY

**Symptom**: attempting to add a 13th `@RouterOperation` entry for the QUERY route — no valid value
exists for its `method` attribute.

**Root cause**: `org.springdoc.core.annotations.RouterOperation.method()` is typed
`RequestMethod[]` — the exact same closed 8-value enum from error #2, with no `QUERY` constant.
Manual annotation and automatic discovery both ultimately route through `RequestMethod`; there is no
annotation-based path in this dependency tree that can express `QUERY` as a method.

**Fix**: none possible at this layer — QUERY is excluded from the `@RouterOperations` block. This is
a genuine, confirmed dead end for this specific mechanism (see #8–#13 for what was tried instead).

---

## 8. An external AI proposal's suggested fix doesn't work: bare `addExtension("query", ...)`

**Symptom**: following a proposal suggesting `pathItem.addExtension("query", operation)` (no `x-`
prefix) to inject the QUERY operation — the key never appears anywhere in `/api-docs`, not even as
raw JSON.

**Root cause**: swagger-core (`2.2.47`, this project's pinned version) only serializes extension
keys that start with `x-`, per the OpenAPI specification's own extension convention. A bare `"query"`
key is silently dropped during serialization. Confirmed by adding an `x-`-prefixed key alongside the
unprefixed one in the same test run and observing only the prefixed one survive.

**Fix**: use `x-query` instead of `query` when adding the extension via `OpenApiCustomizer`. This
makes the content appear in `/api-docs` (see error #9 for why that still wasn't the full fix).

---

## 9. The same proposal's second suggestion, `springdoc.config-path`, isn't a real property

**Symptom**: the proposal's second suggested fix — set `springdoc.config-path:
classpath:openapi.yaml` to merge a static file with the generated document — has no effect.

**Root cause**: `springdoc.config-path` doesn't exist as a configuration property in this project's
pinned springdoc-openapi version. Checked the actual `SpringDocConfigProperties` source directly
(not a properties reference page): no `configPath` field, nothing resembling a YAML-merge mechanism
anywhere in that class.

**Fix**: n/a — this suggestion was simply incorrect for this dependency version and was not pursued
further. (A real, different mechanism for showing an additional static spec — `springdoc.swagger-ui.urls`,
a dropdown of separate documents rather than a merge — does exist; see design-decisions-v2.md.)

---

## 10. Referencing `SlotQueryFilter`/`SlotResponse` schemas by `$ref` produces dangling references

**Symptom**: while building the QUERY operation's request/response schemas by hand, referencing
`#/components/schemas/SlotQueryFilter` and `#/components/schemas/SlotResponse` — those schemas don't
actually exist in `/api-docs`'s `components.schemas`, since nothing else in the generated document
references those DTOs by their real Java type (every other operation's schema is the generic
`ServerResponse` WebMvc.fn wrapper type — see design-decisions-v2.md's "known remaining gap" note).

**Fix**: register the schemas explicitly in the same `OpenApiCustomizer`, using swagger-core's
`ModelConverters.getInstance().resolveAsResolvedSchema(new AnnotatedType(SlotQueryFilter.class))` to
reflect them from the real DTO classes rather than hand-typing duplicate field lists (avoids the
schema silently drifting out of sync with the DTOs later).

**Verified by**: fetching `/api-docs` and checking `components.schemas.SlotQueryFilter`/
`SlotResponse` directly — confirmed correct field types and that `SlotStatus`'s enum values
(`FREE`/`BUSY`) came through correctly via reflection.

---

## 11. `x-query` is present in the JSON, but (presumed to) never render as a Swagger UI operation

**Symptom**: after fixes #8 and #10, `/api-docs` has a well-formed `x-query` extension with correct
schemas — but nothing in Swagger UI's known rendering behavior suggested it would ever show up as a
clickable operation card, since extensions aren't one of the fixed method keys Swagger UI's renderer
looks for.

**What actually happened**: this was initially treated as an accepted, documented limitation — until
directly checking exactly which Swagger UI build this project ships, instead of reasoning from
Swagger UI's rendering model in general. `mvn dependency:tree` shows `org.webjars:swagger-ui:5.32.2`.
Extracting and grepping `swagger-ui-bundle.js` from that exact jar turns up a literal method array
`["get","put","post","delete","options","head","patch","trace","query"]` used by the frontend's own
operation-rendering logic, plus `isOAS32()`/`createOnlyOAS32ComponentWrapper` functions gating
3.2-specific behavior. **The bundled frontend already supports `query` as a method** — the ceiling
was never "Swagger UI can't render this," it was "the key must be literally `query`, not `x-query`,
and the document must declare `openapi: 3.2.0`." See error #12 for why neither of those can be set
through springdoc/swagger-core's normal Java-object model, and #13 for the actual fix.

---

## 12. `PathItem` (the Java class springdoc/swagger-core build documents from) has no `query` field

**Symptom**: having confirmed Swagger UI's frontend wants a literal `query` key (error #11), setting
one via the typed `OpenApiCustomizer`/`Operation`/`PathItem` API isn't possible — there's no
`setQuery(...)` method, and no `additionalOperations` map either, to put it in.

**Root cause**: confirmed by inspecting `io.swagger.v3.oas.models.PathItem.class` directly in
`swagger-models-jakarta-2.2.47.jar` (this project's pinned swagger-core version, via `javap`-style
byte-level inspection since a decompiler/sources jar wasn't available for this exact class) — no
`query` field, no `additionalOperations` field. The Java object model this dependency version uses
to build `/api-docs` predates OpenAPI 3.2 entirely; there is no way to represent this through it.

**Fix**: stop trying to solve this at the Java-object layer. The final document is just JSON by the
time it reaches the browser — nothing requires it to have been produced entirely through fields on
`PathItem`. See error #13.

---

## 13. The actual fix: post-process the already-serialized JSON, below springdoc/swagger-core entirely

**Fix**: [`OpenApiQueryOperationFilter`](src/main/java/dev/isidro/queryverb/config/OpenApiQueryOperationFilter.java)
is a servlet `Filter` mapped to `/api-docs` that runs *after* springdoc has fully produced its normal
document (using `ContentCachingResponseWrapper` to capture the response body): it parses the JSON,
renames the `x-query` extension (built by `OpenApiQuerySupportConfig`) to a real `query` sibling of
`get`/`post`, and bumps the document's `openapi` field to `"3.2.0"` — the exact two conditions
Swagger UI's own `isOAS32()` check and method list (error #11) require. Neither of these needs
springdoc or swagger-core to understand OpenAPI 3.2 at all; they're just edits to a JSON tree.

**Verified by**: fetching the live `/api-docs` response and confirming both `"openapi":"3.2.0"` and
`paths./api/users/{userId}/slots.query` (not `.x-query`) are present, with the schemas from error #10
still correctly referenced. **Not verified with an actual browser screenshot** — browser automation
was unavailable in the environment this was built in — so "renders as a clickable operation card" is
inferred from reading this exact Swagger UI build's own JS, not visually confirmed. Check
`http://localhost:8080/swagger-ui.html` directly to see it firsthand.

---

## 14. Wiring the filter's `ObjectMapper` via Spring DI fails with `NoSuchBeanDefinitionException`

**Symptom**: `OpenApiQueryOperationFilter`'s `@Bean` factory method takes `ObjectMapper` as a
parameter for constructor injection — the app fails to start:
`required a bean of type 'com.fasterxml.jackson.databind.ObjectMapper' that could not be found`.

**Root cause**: Spring Boot 4.1 defaults its autoconfigured `ObjectMapper` bean to **Jackson 3**
(package `tools.jackson.databind`, not the classic `com.fasterxml.jackson.databind`) — confirmed via
`mvn dependency:tree`, which shows `tools.jackson.core:jackson-databind:3.1.4` as the primary Jackson
dependency pulled in by `spring-boot-starter-jackson`. springdoc-openapi, meanwhile, still pulls in
classic Jackson 2 (`com.fasterxml.jackson.core:jackson-databind:2.21.4`) transitively for its own
internal serialization — but nothing registers *that* as a Spring bean; springdoc constructs and uses
its own instance internally. Two different Jackson major versions coexist in the same application,
only one of which is a wireable bean, and it's not the one whose type this filter asked for.

**Fix**: don't rely on Spring's autoconfigured bean at all. The filter only does generic JSON tree
edits (parse, mutate two things, re-serialize) — no custom modules or app-specific configuration
needed — so a plain `new ObjectMapper()` constructed locally inside the filter is both correct and
avoids depending on which Jackson major version happens to be wired as a bean in this or any future
version of this application.

---

## Summary: what actually made QUERY show up in `/api-docs`

Three cooperating pieces, none of which alone was sufficient:

1. **`SpringDocResilienceConfig`** — a `RouterFunctionProvider` that isolates per-bean failures
   instead of one bad route aborting the whole document (errors #3–#5).
2. **`SlotRouterConfig`** split into two `@Bean`s, plus a `@RouterOperations` block on the
   non-QUERY one — makes the other 12 routes document at all (errors #5–#7).
3. **`OpenApiQuerySupportConfig`** (builds the QUERY operation + real schemas, as an `x-query`
   extension) **and `OpenApiQueryOperationFilter`** (promotes that extension to a real `query` key
   and bumps the declared OpenAPI version) — makes QUERY itself show up, exploiting the fact that
   this project's actual Swagger UI build (5.32.2) already supports OpenAPI 3.2's `query` method on
   the frontend, even though the backend Java tooling (springdoc 3.0.3 / swagger-core 2.2.47) does
   not yet (errors #8–#14).

Full test suite: 84/84 throughout every step above — each fix was verified against the running
application (both local H2 and `docker-compose` Postgres profiles), not just against `mvn test`.
