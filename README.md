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

# Run tests (50 unit tests)
./gradlew test

# Run a single spec
./gradlew test --tests "org.grails.plugins.localization.LocalizationSpec"

# Publish to GitHub Packages (requires GITHUB_USERNAME + GITHUB_TOKEN env vars)
./gradlew publishMavenJarPublicationToGitHubPackagesRepository

# Publish to Nexus (requires NEXUS_USERNAME, NEXUS_PASSWORD, NEXUS_URL env vars)
./gradlew publishMavenJarPublicationToNexusRepoRepository
```

See [MIGRATION.md](MIGRATION.md) for the full list of changes made in the Grails 6 → 7 upgrade.
