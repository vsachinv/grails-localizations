package org.grails.plugins.localization

import grails.testing.gorm.DomainUnitTest
import spock.lang.Specification

class LocalizationSpec extends Specification implements DomainUnitTest<Localization> {

    def setup() {
        Localization.resetAll()
    }

    // ── Constraints ──────────────────────────────────────────────────────────

    void "code must not be blank"() {
        given:
        def loc = new Localization(code: '', locale: '*', text: 'hello')

        expect:
        !loc.validate()
        loc.errors['code']
    }

    void "code must not exceed 250 characters"() {
        given:
        def loc = new Localization(code: 'a' * 251, locale: '*', text: 'hello')

        expect:
        !loc.validate()
        loc.errors['code']
    }

    void "locale must match allowed pattern"() {
        expect:
        new Localization(code: 'k', locale: locale, text: 't').validate() == valid

        where:
        locale  | valid
        '*'     | true
        'en'    | true
        'enUS'  | true
        'EN'    | false   // must be lowercase language
        'enUSX' | false   // too long
        '1'     | false   // digits not allowed
        ''      | false   // blank
    }

    void "relevance is automatically set to locale length by validator"() {
        given:
        def loc = new Localization(code: 'test.key', locale: 'enUS', text: 'hello')
        loc.validate()

        expect:
        loc.relevance == 4
    }

    void "text may be null"() {
        given:
        def loc = new Localization(code: 'test.key', locale: '*', text: null)

        expect:
        loc.validate()
    }

    void "text may be empty string"() {
        given:
        // GORM 9 coerces '' to null; nullable: true in constraints permits this
        def loc = new Localization(code: 'test.key', locale: '*', text: '')

        expect:
        loc.validate()
    }

    void "text must not exceed 2000 characters"() {
        given:
        def loc = new Localization(code: 'test.key', locale: '*', text: 'a' * 2001)

        expect:
        !loc.validate()
        loc.errors['text']
    }

    // ── localeAsObj ───────────────────────────────────────────────────────────

    void "localeAsObj returns correct Locale for 4-char locale"() {
        given:
        def loc = new Localization(locale: 'enUS')

        when:
        Locale result = loc.localeAsObj()

        then:
        result.language == 'en'
        result.country  == 'US'
    }

    void "localeAsObj returns correct Locale for 2-char locale"() {
        given:
        def loc = new Localization(locale: 'en')

        when:
        Locale result = loc.localeAsObj()

        then:
        result.language == 'en'
        result.country  == ''
    }

    void "localeAsObj returns null for wildcard locale"() {
        given:
        def loc = new Localization(locale: '*')

        expect:
        loc.localeAsObj() == null
    }

    // ── getLocaleForFileName ──────────────────────────────────────────────────

    void "getLocaleForFileName returns null for base properties file"() {
        expect:
        Localization.getLocaleForFileName('messages.properties') == null
    }

    void "getLocaleForFileName parses 2-char language suffix"() {
        when:
        Locale locale = Localization.getLocaleForFileName('messages_fr.properties')

        then:
        locale.language == 'fr'
        locale.country  == ''
    }

    void "getLocaleForFileName parses language+country suffix"() {
        when:
        Locale locale = Localization.getLocaleForFileName('messages_fr_FR.properties')

        then:
        locale.language == 'fr'
        locale.country  == 'FR'
    }

    // ── Cache: resetAll / statistics / resetThis ──────────────────────────────

    void "statistics returns zeroed map after resetAll"() {
        when:
        def stats = Localization.statistics()

        then:
        stats.count  == 0
        stats.hits   == 0
        stats.misses == 0
        stats.size   == 0
    }

    void "resetThis removes only entries whose key starts with the given prefix"() {
        given:
        def cacheField = Localization.getDeclaredField('cache')
        cacheField.setAccessible(true)
        def cacheMap = cacheField.get(null) as Map

        def currentSizeField = Localization.getDeclaredField('currentCacheSize')
        currentSizeField.setAccessible(true)

        def delimField = Localization.getDeclaredField('keyDelimiter')
        delimField.setAccessible(true)
        String kd = delimField.get(null)

        String key1 = "foo${kd}en"
        String key2 = "bar${kd}en"

        synchronized (cacheMap) {
            cacheMap.put(key1, 'value1')
            cacheMap.put(key2, 'value2')
            currentSizeField.set(null, (key1 + 'value1').length() + (key2 + 'value2').length() as long)
        }

        when:
        Localization.resetThis('foo')

        then:
        synchronized (cacheMap) {
            !cacheMap.containsKey(key1)
            cacheMap.containsKey(key2)
        }
    }

    // ── loadPropertyFile ─────────────────────────────────────────────────────

    void "loadPropertyFile imports new keys and skips duplicates"() {
        given:
        String props = "greeting=Hello\nfarewell=Goodbye\n"
        InputStreamReader reader = new InputStreamReader(new ByteArrayInputStream(props.bytes), 'UTF-8')

        when:
        Map counts = Localization.loadPropertyFile(reader, null)

        then:
        counts.imported == 2
        counts.skipped  == 0
        Localization.countByLocale('*') == 2
    }

    void "loadPropertyFile skips keys already present in the database"() {
        given:
        new Localization(code: 'greeting', locale: '*', text: 'Hi').save(flush: true)

        String props = "greeting=Hello\nfarewell=Goodbye\n"
        InputStreamReader reader = new InputStreamReader(new ByteArrayInputStream(props.bytes), 'UTF-8')

        when:
        Map counts = Localization.loadPropertyFile(reader, null)

        then:
        counts.imported == 1
        counts.skipped  == 1
    }

    void "loadPropertyFile uses locale language+country when locale provided"() {
        given:
        Locale fr = new Locale('fr', 'FR')
        String props = "greeting=Bonjour\n"
        InputStreamReader reader = new InputStreamReader(new ByteArrayInputStream(props.bytes), 'UTF-8')

        when:
        Localization.loadPropertyFile(reader, fr)

        then:
        Localization.countByLocale('frFR') == 1
    }

    // ── search ────────────────────────────────────────────────────────────────

    void "search finds entries matching code"() {
        given:
        new Localization(code: 'home.title', locale: '*', text: 'Home').save(flush: true)
        new Localization(code: 'about.title', locale: '*', text: 'About').save(flush: true)

        when:
        List results = Localization.search([q: 'home', max: 10, sort: 'code', order: 'asc'])

        then:
        results.size() == 1
        results[0].code == 'home.title'
    }

    void "search finds entries matching text"() {
        given:
        new Localization(code: 'home.title', locale: '*', text: 'Welcome Home').save(flush: true)
        new Localization(code: 'about.title', locale: '*', text: 'About Us').save(flush: true)

        when:
        List results = Localization.search([q: 'welcome', max: 10, sort: 'code', order: 'asc'])

        then:
        results.size() == 1
        results[0].code == 'home.title'
    }

    void "search filters by locale when provided"() {
        given:
        new Localization(code: 'key', locale: '*',  text: 'Global').save(flush: true)
        new Localization(code: 'key', locale: 'fr', text: 'French').save(flush: true)

        when:
        List results = Localization.search([q: 'key', locale: 'fr', max: 10, sort: 'code', order: 'asc'])

        then:
        results.size() == 1
        results[0].locale == 'fr'
    }

    // ── getUniqLocales ────────────────────────────────────────────────────────

    void "getUniqLocales returns sorted distinct locales"() {
        given:
        new Localization(code: 'a', locale: 'fr', text: 'a').save(flush: true)
        new Localization(code: 'b', locale: '*',  text: 'b').save(flush: true)
        new Localization(code: 'c', locale: 'en', text: 'c').save(flush: true)
        new Localization(code: 'd', locale: 'fr', text: 'd').save(flush: true)

        when:
        List<String> locales = Localization.uniqLocales

        then:
        locales == ['*', 'en', 'fr']
    }
}
