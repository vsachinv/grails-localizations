# Migration Guide: Grails 6.2.0 → Apache Grails 7.0.11

This document records every change made to upgrade the `grails-localizations` plugin from
Grails 6.2.0 to Apache Grails 7.0.11. It is intended as a reference for:
- Reverting individual changes if a regression is discovered
- Applying the same migration pattern to other plugins
- Understanding *why* each change was necessary

Branch: `7.x-upgrade`  
Plugin version bump: `6.0-M1` → `7.0.0-M1`

---

## Table of Contents

1. [Build Infrastructure](#1-build-infrastructure)
2. [Plugin Descriptor](#2-plugin-descriptor)
3. [Domain Class — Localization.groovy](#3-domain-class--localizationgroovy)
4. [Resource Loader — LocalizationsPluginUtils.groovy](#4-resource-loader--localizationspluginutilsgroovy)
5. [Service — LocalizationService.groovy](#5-service--localizationservicegroovy)
6. [Assets — Removed](#6-assets--removed)
7. [New: Test Suite](#7-new-test-suite)
8. [Removed APIs Quick Reference](#8-removed-apis-quick-reference)

---

## 1. Build Infrastructure

### 1.1 `gradle/wrapper/gradle-wrapper.properties`

**Why:** Apache Grails 7 requires Gradle 8+. Gradle 7.6.4 fails with
`ConfigurableFileCollection.convention(Object[])` not found.

| | Before | After |
|--|--------|-------|
| `distributionUrl` | `gradle-7.6.4-bin.zip` | `gradle-8.14.4-bin.zip` |

---

### 1.2 `gradle.properties`

**Why:** Version bumps to target Apache Grails 7.0.11 and Spring Boot 3.x.

```diff
-grailsVersion=6.2.0
-grailsGradlePluginVersion=6.1.2
-version=6.0-M1
+grailsVersion=7.0.11
+version=7.0.0-M1
+springBootVersion=3.5.14
```

> `org.gradle.java.home` may be set locally to point to a SDKMAN-managed Java 17 install when the
> system default JVM is not Java 17. Omit it in CI environments where Java 17 is the default.

---

### 1.3 `buildSrc/` — removed; replaced by `buildscript {}` block in `build.gradle`

**Why:** The Grails 6 build used a `buildSrc` project to put the Grails Gradle plugin on the
buildscript classpath. `buildSrc` runs as a separate Gradle build and inherits the system JVM,
which causes resolution failures when the system default is not Java 17. This created a fragile
two-project setup requiring maintenance in two places.

The `buildSrc/` directory was deleted entirely. The same requirement is now met by a `buildscript {}`
block at the top of `build.gradle`, which is Gradle's native mechanism for adding plugin classpath
entries. It resolves under the JVM set by `org.gradle.java.home` in `gradle.properties`.

Plugins that are not on the Gradle Plugin Portal (Grails, git-properties) cannot use the `plugins {}`
DSL after being declared this way — they must be applied with `apply plugin:` instead.

```groovy
// ADDED — replaces buildSrc entirely
buildscript {
    repositories {
        mavenLocal()
        mavenCentral()
        maven { url = 'https://repo.grails.org/grails/restricted' }
    }
    dependencies {
        classpath platform("org.apache.grails:grails-bom:$grailsVersion")
        classpath "org.apache.grails:grails-gradle-plugins"           // version from BOM
        classpath "com.gorylenko.gradle-git-properties:gradle-git-properties:4.0.1"
    }
}
```

**Files deleted:** `buildSrc/build.gradle` and all of `buildSrc/`

---

### 1.4 `settings.gradle`

**Why:** Simplified — removed the `pluginManagement` block and empty `plugins {}` block that
existed to support the old `buildSrc` approach. The file is now a single line:

```groovy
rootProject.name='grails-localizations'
```

---

### 1.5 `build.gradle`

**Why:** Apache Grails 7 moved to the `org.apache.grails` Maven group. The Gradle plugin ID
changed. Grails 7.0.x targets Hibernate 5 (not Hibernate 6). All plugin plugins are now applied
imperatively via `apply plugin:` because they are declared in `buildscript {}`.

**Plugin declarations — all via `apply plugin:`:**

```groovy
apply plugin: "eclipse"
apply plugin: "idea"
apply plugin: "org.apache.grails.gradle.grails-plugin"   // was: org.grails.grails-plugin
apply plugin: "maven-publish"
apply plugin: "com.gorylenko.gradle-git-properties"
```

**Java source release:**

```diff
-java {
-    sourceCompatibility = JavaVersion.toVersion("11")
-}
+compileJava.options.release = 17
```

> The JVM used is controlled by `org.gradle.java.home` in `gradle.properties`. The `release = 17`
> flag locks the bytecode target.

**Repositories:**

```diff
-maven { url "https://repo1.maven.org/maven2/" }
+mavenCentral()
```

**Dependencies — full replacement:**

| Grails 6 coordinate | Apache Grails 7.0.11 coordinate | Reason |
|---------------------|----------------------------------|--------|
| `org.grails:grails-core` | `org.apache.grails:grails-core` (via BOM) | Group change |
| `org.grails:grails-plugin-rest` | `org.apache.grails:grails-rest-transforms` | Group + rename |
| `org.grails.web:grails-web-mvc` → same group | `org.apache.grails.web:grails-web-mvc` | Group change |
| `org.grails.plugins:hibernate5` | `org.apache.grails:grails-data-hibernate5` | Group + rename; Hibernate 5.6.x |
| `org.grails:grails-gorm-testing-support` | Split into three: `org.apache.grails.testing:grails-testing-support-core` + `org.apache.grails:grails-testing-support-web` + `org.apache.grails:grails-testing-support-datamapping` | Renamed and split |
| `org.grails:grails-plugin-i18n` | Removed | Bundled in core |
| `org.grails:grails-plugin-interceptors` | Removed | Bundled in core |
| `com.bertramlabs.plugins:asset-pipeline-gradle` | Removed entirely | Plugin has no assets (see §6) |

**BOM added:**

```groovy
// In buildscript dependencies (§1.3):
classpath platform("org.apache.grails:grails-bom:$grailsVersion")

// In project dependencies:
implementation platform("org.apache.grails:grails-bom:$grailsVersion")
```

The BOM manages version alignment for all `org.apache.grails:*` artifacts — individual version
strings are omitted for BOM-managed coordinates.

**Groovy indy optimization disabled (new — required for Grails 7.0.x):**

```groovy
// https://github.com/apache/grails-core/issues/15321
tasks.withType(GroovyCompile).configureEach {
    groovyOptions.optimizationOptions.indy = false
}
```

This works around a known Groovy 4 + Grails 7.0.x invokedynamic issue. Without it, certain
Groovy closure paths fail at runtime.

**`task sourceJar` / `task packageJavadoc` / `task packageGroovydoc`:** changed `classifier =` to
`archiveClassifier =` (Gradle 8 deprecation).

---

## 2. Plugin Descriptor

**File:** `src/main/groovy/org/grails/plugins/localization/GrailsLocalizationsGrailsPlugin.groovy`

**Why:** The `grailsVersion` range must be updated to declare compatibility with Grails 7.

```diff
-def grailsVersion = "6.2.0  > *"
+def grailsVersion = "7.0.0 > *"
```

---

## 3. Domain Class — `Localization.groovy`

**File:** `grails-app/domain/org/grails/plugins/localization/Localization.groovy`

### 3.1 Import cleanup — removed Grails 6 web context APIs

**Why:** `GrailsWebMockUtil`, `ServletContextHolder`, and related web context classes are removed
in Grails 7. They were used in `getMessage()` to synthesize a fake HTTP request when no real
request was in scope, solely to obtain a `Locale`. `LocaleContextHolder` provides the same
information without any web context dependency and works correctly in both web and non-web contexts.

```diff
-import grails.util.GrailsWebMockUtil
-import grails.web.context.ServletContextHolder
-import org.springframework.web.context.WebApplicationContext
-import org.springframework.web.context.request.RequestAttributes
-import org.springframework.web.context.request.RequestContextHolder
-import org.springframework.web.context.support.WebApplicationContextUtils
-import org.springframework.web.servlet.support.RequestContextUtils
+import org.springframework.context.i18n.LocaleContextHolder
```

### 3.2 `namedQueries` — added `forCodeAndLocale`

**Why:** `decodeMessage()` is on the hot path — called for every message lookup that misses the
in-memory cache. A named query compiles and caches the criteria definition once at class load time,
avoiding the overhead of rebuilding it on every call.

```groovy
static namedQueries = {
    forCodeAndLocale { String aCode, List<String> locales ->
        eq 'code', aCode
        inList 'locale', locales
        order 'relevance', 'desc'
    }
}
```

### 3.3 `decodeMessage()` — rewritten to use named query

**Why (was HQL):** The Grails 6 code used `findAll("from Localization ... ?0 ... ?1 ... ?2")`.
Two problems:

1. GORM 9 / Grails 7 standardises to `?1`-based (1-indexed) positional parameters. The old `?0`
   indexing would return wrong results or fail.
2. `DomainUnitTest`'s in-memory GORM does not support HQL string queries at all — it throws
   `UnsupportedOperationException: String-based queries like [findAll] not supported`.

**Why named query instead of plain `createCriteria()`:** A named query is defined once and its
execution plan is cached. `createCriteria()` rebuilds the criteria object on every invocation.
For a method called on every cache miss, the named query is the better choice.

```diff
-List<Localization> lst = Localization.findAll(
-    "from org.grails.plugins.localization.Localization as x where x.code = ?0 " +
-    "and x.locale in ('*', ?1, ?2) order by x.relevance desc",
-    [code, locale.getLanguage(), locale.getLanguage() + locale.getCountry()])
+List<Localization> lst = Localization.forCodeAndLocale(
+    code, ['*', locale.getLanguage(), locale.getLanguage() + locale.getCountry()]
+).list(max: 1)
```

Semantics are identical: fetch the most relevant locale match (country-specific beats
language-only beats wildcard), limited to 1 row.

### 3.4 `reload()` — HQL DELETE missing FROM keyword

**Why:** GORM 9 / Grails 7 requires the `FROM` keyword in HQL DELETE statements.

```diff
-Localization.executeUpdate("delete Localization")
+Localization.executeUpdate("delete from Localization")
```

### 3.5 `getMessage()` — replaced mock web request with `LocaleContextHolder`

**Why:** `GrailsWebMockUtil.bindMockWebRequest()` and `RequestContextHolder` were the Grails 6
mechanism for resolving the locale when no real HTTP request was in scope. Both are removed in
Grails 7. Spring's `LocaleContextHolder` is the idiomatic replacement — it is thread-local,
populated automatically by Spring MVC for web requests and by Spring's async infrastructure for
background threads, and defaults to the JVM locale when nothing has set it.

```groovy
// BEFORE (Grails 6)
static String getMessage(Map parameters) {
    def requestAttributes = RequestContextHolder.getRequestAttributes()
    if (!requestAttributes) {
        GrailsWebMockUtil.bindMockWebRequest()
        requestAttributes = RequestContextHolder.getRequestAttributes()
    }
    def ctx = WebApplicationContextUtils.getRequiredWebApplicationContext(
                  ServletContextHolder.getServletContext())
    def messageSource = ctx.getBean("messageSource")
    Locale locale = RequestContextUtils.getLocale(requestAttributes.getRequest())
    String msg = messageSource.getMessage(...)
}

// AFTER (Grails 7)
static String getMessage(Map parameters) {
    def messageSource = Holders.applicationContext.getBean("messageSource")
    // Works in both web and non-web contexts; replaces GrailsWebMockUtil/RequestContextHolder (removed in Grails 7)
    Locale locale = LocaleContextHolder.getLocale()
    String msg = messageSource.getMessage(...)
}
```

### 3.6 `text` constraint — added `nullable: true`

**Why:** GORM 9 (all Grails 7.x) converts empty string `''` to `null` before validation runs.
Previously `text(blank: true)` allowed empty strings. In Grails 7, passing `text: ''` is coerced
to `text: null` and then fails the implicit `nullable: false` constraint. Adding `nullable: true`
restores the ability to store both `null` and blank text values.

```diff
-text(blank: true, size: 0..2000)
+text(blank: true, nullable: true, size: 0..2000)
```

---

## 4. Resource Loader — `LocalizationsPluginUtils.groovy`

**File:** `src/main/groovy/org/grails/plugins/localization/LocalizationsPluginUtils.groovy`

**Why:** `ClassRelativeResourcePatternResolver` (`org.grails.core.support.internal.tools`) is an
internal Grails class removed in Grails 7. It was used in the production (non-dev) code path to
scan the classpath for `*.properties` files relative to the application's main class.
`DefaultGrailsApplication.getApplicationClass()` (used to obtain that main class) was also removed.

The replacement is Spring's standard `PathMatchingResourcePatternResolver` initialised with the
thread context classloader. This scans the full classpath identically and requires no Grails-internal
API.

**Imports removed:**
```diff
-import grails.core.DefaultGrailsApplication
-import grails.core.GrailsApplicationClass
-import org.grails.core.io.StaticResourceLoader
-import org.grails.core.support.internal.tools.ClassRelativeResourcePatternResolver
```

**Production code path in `getI18nResources()` — non-dev branch:**
```diff
// BEFORE
-DefaultGrailsApplication defaultGrailsApplication =
-    (DefaultGrailsApplication) Holders.grailsApplication
-GrailsApplicationClass applicationClass =
-    defaultGrailsApplication.getApplicationClass()
-if (applicationClass != null) {
-    ResourcePatternResolver resourcePatternResolver =
-        new ClassRelativeResourcePatternResolver(applicationClass.getClass())
-    resources = resourcePatternResolver.getResources(messageBundleLocationPattern)
-}

// AFTER
+ResourcePatternResolver resourcePatternResolver = new PathMatchingResourcePatternResolver(
+    Thread.currentThread().getContextClassLoader()
+)
+resources = resourcePatternResolver.getResources(messageBundleLocationPattern)
```

The dev branch (file-system scan via `BuildSettings.BASE_DIR`) is unchanged.

---

## 5. Service — `LocalizationService.groovy`

**File:** `grails-app/services/org/grails/plugins/localization/LocalizationService.groovy`

**Why:** The service had `@CompileStatic`. The single method `hasPlugin()` calls
`Holders.getPluginManager()` as a Groovy static call. With `@CompileStatic`, this compiles to a
direct `invokestatic` bytecode instruction — Groovy's metaClass dispatch is bypassed entirely,
making it impossible to mock `Holders` in unit tests using `GroovyMock(global: true)`.

Removing `@CompileStatic` restores Groovy dynamic dispatch so the service can be properly tested.
Runtime behaviour is identical — the method is a one-liner with no performance-sensitive logic.

```diff
-import groovy.transform.CompileStatic
-
-@CompileStatic
 class LocalizationService {
     Boolean hasPlugin(String name) {
         return Holders.getPluginManager()?.hasGrailsPlugin(name)
     }
 }
```

---

## 6. Assets — Removed

**Directory deleted:** `grails-app/assets/`

**Why:** The assets directory contained only default Grails scaffold boilerplate — Bootstrap CSS/JS,
jQuery, favicon, and skin icons — generated when the project was first scaffolded. None of the
plugin's GSP views reference these assets via `<asset:javascript>` or `<asset:stylesheet>` tags.

Shipping these files inside the plugin JAR is harmful: consuming applications already have their
own asset pipeline with their own versions of Bootstrap and jQuery. Bundling duplicates wastes
space and can cause version conflicts.

**Removed from `build.gradle` at the same time:**
```diff
// buildscript dependencies
-classpath "cloud.wondrify:asset-pipeline-gradle:5.0.34"

// apply plugins
-apply plugin: "cloud.wondrify.asset-pipeline"

// project dependencies
-assets "org.apache.grails:grails-dependencies-assets"
```

---

## 7. New: Test Suite

Four Spock spec files were created in `src/test/groovy/org/grails/plugins/localization/`.
No tests existed before this migration. All 50 tests pass.

### `LocalizationSpec.groovy`

Uses `DomainUnitTest<Localization>`. Covers:
- All domain constraints: code blank/size, locale pattern, relevance auto-set, text nullable
- `localeAsObj()` for 4-char, 2-char, and wildcard locales
- `getLocaleForFileName()` for base, language-only, and language+country filenames
- Cache: `statistics()`, `resetAll()`, `resetThis()` selective eviction
- `loadPropertyFile()`: import, skip-duplicate, locale-specific import
- `search()`: code match, text match, locale filter
- `getUniqLocales()`: sorted distinct list

### `LocalizationControllerSpec.groovy`

Uses `ControllerUnitTest<LocalizationController>` + `DataTest`. Covers all CRUD actions (index,
show, create, save, edit, update, delete), cache/reset, and search.

Key patterns required in Grails 7:

- `DataTest` mixin with `Class[] getDomainClassesToMock() { [Localization] }` — `ControllerUnitTest`
  alone does not initialise GORM.
- `controller.metaClass.message = { Map p -> p['default'] ?: p['code'] ?: '' }` — stubs the
  `message()` helper injected at runtime by `doWithDynamicMethods` (not available in unit tests).
  Note: `p['default']` not `p.default` — `default` is a Groovy keyword.
- `controller.localizationService = Mock(LocalizationService) { hasPlugin(_) >> false }` — service
  stub injected directly onto the controller instance.
- Controller actions returning a Map implicitly are captured as `def m = controller.action()` —
  the `model` property on the spec may return `[:]` for implicit Map returns in Grails 7.

### `LocalizationMessageSourceSpec.groovy`

Uses `DomainUnitTest<Localization>` (not a plain `Specification`).

`LocalizationMessageSource` is a Java class that calls `Localization.decodeMessage()` via a direct
Java static call. Groovy metaClass cannot intercept Java static calls, so tests seed real
`Localization` rows into the H2-backed GORM session and let the full call path execute end-to-end.

Covers `resolveCodeWithoutArguments`, `resolveCode`, and `setResourceLoader`.

### `LocalizationServiceSpec.groovy`

Uses `ServiceUnitTest<LocalizationService>`. Tests `hasPlugin()` with absent, false, and true
plugin manager states.

`Holders.getPluginManager()` uses an internal static field — registering a mock in the Spring
application context does not affect what `Holders` returns. `GroovyMock(global: true)` correctly
intercepts it because `LocalizationService` no longer has `@CompileStatic` (see §5).

Correct Spock syntax for stubbing a static method via global mock — use the **class name**, not
the mock variable:

```groovy
GroovyMock(Holders, global: true)
Holders.getPluginManager() >> mockManager   // ← class reference, NOT mockVar.getPluginManager()
```

---

## 8. Removed APIs Quick Reference

| Removed in Grails 7 | Replacement | Location |
|---------------------|-------------|----------|
| `grails.util.GrailsWebMockUtil` | `LocaleContextHolder.getLocale()` | `Localization.getMessage()` |
| `grails.web.context.ServletContextHolder` | (removed — `LocaleContextHolder` covers the need) | `Localization.getMessage()` |
| `RequestContextHolder` / `RequestContextUtils` | `LocaleContextHolder.getLocale()` | `Localization.getMessage()` |
| `WebApplicationContextUtils.getRequiredWebApplicationContext()` | `Holders.applicationContext` | `Localization.getMessage()` |
| `ClassRelativeResourcePatternResolver` (internal) | `PathMatchingResourcePatternResolver(Thread.currentThread().getContextClassLoader())` | `LocalizationsPluginUtils.getI18nResources()` |
| `DefaultGrailsApplication.getApplicationClass()` | Not needed with `PathMatchingResourcePatternResolver` | `LocalizationsPluginUtils.getI18nResources()` |
| HQL short DELETE: `"delete Localization"` | `"delete from Localization"` | `Localization.reload()` |
| HQL `?0`-based positional params | Named query / criteria (no positional params needed) | `Localization.decodeMessage()` |
| HQL `findAll("from ...")` in unit tests | `namedQueries` / `createCriteria()` | `Localization.decodeMessage()` |
| `@CompileStatic` on `LocalizationService` | Removed to allow `GroovyMock` interception | `LocalizationService` |
| `grails-app/assets/` directory | Deleted — plugin had no real assets | Build |
| `asset-pipeline-gradle` plugin | Deleted — no longer needed | `build.gradle` |
