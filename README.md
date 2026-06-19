grails-localization
===================

The localizations plugin replaces Grails' default file-based i18n (`.properties` files) with
database-backed internationalization. On first message request after install, all `i18n/*.properties`
files are automatically loaded into the `Localization` table. Subsequent lookups hit the database
(with an in-memory LRU cache for speed).

A CRUD UI, import facility, cache management endpoint, and JSONP endpoint are included out of the box.

---

## Version Compatibility

| Plugin version | Grails version | Java | Branch |
|----------------|---------------|------|--------|
| `7.0.0-M1` | Apache Grails 7.0.x | 17+ | `7.x-upgrade` |
| `6.0-M1` | Grails 6.2.x | 11+ | `master` |
| `5.0-M1` | Grails 5.x | 11+ | — |
| `4.0-M1` | Grails 4.x | 8+ | — |
| `0.1.3` | Grails 3.2.x | 8+ | `grails3-upgrade` |
| `2.4` | Grails 2.x | 8+ | `Plugin_2.X` |

---

## Installation — Grails 7.x (Apache Grails)

### Via GitHub Packages

Add the repository and dependency to your app's `build.gradle`:

```groovy
repositories {
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/vsachinv/grails-localizations")
        credentials {
            username = project.findProperty("gpr.user") ?: System.getenv("GITHUB_USERNAME")
            password = project.findProperty("gpr.key") ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation "org.grails.plugins:grails-localizations:7.0.0-M1"
}
```

### Via Nexus (internal)

```groovy
dependencies {
    implementation "org.grails.plugins:grails-localizations:7.0.0-M1"
}
```

### Enable the plugin

The plugin is inactive by default. Add to your app's `grails-app/conf/application.groovy` or
`application.yml`:

```groovy
// application.groovy
grails.plugin.localizations.enabled = true
```

```yaml
# application.yml
grails:
  plugin:
    localizations:
      enabled: true
```

---

## Installation — Grails 6.x

```groovy
repositories {
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/vsachinv/grails-localizations")
        credentials {
            username = project.findProperty("gpr.user") ?: System.getenv("GITHUB_USERNAME")
            password = project.findProperty("gpr.key") ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation "org.grails.plugins:grails-localizations:6.0-M1"
}
```

---

## Installation — Grails 4.x / 5.x (via JitPack)

```groovy
repositories {
    maven { url "https://jitpack.io" }
}

dependencies {
    // Grails 5.x
    implementation 'com.github.vsachinv:grails-localizations:5.0-M1'

    // Grails 4.x
    implementation 'com.github.vsachinv:grails-localizations:4.0-M1'
}
```

---

## Installation — Grails 3.2.x (via JitPack)

```groovy
repositories {
    maven { url "https://jitpack.io" }
}

dependencies {
    compile 'com.github.vsachinv:grails-localizations:0.1.3'
}
```

Source: https://github.com/vsachinv/grails-localizations/tree/grails3-upgrade

---

## Installation — Grails 2.x

```groovy
// BuildConfig.groovy
plugins {
    compile ":localizations:2.4"
}
```

Source: https://github.com/vsachinv/grails-localizations/tree/Plugin_2.X

---

## Configuration

All config keys go in your consuming app's `application.groovy` (or `application.yml`):

```groovy
// Required — activates the plugin
grails.plugin.localizations.enabled = true

// Optional — override LRU cache size in KB (default 128 KB, 0 = disable cache)
localizations.cache.size.kb = 1024

// Optional — override GORM column/index mapping
grails.plugin.localizations.mapping = {
    columns {
        code   index: "my_custom_index"
        locale column: "my_locale_col"
    }
}
```

---

## Features

### Automatic property file loading

On the first `message()` call after installation, all `*.properties` files under `grails-app/i18n/`
(and from installed plugins) are imported into the `Localization` table. Subsequent message lookups
go directly to the database.

### CRUD management UI

A full create/read/update/delete interface is available at:

```
http://myServer/myApp/localization/index
```

### Import facility

Load additional property files (e.g. from newly installed plugins) without restarting:

```
http://myServer/myApp/localization/imports
```

### Cache management

View cache statistics or reset the cache:

```
http://myServer/myApp/localization/cache
```

Or programmatically: `Localization.resetAll()` / `Localization.resetThis(code)`

### Dynamic methods

The plugin injects a `message(Map)` method onto all domain and service classes, and an
`errorMessage(Map)` method onto domain classes:

```groovy
// In any domain or service class
String label = message(code: 'my.key', default: 'Fallback text')

// In a domain class — set a field error with a localised message
errorMessage(field: 'name', code: 'my.domain.name.invalid', default: 'Name is invalid')
```

### JSONP endpoint

Retrieve localizations as JSONP for use in client-side templates:

```
http://myServer/myApp/localization/jsonp?padding=messages&codeBeginsWith=my.prefix
```

---

## REST API

The plugin exposes a full REST API for managing localizations. This is useful for external UI
integrations (e.g. an admin SPA) without using the built-in GSP views.

### URL Mapping — required in the consuming app

The plugin ships a default `UrlMappings.groovy` for use when the plugin is installed standalone,
but **when installed inside an existing Grails application you must add the REST routes to your
app's own `UrlMappings.groovy`**:

```groovy
// grails-app/controllers/UrlMappings.groovy (in your application)
class UrlMappings {
    static mappings = {

        // ... your existing mappings ...

        // Localization REST API
        "/api/localizations"(resources: 'localization') {
            "/search"(controller: 'localization', action: 'search')
            "/cache"(controller: 'localization', action: 'cache', method: 'GET')
            "/cache/reset"(controller: 'localization', action: 'reset', method: 'POST')
        }
    }
}
```

> The `resources: 'localization'` declaration generates all seven RESTful routes automatically.
> The nested entries add the plugin-specific `search`, `cache`, and `cache/reset` sub-routes.

---

### Endpoints

All endpoints accept and return `application/json`. Send `Accept: application/json` (or
`Content-Type: application/json` for write requests) to activate JSON mode. Omitting the header
returns the standard HTML view instead.

#### List localizations

```
GET /api/localizations
```

Query parameters:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `max`     | 20      | Page size (capped at 50) |
| `offset`  | 0       | Zero-based record offset |
| `sort`    | `code`  | Field to sort by (`code`, `locale`, `text`) |
| `order`   | `asc`   | Sort direction (`asc` or `desc`) |

Response:

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

#### Get a single localization

```
GET /api/localizations/{id}
```

Response: the `Localization` object as JSON, or `404` if not found.

#### Create a localization

```
POST /api/localizations
Content-Type: application/json

{ "code": "home.label", "locale": "fr", "text": "Accueil" }
```

Returns `201 Created` with the created object, or `422 Unprocessable Entity` with validation errors.

#### Update a localization

```
PUT /api/localizations/{id}
Content-Type: application/json

{ "code": "home.label", "locale": "fr", "text": "Accueil modifié" }
```

`PATCH` is also accepted. Returns `200 OK` with the updated object, `422` on validation error,
or `404` if not found.

#### Delete a localization

```
DELETE /api/localizations/{id}
```

Returns `204 No Content` on success, `404` if not found.

#### Search localizations

```
GET /api/localizations/search?q=home&locale=fr&sort=code&order=asc&max=20&offset=0
```

Query parameters:

| Parameter | Description |
|-----------|-------------|
| `q`       | Substring match against `code` or `text` (case-insensitive) |
| `locale`  | Filter by locale code (e.g. `fr`, `frFR`, `*`) |
| `sort`    | Field to sort by |
| `order`   | `asc` or `desc` |
| `max` / `offset` | Pagination |

Response: same paginated envelope as the list endpoint.

#### Cache statistics

```
GET /api/localizations/cache
```

Response:

```json
{
  "stats": {
    "count": 42,
    "hits": 1830,
    "misses": 42,
    "maxSizeKb": 128,
    "currentSizeKb": 11
  }
}
```

#### Reset cache

```
POST /api/localizations/cache/reset
```

Returns `302` redirect to the cache stats page (HTML) or `200` (JSON).

---

### HTTP status codes summary

| Status | Meaning |
|--------|---------|
| `200`  | Success (GET, PUT/PATCH) |
| `201`  | Created (POST) |
| `204`  | Deleted (DELETE) |
| `404`  | Localization not found |
| `405`  | Method not allowed |
| `422`  | Validation failed — response body contains `errors` object |

---

## Locale lookup order

When resolving a message for locale `fr_FR`, the plugin queries for:
1. Exact country match: `frFR`
2. Language match: `fr`
3. Wildcard: `*`

The most specific match wins (ordered by `relevance DESC`, where relevance = locale string length).

---

## Database requirement

Your database must be configured to allow Unicode data (`utf8mb4` for MySQL, `UTF8` for PostgreSQL,
etc.) for multi-language content to store and retrieve correctly.

---

## Building from source

Requires Java 17+ and Gradle 8+.

```bash
# Build the plugin jar
./gradlew assemble

# Run tests (51 unit tests)
./gradlew test

# Run a single spec
./gradlew test --tests "org.grails.plugins.localization.LocalizationSpec"

# Publish to GitHub Packages (requires GITHUB_USERNAME + GITHUB_TOKEN env vars)
./gradlew publishMavenJarPublicationToGitHubPackagesRepository

# Publish to Nexus (requires NEXUS_USERNAME, NEXUS_PASSWORD, NEXUS_URL env vars)
./gradlew publishMavenJarPublicationToNexusRepoRepository
```

See [MIGRATION.md](MIGRATION.md) for the full list of changes made in the Grails 6 → 7 upgrade.
