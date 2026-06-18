package org.grails.plugins.localization

import grails.testing.gorm.DataTest
import grails.testing.web.controllers.ControllerUnitTest
import spock.lang.Specification

class LocalizationControllerSpec extends Specification
        implements ControllerUnitTest<LocalizationController>, DataTest {

    Class[] getDomainClassesToMock() { [Localization] }

    def setup() {
        Localization.resetAll()
        // inject a service stub that always reports no optional plugins installed
        controller.localizationService = Mock(LocalizationService) {
            hasPlugin(_) >> false
        }
        // stub message() — not injected in unit tests (doWithDynamicMethods doesn't run)
        controller.metaClass.message = { Map p -> p['default'] ?: p['code'] ?: '' }
    }

    // ── index ─────────────────────────────────────────────────────────────────

    void "index renders with empty localization list"() {
        when:
        def m = controller.index()

        then:
        m?.localizationList      != null
        m?.localizationListCount == 0
        m?.uniqLocales           != null
    }

    // ── show ──────────────────────────────────────────────────────────────────

    void "show redirects to index when id not found"() {
        given:
        params.id = 9999

        when:
        controller.show()

        then:
        response.redirectedUrl ==~ /.*index.*/
        flash.message == 'localization.not.found'
    }

    void "show returns localization for valid id"() {
        given:
        def loc = new Localization(code: 'test.key', locale: '*', text: 'Test').save(flush: true)
        params.id = loc.id

        when:
        def m = controller.show()

        then:
        m?.localization?.code == 'test.key'
    }

    // ── create ────────────────────────────────────────────────────────────────

    void "create returns new Localization instance"() {
        when:
        def m = controller.create()

        then:
        m.localization instanceof Localization
    }

    // ── save ──────────────────────────────────────────────────────────────────

    void "save persists valid localization and redirects to show"() {
        given:
        params.code   = 'save.key'
        params.locale = '*'
        params.text   = 'Saved'
        request.method = 'POST'

        when:
        controller.save()

        then:
        Localization.countByCode('save.key') == 1
        response.redirectedUrl ==~ /.*show.*/
        flash.message == 'localization.created'
    }

    void "save re-renders create view when validation fails"() {
        given:
        params.code   = ''
        params.locale = '*'
        params.text   = 'Bad'
        request.method = 'POST'

        when:
        controller.save()

        then:
        view == '/localization/create'
    }

    // ── edit ──────────────────────────────────────────────────────────────────

    void "edit returns localization model for valid id"() {
        given:
        def loc = new Localization(code: 'edit.key', locale: '*', text: 'Edit me').save(flush: true)
        params.id = loc.id

        when:
        def m = controller.edit()

        then:
        m?.localization?.id == loc.id
    }

    // ── update ────────────────────────────────────────────────────────────────

    void "update changes text and redirects to show"() {
        given:
        def loc = new Localization(code: 'upd.key', locale: '*', text: 'Old').save(flush: true)
        params.id     = loc.id
        params.code   = loc.code
        params.locale = loc.locale
        params.text   = 'New'
        request.method = 'POST'

        when:
        controller.update()

        then:
        Localization.get(loc.id).text == 'New'
        response.redirectedUrl ==~ /.*show.*/
        flash.message == 'localization.updated'
    }

    void "update redirects to edit when localization not found"() {
        given:
        params.id = 9999
        request.method = 'POST'

        when:
        controller.update()

        then:
        flash.message == 'localization.not.found'
    }

    // ── delete ────────────────────────────────────────────────────────────────

    void "delete removes localization and redirects to index"() {
        given:
        def loc = new Localization(code: 'del.key', locale: '*', text: 'Bye').save(flush: true)
        params.id = loc.id
        request.method = 'POST'

        when:
        controller.delete()

        then:
        Localization.get(loc.id) == null
        response.redirectedUrl ==~ /.*index.*/
        flash.message == 'localization.deleted'
    }

    // ── cache / reset ─────────────────────────────────────────────────────────

    void "cache action returns statistics map"() {
        when:
        def m = controller.cache()

        then:
        m.stats instanceof Map
        m.stats.containsKey('count')
        m.stats.containsKey('hits')
        m.stats.containsKey('misses')
    }

    void "reset action clears cache and redirects to cache view"() {
        when:
        request.method = 'POST'
        controller.reset()

        then:
        response.redirectedUrl ==~ /.*cache.*/
        Localization.statistics().count == 0
    }

    // ── search ────────────────────────────────────────────────────────────────

    void "search renders index view with matching results"() {
        given:
        new Localization(code: 'home.label', locale: '*', text: 'Home').save(flush: true)
        new Localization(code: 'about.label', locale: '*', text: 'About').save(flush: true)
        params.q = 'home'

        when:
        controller.search()

        then:
        view == '/localization/index'
        model.localizationList.size() == 1
        model.localizationList[0].code == 'home.label'
    }
}
