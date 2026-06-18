package org.grails.plugins.localization

import grails.testing.gorm.DomainUnitTest
import spock.lang.Specification

import java.text.MessageFormat

/**
 * LocalizationMessageSource is a Java class that calls Localization.decodeMessage()
 * via a direct static Java call — Groovy metaClass cannot intercept it.
 * Tests here use a real H2-backed GORM session (via DomainUnitTest) so the full
 * call path works end-to-end.
 */
class LocalizationMessageSourceSpec extends Specification implements DomainUnitTest<Localization> {

    LocalizationMessageSource messageSource

    def setup() {
        Localization.resetAll()
        messageSource = new LocalizationMessageSource()
    }

    // ── resolveCodeWithoutArguments ───────────────────────────────────────────

    void "resolveCodeWithoutArguments returns text stored in DB"() {
        given:
        new Localization(code: 'greet.hello', locale: '*', text: 'Hello').save(flush: true)

        when:
        String result = messageSource.resolveCodeWithoutArguments('greet.hello', Locale.ENGLISH)

        then:
        result == 'Hello'
    }

    void "resolveCodeWithoutArguments returns null when key not found"() {
        when:
        String result = messageSource.resolveCodeWithoutArguments('no.such.key', Locale.ENGLISH)

        then:
        result == null
    }

    void "resolveCodeWithoutArguments prefers language-specific entry over wildcard"() {
        given:
        new Localization(code: 'lang.key', locale: '*',  text: 'Global').save(flush: true)
        new Localization(code: 'lang.key', locale: 'fr', text: 'French').save(flush: true)

        when:
        String result = messageSource.resolveCodeWithoutArguments('lang.key', Locale.FRENCH)

        then:
        result == 'French'
    }

    // ── resolveCode ───────────────────────────────────────────────────────────

    void "resolveCode returns MessageFormat when message found"() {
        given:
        new Localization(code: 'greet.key', locale: '*', text: 'Hello {0}').save(flush: true)

        when:
        MessageFormat fmt = messageSource.resolveCode('greet.key', Locale.ENGLISH)

        then:
        fmt != null
        fmt.format(['World'] as Object[]) == 'Hello World'
    }

    void "resolveCode returns null when message not found"() {
        when:
        MessageFormat fmt = messageSource.resolveCode('missing.key', Locale.ENGLISH)

        then:
        fmt == null
    }

    // ── ResourceLoaderAware ───────────────────────────────────────────────────

    void "setResourceLoader stores the resource loader without error"() {
        given:
        def loader = Mock(org.springframework.core.io.ResourceLoader)

        when:
        messageSource.setResourceLoader(loader)

        then:
        noExceptionThrown()
    }
}
