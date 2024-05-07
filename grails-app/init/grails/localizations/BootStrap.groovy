package grails.localizations

import grails.gorm.transactions.Transactional
import grails.util.Holders
import groovy.util.logging.Slf4j
import org.grails.plugins.localization.Localization

@Slf4j
class BootStrap {

    def init = { servletContext ->
        if (Holders.config.getProperty('grails.plugin.localizations.enabled', Boolean, false)) {
            log.info("Localization Plugin: Loading localization files to DB")
            loadLocalizeDataToDB()
        }
    }

    def destroy = {
    }

    @Transactional
    void loadLocalizeDataToDB() {
        if (Holders.config.getProperty('grails.plugin.localizations.reloadAll', Boolean, false)) {
            log.warn("Re-Loading all localization data to db by refreshing localization table")
            Localization.reload()
        } else {
            Localization.load()
        }
    }

}
