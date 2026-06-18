# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

A Grails plugin that replaces Grails' default file-based i18n (`.properties` files) with database-backed internationalization. On first message request after install, all `i18n/*.properties` files are loaded into the `Localization` table. Subsequent lookups hit the DB (with an in-memory LRU cache).

Current version: `7.0.0-M1` targeting Apache Grails 7.0.11 (branch `7.x-upgrade`). The Grails 6.2.x version `6.0-M1` is on `master`.

## Build & Test Commands

Requires Java 17 and Gradle 8+. If the system default JVM is not Java 17, set
`org.gradle.java.home` in `gradle.properties` to the Java 17 path.

```bash
# Build the plugin jar
./gradlew assemble

# Run tests (50 unit tests across 4 spec files)
./gradlew test

# Run a single test class
./gradlew test --tests "org.grails.plugins.localization.LocalizationSpec"

# Publish to GitHub Packages (requires GITHUB_USERNAME + GITHUB_TOKEN env vars)
./gradlew publishMavenJarPublicationToGitHubPackagesRepository

# Publish to Nexus (requires NEXUS_USERNAME, NEXUS_PASSWORD, NEXUS_URL env vars)
./gradlew publishMavenJarPublicationToNexusRepoRepository
```

## Architecture

### Plugin activation
The plugin is only active when `grails.plugin.localizations.enabled = true` is set (in `grails-app/conf/plugin.groovy` for the plugin itself, or in the consuming app's config). When enabled, `GrailsLocalizationsGrailsPlugin.doWithSpring()` replaces the default `messageSource` bean with `LocalizationMessageSource`.

### Key files

| File | Role |
|---|---|
| `src/main/groovy/.../GrailsLocalizationsGrailsPlugin.groovy` | Plugin descriptor — Spring wiring, dynamic method injection (`message`, `errorMessage`) onto domain/service classes |
| `grails-app/domain/.../Localization.groovy` | The domain class and the main logic hub: cache, `decodeMessage`, `load`/`reload`/`syncWithPropertyFiles`, `getMessage`, `setError` |
| `src/main/groovy/.../LocalizationsPluginUtils.groovy` | Resolves `i18n` resource files from the app and all installed plugins |
| `grails-app/controllers/.../LocalizationController.groovy` | CRUD UI + `cache` (stats/reset), `imports`, `jsonp` actions |
| `grails-app/services/.../LocalizationService.groovy` | Thin service; currently only checks whether optional peer plugins (`settings`, `criteria`) are installed |

### Cache
The cache lives as a static `LinkedHashMap` on the `Localization` domain class (LRU eviction). Default size is 128 KB. Override with `localizations.cache.size.kb` in app config. Reset via `GET /localization/cache` or calling `Localization.resetAll()`.

### Locale lookup order
`decodeMessage` queries for locale `'*'` (wildcard), the 2-char language code, and the 4-char language+country code, using `createCriteria()` with `order 'relevance', 'desc'` (relevance = locale string length), so country-specific entries beat language-only beats wildcard.

### Config options (consumer app)
```groovy
grails.plugin.localizations.enabled = true       // required to activate
localizations.cache.size.kb = 1024               // optional, 0 disables cache
grails.plugin.localizations.mapping = { ... }    // optional GORM column/index overrides
```

## Grails 7 Specifics

### Maven group
Apache Grails 7 moved from `org.grails` to `org.apache.grails`. All core dependencies and the
Gradle plugin use the new group. The Gradle plugin is declared in `buildSrc/build.gradle` (not in
`plugins {}` directly) because it is not published to the Gradle Plugin Portal.

### Hibernate version
This branch targets **Grails 7.0.11 which ships Hibernate 5** (5.6.15.Final), not Hibernate 6.
The HQL implicit-join restriction and `@Type` changes from Hibernate 6 do NOT apply here.

### GORM 9 — empty string coercion
GORM 9 converts `''` to `null` before validation. Any string constraint that uses `blank: true`
without `nullable: true` will reject empty strings in Grails 7. The `text` field on `Localization`
has `nullable: true` for this reason.

### decodeMessage uses criteria, not HQL
The `Localization.decodeMessage()` method was rewritten from HQL `findAll(...)` to
`createCriteria()`. This is required because `DomainUnitTest`'s in-memory GORM does not support
HQL string queries.

### Test suite
50 unit tests across 4 Spock spec files in `src/test/groovy/`. All tests pass on Grails 7.0.11.
See `MIGRATION.md` for the full list of source changes and their rationale.
