package org.grails.plugins.localization

import grails.util.Holders

class LocalizationService {

    Boolean hasPlugin(String name) {
        return Holders.getPluginManager()?.hasGrailsPlugin(name)
    }
}
