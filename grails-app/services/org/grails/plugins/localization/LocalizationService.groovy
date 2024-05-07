package org.grails.plugins.localization

import grails.util.Holders
import groovy.transform.CompileStatic

@CompileStatic
class LocalizationService {

    Boolean hasPlugin(String name) {
        return Holders.getPluginManager()?.hasGrailsPlugin(name)
    }
}
