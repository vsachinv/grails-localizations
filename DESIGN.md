# Design Document — grails-localizations Plugin

**Version:** 7.0.0-M1  
**Target:** Apache Grails 7.0.11 / GORM 9 / Hibernate 5.6.x / Spring Boot 3.x  
**Branch:** `7.x-upgrade`

---

## Table of Contents

1. [Purpose](#1-purpose)
2. [High-Level Architecture](#2-high-level-architecture)
3. [Component Descriptions](#3-component-descriptions)
4. [Data Model](#4-data-model)
5. [Message Resolution Flow](#5-message-resolution-flow)
6. [Cache Design](#6-cache-design)
7. [Property File Loading](#7-property-file-loading)
8. [Plugin Activation & Spring Wiring](#8-plugin-activation--spring-wiring)
9. [Dynamic Method Injection](#9-dynamic-method-injection)
10. [Controller, UI & REST API](#10-controller-ui--rest-api)
11. [Optional Plugin Integration](#11-optional-plugin-integration)
12. [Configuration Reference](#12-configuration-reference)
13. [Key Design Decisions](#13-key-design-decisions)
14. [Known Limitations](#14-known-limitations)

---

## 1. Purpose

The `grails-localizations` plugin replaces Grails' default file-based i18n (`.properties` files)
with a database-backed internationalization system.

**Problem with the default approach:** `.properties` files are packaged into the application JAR.
Changing a message requires a redeployment. There is no runtime UI for non-technical users to
update translations.

**What this plugin provides:**
- All messages stored in a database table (`Localization`) — editable at runtime without redeployment
- An in-memory LRU cache for read performance comparable to file-based lookups
- Automatic one-time import of all `i18n/*.properties` files on first use
- A full CRUD management UI at `/localization`
- A JSONP endpoint for client-side template use
- `message()` and `errorMessage()` dynamic methods injected onto domain and service classes

---

## 2. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Consuming Application                    │
│                                                              │
│   GSP / Controller / Domain / Service                        │
│        │ message(code: 'my.key')                             │
│        ▼                                                     │
│   Spring MessageSource (bean: "messageSource")               │
│        │                                                     │
│        ▼  (replaced by plugin when enabled)                  │
│   LocalizationMessageSource  ──────────────────────────────► │
│        │                                                     │
│        ▼                                                     │
│   Localization.decodeMessage(code, locale)                   │
│        │                                                     │
│        ├──► In-Memory LRU Cache (LinkedHashMap)              │
│        │         hit ◄──────────────────────────────────────┤│
│        │         miss                                        ││
│        ▼                                                     ││
│   Localization.forCodeAndLocale (namedQuery)                 ││
│        │                                                     ││
│        ▼                                                     ││
│   Database (Localization table)                              ││
│        │                                                     ││
│        └──────────────────────────────────────────────────► ││
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. Component Descriptions

| Component | Type | Role |
|-----------|------|------|
| `LocalizationMessageSource` | Java class | Implements Spring's `AbstractMessageSource`. Registered as the `messageSource` bean. Delegates all lookups to `Localization.decodeMessage()`. |
| `Localization` | GORM domain class | Central hub of the plugin. Holds the data model, LRU cache, named query, and all static logic: `decodeMessage`, `getMessage`, `load`, `reload`, `syncWithPropertyFiles`, `search`, `statistics`. |
| `GrailsLocalizationsGrailsPlugin` | Plugin descriptor | Conditionally replaces the `messageSource` bean in `doWithSpring`. Injects `message()` and `errorMessage()` dynamic methods in `doWithDynamicMethods`. |
| `LocalizationsPluginUtils` | Groovy utility class | Resolves `i18n/*.properties` resource files from the application (dev and production paths) and from all installed binary plugins. |
| `LocalizationService` | Grails service | Checks whether optional peer plugins (`settings`, `criteria`, `helpBalloons`, `menus`) are installed via `Holders.getPluginManager()`. |
| `LocalizationController` | Grails controller | Provides the CRUD management UI, cache management, manual import, and JSONP endpoint. |
| `LocalizationTagLib` | Grails tag library | Renders optional UI elements (help balloons, criteria, paginate, menu button) when peer plugins are installed. |

---

## 4. Data Model

### Table: `localization`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | Long | PK, auto-generated | Surrogate key |
| `code` | VARCHAR(250) | NOT NULL, indexed | Message key (e.g., `home.title`) |
| `loc` | VARCHAR(4) | NOT NULL, unique with `code` | Locale string: `*`, `en`, `enUS` |
| `relevance` | TINYINT | NOT NULL | Auto-set to `locale.length()` by validator. Drives locale specificity ordering. |
| `text` | VARCHAR(2000) | nullable | The translated message text |
| `date_created` | DATETIME | auto | GORM auto-timestamp |
| `last_updated` | DATETIME | auto | GORM auto-timestamp |

> The `loc` column is named `loc` (not `locale`) due to the GORM mapping override. The default
> index name on `code` is `localizations_idx`. Both can be overridden via config.

### Locale values

| Value | Meaning | Example |
|-------|---------|---------|
| `*` | Wildcard — matches all locales | Fallback for any language |
| `en` | Language-only (2 chars) | Matches `en_US`, `en_GB`, etc. |
| `enUS` | Language + country (4 chars) | Exact match for `en_US` only |

The `relevance` field stores `locale.length()` (1 for `*`, 2 for `en`, 4 for `enUS`). Ordering
by `relevance DESC` in the named query ensures the most specific match wins.

### Domain constraints

```groovy
static constraints = {
    code(blank: false, size: 1..250)
    locale(size: 1..4, unique: 'code', blank: false, matches: "\\*|([a-z][a-z]([A-Z][A-Z])?)")
    relevance(validator: { val, obj ->
        if (obj.locale) obj.relevance = obj.locale.length()
        return true
    })
    text(blank: true, nullable: true, size: 0..2000)
}
```

> `text` has `nullable: true` because GORM 9 coerces `''` to `null` before validation. Without
> `nullable: true`, saving a blank translation would fail validation.

---

## 5. Message Resolution Flow

### Step-by-step: `messageSource.getMessage("home.title", null, null, Locale.forLanguageTag("fr-FR"))`

```
1. Spring calls LocalizationMessageSource.resolveCodeWithoutArguments("home.title", fr_FR)

2. LocalizationMessageSource calls Localization.decodeMessage("home.title", fr_FR)

3. Build cache key:  "home.title\bfrFR"   (\b = keyDelimiter = missingValue sentinel)

4. Check in-memory cache (synchronized):
   ├── HIT  → increment cacheHits, return cached value
   └── MISS → increment cacheMisses, continue to DB

5. Open a new Hibernate session (withNewSession):
   Execute namedQuery forCodeAndLocale:
     SELECT * FROM localization
     WHERE code = 'home.title'
       AND loc IN ('*', 'fr', 'frFR')
     ORDER BY relevance DESC
     LIMIT 1

   Result priority:
     'frFR' (relevance=4) wins over 'fr' (relevance=2) wins over '*' (relevance=1)
     No match → store missingValue sentinel (\b) in cache

6. Store result in cache (synchronized):
   - Update currentCacheSize
   - Evict oldest entries (LRU) if currentCacheSize > maxCacheSize

7. Return:
   - msg == missingValue sentinel → return null  (Spring falls back to parent MessageSource)
   - otherwise → return msg
```

### Null return behavior

When `decodeMessage` returns `null`, Spring's `AbstractMessageSource` propagates the lookup to
the parent `MessageSource` (if one is configured) or throws `NoSuchMessageException`. In standard
Grails usage, the parent is the default `ResourceBundleMessageSource` (file-based), so the plugin
gracefully falls back to `.properties` files for any key not yet in the database.

---

## 6. Cache Design

The cache is a static `LinkedHashMap` on the `Localization` class, constructed in LRU access-order
mode:

```groovy
private static final Map cache = new LinkedHashMap((int) 16, (float) 0.75, (boolean) true)
```

The third constructor argument (`true`) enables **access-order mode** — the least-recently-accessed
entry is at the head, making it straightforward to evict when the cache exceeds its size limit.

### Cache key

```
"<code>\b<language><country>"
```

The `\b` (backspace) character is used as both the key delimiter and the "missing value" sentinel —
it is an impossible value in a message key or locale string, ensuring no collision.

Examples:
- `"home.title\benUS"` — exact key for `en_US`
- `"home.title\benUS"` will also be the key when `en_US` is the requested locale, even if only a
  `*` or `en` row exists in the DB

### Size management

Cache size is tracked in bytes (sum of key length + value length for each entry). When
`currentCacheSize > maxCacheSize`, the iterator walks the LRU-ordered map from the oldest entry,
removing entries until the size is back under the limit.

### Cache invalidation

| Operation | Invalidation |
|-----------|-------------|
| Create new Localization | `resetThis(code)` — removes all cache entries whose key starts with `code\b` |
| Update Localization | `resetThis(oldCode)` + `resetThis(newCode)` if code changed |
| Delete Localization | `resetThis(code)` |
| `reload()` — full DB reload from properties files | `resetAll()` |
| `syncWithPropertyFiles()` — incremental import | `resetAll()` |
| `loadPropertyFile()` — when any rows were imported | `resetAll()` |
| Manual reset via UI or API | `resetAll()` |

### Cache statistics

`Localization.statistics()` returns:

```groovy
[
    max   : maxCacheSize,     // configured max size in bytes
    size  : currentCacheSize, // current size in bytes
    count : cache.size(),     // number of entries
    hits  : cacheHits,        // total cache hits since last reset
    misses: cacheMisses       // total cache misses since last reset
]
```

### Configuration

```groovy
// Default: 128 KB (128 * 1024 bytes)
// Set to 0 to disable caching entirely
localizations.cache.size.kb = 1024
```

---

## 7. Property File Loading

### Trigger

Loading happens automatically on the **first `message()` call** after installation. The
`LocalizationController.index()` and `imports()` actions both call `message(code: "home", default: "Home")`
at the top — this triggers the Spring messageSource, which calls `decodeMessage`, which finds
nothing in the DB and nothing in the cache, then `LocalizationMessageSource` returns `null`.
Spring's `AbstractMessageSource` calls back to the parent `ResourceBundleMessageSource`, which
triggers `Localization.load()` via the plugin activation hooks.

> In practice, the load is triggered by the first real `message()` call in the application, not
> just from the controller.

### Resource discovery — `LocalizationsPluginUtils`

Two strategies are used depending on the runtime environment:

**Development environment** (`Environment.isDevelopmentEnvironmentAvailable() == true`):  
Scans `<BuildSettings.BASE_DIR>/grails-app/i18n/` directly on the filesystem. Returns
`FileSystemResource` objects. This gives hot-reload behaviour during development.

**Production environment:**  
Uses Spring's `PathMatchingResourcePatternResolver` initialised with
`Thread.currentThread().getContextClassLoader()`. Pattern: `classpath*:*.properties`. Scans
the full application classpath for all `.properties` files.

**Plugin i18n resources:**  
`getAllPluginI18nResources()` iterates over all installed `BinaryGrailsPlugin` instances via
`Holders.pluginManager.allPlugins`. For each, uses `plugin.baseResourcesResource` as the root
and resolves `*.properties` files relative to it.

**Sorting:**  
Resources are sorted by the number of `_` tokens in the filename (ascending), then reversed.
This ensures country-specific files (`messages_en_US.properties`) are processed after
language-only files (`messages_en.properties`), which are processed after base files
(`messages.properties`). Since `loadPropertyFile` skips duplicates, loading order determines
which translation wins for a given code+locale combination.

### Import logic — `loadPropertyFile`

For each property file:
1. Determine the locale from the filename (`getLocaleForFileName`)
2. For each key in the file, check `Localization.countByCodeAndLocale(key, loc)` — skip if exists
3. Save new records; skip invalid records (logged but not thrown)
4. If any records were imported in this file, flush the session and `resetAll()` the cache

### `getLocaleForFileName`

| Filename pattern | Locale result |
|-----------------|---------------|
| `messages.properties` | `null` → stored as locale `*` |
| `messages_en.properties` | `new Locale("en")` → stored as `en` |
| `messages_en_US.properties` | `new Locale("en", "US")` → stored as `enUS` |

---

## 8. Plugin Activation & Spring Wiring

The plugin is **inactive by default**. It activates only when:

```groovy
grails.plugin.localizations.enabled = true
```

is set in the consuming application's config.

### `doWithSpring` — replaces the `messageSource` bean

```groovy
Closure doWithSpring() {
    { ->
        if (Holders.config.getProperty('grails.plugin.localizations.enabled', Boolean, false)) {
            messageSource(LocalizationMessageSource)
        }
    }
}
```

When enabled, `LocalizationMessageSource` is registered as the `messageSource` bean, replacing
Grails' default `ResourceBundleMessageSource`. Spring's `AbstractMessageSource` parent class
retains the default `ResourceBundleMessageSource` as the parent fallback — any key not found
in the database is resolved from `.properties` files.

### Load order

The plugin declares `loadAfter = ['i18n']` in the descriptor, ensuring the default i18n
`messageSource` bean is created first so that `LocalizationMessageSource` can safely replace it.

---

## 9. Dynamic Method Injection

`doWithDynamicMethods` injects two methods onto all Grails artefact classes:

| Method | Injected onto | Delegates to |
|--------|---------------|--------------|
| `message(Map)` | All domain classes + all service classes | `Localization.getMessage(parameters)` |
| `errorMessage(Map)` | All domain classes only | `Localization.setError(delegate, parameters)` |

### `getMessage(Map parameters)`

Resolves a message via the Spring `messageSource` bean. Parameters:

| Key | Required | Description |
|-----|----------|-------------|
| `code` | Yes | Message key |
| `args` | No | Array of substitution arguments for `MessageFormat` |
| `default` | No | Fallback string if key not found |
| `encodeAs` | No | Output encoding: `html`, `xml`, `url`, `javascript`, `base64` |

Locale is resolved from `LocaleContextHolder.getLocale()` — works in both web (HTTP request) and
non-web (background job, bootstrap) contexts.

### `setError(domain, Map parameters)`

Resolves a message via `getMessage()` then calls:
- `domain.errors.rejectValue(parameters.field, null, msg)` — if `field` is specified
- `domain.errors.reject(null, msg)` — if no `field`

Used to set a localised validation error message directly on a domain object's error state.

---

## 10. Controller, UI & REST API

`LocalizationController` serves both the HTML management UI and the REST API from a single
controller using Grails content negotiation (`withFormat`). The response format is determined by
the `Accept` header (or `format` request parameter / file extension) on each request.

`allowedMethods` accepts both HTML form verbs and REST verbs for mutating actions:

```groovy
static allowedMethods = [
    save  : ['POST'],
    update: ['POST', 'PUT', 'PATCH'],
    delete: ['POST', 'DELETE'],
    reset : ['POST'],
    load  : ['POST']
]
```

---

### HTML UI — `/localization`

| Action | Method | URL | Description |
|--------|--------|-----|-------------|
| `index` | GET | `/localization` | Lists all localizations with pagination and locale filter |
| `search` | GET | `/localization/search?q=...` | Full-text search across `code` and `text` fields, optional `locale` filter |
| `show` | GET | `/localization/show/{id}` | Shows a single localization |
| `create` | GET | `/localization/create` | Empty create form |
| `save` | POST | `/localization/save` | Persists a new localization; resets `code` cache entry |
| `edit` | GET | `/localization/edit/{id}` | Edit form for an existing localization |
| `update` | POST | `/localization/update` | Saves changes; resets cache for old and new code |
| `delete` | POST | `/localization/delete/{id}` | Deletes a localization; resets `code` cache entry |
| `cache` | GET | `/localization/cache` | Displays cache statistics |
| `reset` | POST | `/localization/reset` | Calls `Localization.resetAll()`, redirects to cache |
| `imports` | GET | `/localization/imports` | Lists available `.properties` files for manual import |
| `load` | POST | `/localization/load?file=...` | Loads a specific `.properties` file into the DB |
| `jsonp` | GET | `/localization/jsonp?padding=...&codeBeginsWith=...` | Returns localizations as JSONP |

#### JSONP endpoint

Returns all localizations for the current request locale (wildcard + language + country) as a
JSONP response. Useful for client-side template rendering. Duplicate codes are resolved by locale
specificity — a `frFR` row overwrites an `fr` row which overwrites a `*` row.

```
GET /localization/jsonp?padding=messages&codeBeginsWith=app.
→ messages={"app.title":"Mon Application","app.home":"Accueil"};
```

> No caching is applied to JSONP responses — each request hits the database.

---

### REST API — `/api/localizations`

The REST API is activated by adding a `resources` URL mapping to the consuming application's
`UrlMappings.groovy` (see §12 and README). All endpoints accept and return `application/json`.

#### URL Mapping (consuming app)

```groovy
"/api/localizations"(resources: 'localization') {
    "/search"(controller: 'localization', action: 'search')
    "/cache"(controller: 'localization', action: 'cache', method: 'GET')
    "/cache/reset"(controller: 'localization', action: 'reset', method: 'POST')
}
```

#### Route table

| Method | URL | Action | Description |
|--------|-----|--------|-------------|
| GET | `/api/localizations` | `index` | Paginated list |
| POST | `/api/localizations` | `save` | Create |
| GET | `/api/localizations/{id}` | `show` | Single record |
| PUT / PATCH | `/api/localizations/{id}` | `update` | Update |
| DELETE | `/api/localizations/{id}` | `delete` | Delete |
| GET | `/api/localizations/search` | `search` | Search with `q` and `locale` filters |
| GET | `/api/localizations/cache` | `cache` | Cache statistics |
| POST | `/api/localizations/cache/reset` | `reset` | Flush cache |

#### Paginated list response (`GET /api/localizations`)

Query parameters: `max` (default 20, cap 50), `offset` (default 0), `sort`, `order`.

```json
{
  "data": [
    { "id": 1, "code": "home.label", "locale": "*", "text": "Home" }
  ],
  "total": 248,
  "max": 20,
  "offset": 0,
  "page": 1,
  "totalPages": 13
}
```

`page` and `totalPages` are derived server-side so callers do not need to compute them.

#### Create (`POST /api/localizations`)

```json
{ "code": "home.label", "locale": "fr", "text": "Accueil" }
```

Returns `201 Created` with the persisted object, or `422 Unprocessable Entity` with a GORM
`errors` object on validation failure.

#### Update (`PUT /api/localizations/{id}`)

```json
{ "code": "home.label", "locale": "fr", "text": "Accueil modifié" }
```

Returns `200 OK` with the updated object, `422` on validation failure, or `404` if not found.

#### Delete (`DELETE /api/localizations/{id}`)

Returns `204 No Content`. Returns `404` if the record does not exist.

#### Search (`GET /api/localizations/search`)

Query parameters: `q` (substring match on `code` or `text`), `locale`, `sort`, `order`,
`max`, `offset`. Response uses the same paginated envelope as the list endpoint.

#### Cache statistics (`GET /api/localizations/cache`)

```json
{
  "stats": {
    "count": 42,
    "hits": 1830,
    "misses": 42,
    "max": 131072,
    "size": 11264
  }
}
```

#### HTTP status codes

| Status | Meaning |
|--------|---------|
| `200` | Success (GET, PUT/PATCH) |
| `201` | Created (POST save) |
| `204` | Deleted (DELETE) |
| `404` | Record not found |
| `405` | Method not allowed |
| `422` | Validation failed — response body contains `errors` |

---

### `withLocalization` private helper

```groovy
private def withLocalization(id = "id", Closure c) {
    Localization localization = Localization.get(params[id])
    if (localization) {
        c.call localization
    } else {
        withFormat {
            html {
                flash.message = "localization.not.found"
                flash.args = [params.id]
                redirect(action: 'index')
            }
            '*' { render status: 404 }
        }
    }
}
```

Shared lookup and not-found handling used by `show`, `edit`, and `delete`. For HTML requests,
redirects to `index` with a flash message. For REST (JSON or any other format), returns `404`.

---

## 11. Optional Plugin Integration

`LocalizationService.hasPlugin(String name)` wraps `Holders.getPluginManager().hasGrailsPlugin(name)`
to check whether optional peer plugins are installed at runtime. No hard compile-time dependency
is taken on any of these plugins.

| Plugin name | Used in | Effect |
|-------------|---------|--------|
| `settings` | `LocalizationController.index()` | Reads `pagination.max` and `pagination.default` from the Settings plugin instead of using hardcoded defaults |
| `criteria` / `drilldowns` | `LocalizationController.index()`, `LocalizationTagLib` | Uses `Localization.selectList()` / `selectCount()` for filtering when these plugins are present |
| `helpBalloons` | `LocalizationTagLib` | Renders help balloon tags if the helpBalloons plugin is installed |
| `menus` | `LocalizationTagLib` | Renders a menu button tag if the menus plugin is installed |

---

## 12. Configuration Reference

All config keys go in the consuming application's `application.groovy` (or `application.yml`):

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `grails.plugin.localizations.enabled` | Boolean | `false` | **Required.** Activates the plugin. Without this, `messageSource` is not replaced. |
| `localizations.cache.size.kb` | Integer | `128` | LRU cache size in KB. Set to `0` to disable caching. Max accepted: `1024 * 1024` KB. |
| `grails.plugin.localizations.mapping` | Closure | see below | GORM mapping override for column names and indexes. |

### Default mapping (applied when no override is configured):

```groovy
columns {
    code   index: "localizations_idx"
    locale column: "loc"
}
```

### Overriding the mapping:

```groovy
grails.plugin.localizations.mapping = {
    columns {
        code   index: "my_custom_index"
        locale column: "my_locale_col"
    }
}
```

---

## 13. Key Design Decisions

### Why database-backed instead of file-based?

Messages stored in a database can be updated at runtime via the management UI without a
redeployment. This is the primary driver for the plugin's existence.

### Why an LRU in-memory cache?

A database round-trip on every `message()` call would be unacceptably slow — most pages call
`message()` dozens of times per render. The LRU cache keeps hot keys in memory, giving
performance close to the default file-based approach. The cache is invalidated on any write
to ensure consistency.

### Why `namedQueries` for `decodeMessage`?

`decodeMessage` is on the hot path — called for every cache miss. A named query compiles the
criteria definition once at class load time and caches the execution plan, avoiding the overhead
of rebuilding the criteria object on every invocation.

### Why `LocaleContextHolder` instead of `RequestContextHolder`?

`GrailsWebMockUtil` and `RequestContextHolder` (the Grails 6 approach) required synthesizing a
fake HTTP request to obtain the locale in non-web contexts (background jobs, bootstrap). Both
APIs were removed in Grails 7. `LocaleContextHolder` is the Spring-idiomatic replacement — it
is a thread-local populated automatically by Spring MVC for web requests and by Spring's async
infrastructure for background threads.

### Why `PathMatchingResourcePatternResolver` with the context classloader?

`ClassRelativeResourcePatternResolver` (the Grails 6 approach) was an internal Grails API that
anchored classpath scanning to the application's main class. It was removed in Grails 7. The
context classloader gives access to the same classpath without any Grails-internal API dependency.

### Why `nullable: true` on `text`?

GORM 9 coerces `''` to `null` before validation. A field with `blank: true` but no `nullable: true`
would reject empty string translations. Adding `nullable: true` preserves the intended behaviour:
a translation can be empty (or null) — this is valid for keys that exist in the code but have not
yet been translated.

### Why is `@CompileStatic` removed from `LocalizationService`?

`@CompileStatic` compiles `Holders.getPluginManager()` to a direct `invokestatic` bytecode
instruction, bypassing Groovy's metaClass dispatch. `GroovyMock(global: true)` — the standard
Spock mechanism for mocking `Holders` in unit tests — intercepts via metaClass and therefore has
no effect on `@CompileStatic` code. Removing `@CompileStatic` from this one-liner service restores
testability with no runtime behaviour change.

---

## 14. Known Limitations

| Limitation | Detail |
|------------|--------|
| No JSONP caching | The `jsonp` action queries the database on every request. High-traffic use should add a separate HTTP-level cache (e.g., CDN, reverse proxy). |
| Cache is per-JVM-instance | The in-memory LRU cache is a static field on the `Localization` class. In a clustered deployment (multiple JVM nodes), a write on one node invalidates that node's cache but not other nodes'. |
| `text` max length is 2000 characters | Long messages (e.g., email templates, rich HTML) cannot be stored without a schema change. |
| `reload()` is not transactional | `Localization.executeUpdate("delete from Localization")` runs outside a transaction in the current implementation. A failure mid-reload leaves the table empty. |
| Import skips invalid records silently | `loadPropertyFile` increments `skipped` for validation failures but does not log the key or error. Diagnosing why a key was not imported requires debugging. |
| No audit trail | There is no history of who changed a translation or when. `lastUpdated` provides a timestamp but no actor. |
