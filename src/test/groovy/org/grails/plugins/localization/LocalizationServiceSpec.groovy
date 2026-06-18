package org.grails.plugins.localization

import grails.plugins.GrailsPluginManager
import grails.testing.services.ServiceUnitTest
import grails.util.Holders
import spock.lang.Specification

class LocalizationServiceSpec extends Specification implements ServiceUnitTest<LocalizationService> {

    void "hasPlugin returns false when plugin manager is absent"() {
        given:
        GroovyMock(Holders, global: true)
        Holders.getPluginManager() >> null

        expect:
        !service.hasPlugin('somePlugin')
    }

    void "hasPlugin returns false when plugin is not installed"() {
        given:
        def mockManager = Mock(GrailsPluginManager) {
            hasGrailsPlugin('missingPlugin') >> false
        }
        GroovyMock(Holders, global: true)
        Holders.getPluginManager() >> mockManager

        expect:
        !service.hasPlugin('missingPlugin')
    }

    void "hasPlugin returns true when plugin is installed"() {
        given:
        def mockManager = Mock(GrailsPluginManager) {
            hasGrailsPlugin('settings') >> true
        }
        GroovyMock(Holders, global: true)
        Holders.getPluginManager() >> mockManager

        expect:
        service.hasPlugin('settings')
    }
}
