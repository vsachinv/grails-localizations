package org.grails.plugins.localization

import grails.converters.JSON
import grails.gorm.transactions.Transactional
import org.springframework.context.i18n.LocaleContextHolder

class LocalizationController {
    // HTML forms use POST; REST clients use the appropriate HTTP verb
    static allowedMethods = [
        save  : ['POST'],
        update: ['POST', 'PUT', 'PATCH'],
        delete: ['POST', 'DELETE'],
        reset : ['POST'],
        load  : ['POST']
    ]

    def localizationService

    def index() {
        // The following line has the effect of checking whether this plugin
        // has just been installed and, if so, gets the plugin to load all
        // message bundles from the i18n directory BEFORE we attempt to display
        // them in this list!
        message(code: "home", default: "Home")

        Integer max = 50
        Integer dflt = 20

        // If the settings plugin is available, try and use it for pagination
        if (localizationService.hasPlugin("settings")) {

            // This convolution is necessary because this plugin can't see the
            // domain classes of another plugin
            def setting = grailsApplication.getDomainClass('org.grails.plugins.settings.Setting')?.newInstance()
            if (!setting) //compatibility with Settings plugin v. 1.0
                setting = grailsApplication.getDomainClass('Setting')?.newInstance()

            max = setting.valueFor("pagination.max", max)
            dflt = setting.valueFor("pagination.default", dflt)
        }

        params.max = (params.max && params.max.toInteger() > 0) ? Math.min(params.max.toInteger(), max) : dflt
        params.sort = params.sort ?: "code"

        List<Localization> lst
        if (localizationService.hasPlugin("criteria") || localizationService.hasPlugin("drilldowns")) {
            lst = Localization.selectList(session, params)
        } else {
            lst = Localization.list(params)
        }

        Integer total = Localization.count()
        Integer offset = (params.offset?.toInteger() ?: 0)
        Integer pageMax = params.max.toInteger()

        withFormat {
            html {[
                    localizationList     : lst,
                    localizationListCount: total,
                    uniqLocales          : Localization.uniqLocales
            ]}
            json {
                respond([
                    data      : lst,
                    total     : total,
                    max       : pageMax,
                    offset    : offset,
                    page      : (offset / pageMax).toInteger() + 1,
                    totalPages: (int) Math.ceil(total / pageMax)
                ])
            }
        }
    }

    def search() {
        params.max = (params.max && params.max.toInteger() > 0) ? Math.min(params.max.toInteger(), 50) : 20
        params.order = params.order ? params.order : (params.sort ? 'desc' : 'asc')
        params.sort = params.sort ?: "code"
        List<Localization> lst = Localization.search(params)
        withFormat {
            html { render(view: 'index', model: [
                    localizationList     : lst,
                    localizationListCount: lst.size(),
                    uniqLocales          : Localization.uniqLocales
            ])}
            json { respond lst }
        }
    }

    def list() {
        redirect(action: 'index')
    }

    def show() {
        withLocalization { localization ->
            withFormat {
                html { return [localization: localization] }
                json { respond localization }
            }
        }
    }

    @Transactional
    def delete() {
        withLocalization { localization ->
            localization.delete()
            Localization.resetThis(localization.code)
            withFormat {
                html {
                    flash.message = "localization.deleted"
                    flash.args = [params.id]
                    flash.defaultMessage = "Localization ${params.id} deleted"
                    redirect(action: 'index')
                }
                '*' { render status: 204 }
            }
        }
    }

    def edit() {
        withLocalization { localization ->
            return [localization: localization]
        }
    }

    @Transactional
    def update() {
        Localization localization = Localization.get(params.id)
        if (localization) {
            String oldCode = localization.code
            localization.properties = params
            if (!localization.hasErrors() && localization.save()) {
                Localization.resetThis(oldCode)
                if (localization.code != oldCode) Localization.resetThis(localization.code)
                withFormat {
                    html {
                        flash.message = "localization.updated"
                        flash.args = [params.id]
                        flash.defaultMessage = "Localization ${params.id} updated"
                        redirect(action: 'show', id: localization.id)
                    }
                    json { respond localization }
                }
            } else {
                withFormat {
                    html { render(view: 'edit', model: [localization: localization]) }
                    json { respond localization.errors, [status: 422] }
                }
            }
        } else {
            withFormat {
                html {
                    flash.message = "localization.not.found"
                    flash.args = [params.id]
                    flash.defaultMessage = "Localization not found with id ${params.id}"
                    redirect(action: 'edit', id: params.id)
                }
                json { render status: 404 }
            }
        }
    }

    def create() {
        Localization localization = new Localization()
        localization.properties = params
        return ['localization': localization]
    }

    @Transactional
    def save() {
        Localization localization = new Localization(params)
        if (!localization.hasErrors() && localization.save()) {
            Localization.resetThis(localization.code)
            withFormat {
                html {
                    flash.message = "localization.created"
                    flash.args = ["${localization.id}"]
                    flash.defaultMessage = "Localization ${localization.id} created"
                    redirect(action: 'show', id: localization.id)
                }
                json { respond localization, [status: 201] }
            }
        } else {
            withFormat {
                html { render(view: 'create', model: [localization: localization]) }
                json { respond localization.errors, [status: 422] }
            }
        }
    }

    def cache() {
        return [stats: Localization.statistics()]
    }

    def reset() {
        Localization.resetAll()
        redirect(action: 'cache')
    }

    def imports() {
        // The following line has the effect of checking whether this plugin
        // has just been installed and, if so, gets the plugin to load all
        // message bundles from the i18n directory BEFORE we attempt to display
        // the property files here.
        message(code: "home", default: "Home")

        List<String> names = []
        String path = servletContext.getRealPath("/")
        if (path) {
            File dir = new File(new File(path).getParent(), "grails-app${File.separator}i18n")
            if (dir.exists() && dir.canRead()) {
                def name
                dir.listFiles().each {
                    if (it.isFile() && it.canRead() && it.getName().endsWith(".properties")) {
                        name = it.getName()
                        names << name.substring(0, name.length() - 11)
                    }
                }
                names.sort()
            }
        }

        return [names: names]
    }

    @Transactional
    def load() {
        String name = params.file
        if (name) {
            name += ".properties"
            String path = servletContext.getRealPath("/")
            if (path) {
                File dir = new File(new File(path).getParent(), "grails-app${File.separator}i18n")
                if (dir.exists() && dir.canRead()) {
                    File file = new File(dir, name)
                    if (file.isFile() && file.canRead()) {
                        Locale locale = Localization.getLocaleForFileName(name)

                        Map counts = Localization.loadPropertyFile(new InputStreamReader(new FileInputStream(file), "UTF-8"), locale)
                        flash.message = "localization.imports.counts"
                        flash.args = [counts.imported, counts.skipped]
                        flash.defaultMessage = "Imported ${counts.imported} key(s). Skipped ${counts.skipped} key(s)."
                    } else {
                        flash.message = "localization.imports.access"
                        flash.args = [file]
                        flash.defaultMessage = "Unable to access ${file}"
                    }
                } else {
                    flash.message = "localization.imports.access"
                    flash.args = [dir]
                    flash.defaultMessage = "Unable to access ${dir}"
                }
            } else {
                flash.message = "localization.imports.access"
                flash.args = ["/"]
                flash.defaultMessage = "Unable to access /"
            }
        } else {
            flash.message = "localization.imports.missing"
            flash.defaultMessage = "No properties file selected"
        }

        redirect(action: "imports")
    }

    // returns localizations as jsonp. Useful for displaying text in client side templates.
    // It is possible to limit the messages returned by providing a codeBeginsWith parameter
    // Currently, there is no caching. Will have to add. 
    def jsonp() {
        Locale currentLocale = LocaleContextHolder.getLocale() //.toString.replaceAll('_','')
        String padding = params.padding ?: 'messages' //JSONP
        List<Localization> localizations = Localization.createCriteria().list {
            if (params.codeBeginsWith) ilike "code", "${params.codeBeginsWith}%"
            or {
                eq "locale", "*"
                eq "locale", currentLocale.getLanguage()
                eq "locale", currentLocale.getLanguage() + currentLocale.getCountry()
            }
            order("locale")
        }
        Map<String, String> localizationsMap = [:]
        localizations.each {
            // if there are duplicate codes found, as the results are ordered by locale, the more specific should overwrite the less specific
            localizationsMap[it.code] = it.text
        }
        render "$padding=${localizationsMap as JSON};"
    }

    private def withLocalization(id = "id", Closure c) {
        Localization localization = Localization.get(params[id])
        if (localization) {
            c.call localization
        } else {
            withFormat {
                html {
                    flash.message = "localization.not.found"
                    flash.args = [params.id]
                    flash.defaultMessage = "Localization not found with id ${params.id}"
                    redirect(action: 'index')
                }
                '*' { render status: 404 }
            }
        }
    }

}
