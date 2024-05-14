package org.grails.plugins.localization

import grails.plugins.Plugin
import grails.util.Holders
import org.grails.plugins.localization.Localization
import org.grails.plugins.localization.LocalizationMessageSource

class GrailsLocalizationsGrailsPlugin extends Plugin {

    // the version or versions of Grails the plugin is designed for
    def grailsVersion = "6.2.0  > *"
    // resources that are excluded from plugin packaging
    def pluginExcludes = [
            "grails-app/views/error.gsp"
    ]

    // TODO Fill in these fields
    def author = "Sachin Verma"
    def authorEmail = "v.sachin.v@gmail.com"
    def title = "Localizations (messages) plugin"

    def loadAfter = ['i18n']
    def dependsOn = [i18n: "* > 3.0"]

    def description = '''\
This plugin will pull i18n definitions from the database rather than from the standard properties files in the i18n folder for Grails 3 applications.

It will do the following:
* Create a domain class and corresponding table called Localization
* Prepopulate the table with all the message properties it finds in the i18n folder
* Ensure Grails writes i18n messages based on what it finds in the database rather than the 118n folder

In addtion the plugin also has these added features to help you:
* A CRUD UI to add, delete, and update i18n messages
* A cache for increased speed 
* A JSONP action which can be useful in client-side templating.

Asumptions:
* Your database supports unicode
* Your application has a layout called main
'''

    // URL to the plugin's documentation
    def documentation = "https://github.com/halfbaked/grails-localizations"
    def issueManagement = [ system: "GitHub", url: "https://github.com/halfbaked/grails-localizations/issues" ]
    def developers = [ [ name: "Eamonn O'Connell", email: "eamonnoconnell@gmail.com" ], [ name: "Sachin Verma", email: "v.sachin.v@gmail.com" ]]
    def scm = [ url: "https://github.com/halfbaked/grails-localizations" ]
    def profiles = ['web']

    // Extra (optional) plugin metadata

    // License: one of 'APACHE', 'GPL2', 'GPL3'
    //    def license = "APACHE"

    Closure doWithSpring() {
        { ->
            if(Holders.config.getProperty('grails.plugin.localizations.enabled', Boolean, false)){
                messageSource(LocalizationMessageSource)
            }
        }
    }

    void doWithDynamicMethods() {
        grailsApplication.domainClasses.each { domainClass ->
            domainClass.metaClass.message = { Map parameters -> Localization.getMessage(parameters) }
            domainClass.metaClass.errorMessage = { Map parameters -> Localization.setError(delegate, parameters) }
        }

        grailsApplication.serviceClasses.each { serviceClass ->
            serviceClass.metaClass.message = { Map parameters -> Localization.getMessage(parameters) }
        }
    }

    void doWithApplicationContext() {
        // TODO Implement post initialization spring config (optional)
    }

    void onChange(Map<String, Object> event) {
        // TODO Implement code that is executed when any artefact that this plugin is
        // watching is modified and reloaded. The event contains: event.source,
        // event.application, event.manager, event.ctx, and event.plugin.
    }

    void onConfigChange(Map<String, Object> event) {
        // TODO Implement code that is executed when the project configuration changes.
        // The event is the same as for 'onChange'.
    }

    void onShutdown(Map<String, Object> event) {
        // TODO Implement code that is executed when the application shuts down (optional)
    }
}