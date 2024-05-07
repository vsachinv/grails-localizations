package org.grails.plugins.localization

import grails.localizations.LocalizationsPluginUtils
import grails.util.GrailsWebMockUtil
import grails.util.Holders
import grails.web.context.ServletContextHolder
import groovy.transform.CompileStatic
import org.springframework.core.io.Resource
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.support.WebApplicationContextUtils
import org.springframework.web.servlet.support.RequestContextUtils


class Localization implements Serializable {

    private static final Map cache = new LinkedHashMap((int) 16, (float) 0.75, (boolean) true)
    private static long maxCacheSize = 128L * 1024L // Cache size in KB (default is 128kb)
    private static long currentCacheSize = 0L
    private static final missingValue = "\b" // an impossible value signifying that no such code exists in the database
    private static final keyDelimiter = missingValue
    private static long cacheHits = 0L
    private static long cacheMisses = 0L

    String code
    String locale
    Byte relevance = 0
    String text
    Date dateCreated
    Date lastUpdated

    static mapping = Holders.config.grails.plugin.localizations.mapping ?: {
        columns {
            code index: "localizations_idx"
            locale column: "loc"
        }
    }

    static constraints = {
        code(blank: false, size: 1..250)
        locale(size: 1..4, unique: 'code', blank: false, matches: "\\*|([a-z][a-z]([A-Z][A-Z])?)")
        relevance(validator: { val, obj ->
            if (obj.locale) obj.relevance = obj.locale.length()
            return true
        })
        text(blank: true, size: 0..2000)
    }

    Locale localeAsObj() {
        switch (locale.size()) {
            case 4:
                return new Locale(locale[0..1], locale[2..3])
            case 2:
                return new Locale(locale)
            default:
                return null
        }
    }

    static String decodeMessage(String code, Locale locale) {

        String key = code + keyDelimiter + locale.getLanguage() + locale.getCountry()
        def msg
        if (maxCacheSize > 0) {
            synchronized (cache) {
                msg = cache.get(key)
                if (msg) {
                    cacheHits++
                } else {
                    cacheMisses++
                }
            }
        }

        if (!msg) {
            Localization.withNewSession {
                List<Localization> lst = Localization.findAll(
                        "from org.grails.plugins.localization.Localization as x where x.code = ?0 and x.locale in ('*', ?1, ?2) order by x.relevance desc",
                        [code, locale.getLanguage(), locale.getLanguage() + locale.getCountry()])
                msg = lst.size() > 0 ? lst[0].text : missingValue
            }

            if (maxCacheSize > 0) {
                synchronized (cache) {

                    // Put it in the cache
                    def prev = cache.put(key, msg)

                    // Another user may have inserted it while we weren't looking
                    if (prev != null) currentCacheSize -= key.length() + prev.length()

                    // Increment the cache size with our data
                    currentCacheSize += key.length() + msg.length()

                    // Adjust the cache size if required
                    if (currentCacheSize > maxCacheSize) {
                        def entries = cache.entrySet().iterator()
                        def entry
                        while (entries.hasNext() && currentCacheSize > maxCacheSize) {
                            entry = entries.next()
                            currentCacheSize -= entry.getKey().length() + entry.getValue().length()
                            entries.remove()
                        }
                    }
                }
            }
        }

        return (msg == missingValue) ? null : msg
    }

    static String getMessage(Map parameters) {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes()
        WebApplicationContext applicationContext = WebApplicationContextUtils.getRequiredWebApplicationContext(ServletContextHolder.getServletContext())
        boolean unbindRequest = false

        // Outside of an executing request, establish a mock version
        if (!requestAttributes) {
            requestAttributes = GrailsWebMockUtil.bindMockWebRequest(applicationContext)
            unbindRequest = true
        }

        def messageSource = applicationContext.getBean("messageSource")
        def locale = RequestContextUtils.getLocale(requestAttributes.request)

        // What the heck is going on here with RequestContextUtils.getLocale() returning a String?
        // Beats the hell out of me, so just fix it!
        if (locale instanceof String) {

            // Now Javasoft have lost the plot and you can't easily get from a Locale.toString() back to a locale. Aaaargh!
            if (locale.length() >= 5) {
                locale = new Locale(locale[0..1], locale[3..4])
            } else {
                locale = new Locale(locale as String)
            }
        }

        String msg = messageSource.getMessage(parameters.code as String, parameters.args as Object[], parameters.default as String, locale)

        if (unbindRequest) RequestContextHolder.setRequestAttributes(null)
        if (parameters.encodeAs) {
            switch (parameters.encodeAs.toLowerCase()) {
                case 'html':
                    msg = msg.encodeAsHTML()
                    break

                case 'xml':
                    msg = msg.encodeAsXML()
                    break

                case 'url':
                    msg = msg.encodeAsURL()
                    break

                case 'javascript':
                    msg = msg.encodeAsJavaScript()
                    break

                case 'base64':
                    msg = msg.encodeAsBase64()
                    break
            }
        }

        return msg
    }

    static setError(domain, parameters) {
        def msg = Localization.getMessage(parameters)
        if (parameters.field) {
            domain.errors.rejectValue(parameters.field, null, msg)
        } else {
            domain.errors.reject(null, msg)
        }

        return msg
    }

    // Repopulates the org.grails.plugins.localization table from the i18n property files
    @CompileStatic
    static reload() {
        Localization.executeUpdate("delete Localization")
        load()
        resetAll()
    }

    // Leaves the existing data in the database table intact and pulls in newly messages in the property files not found in the database
    static syncWithPropertyFiles() {
        load()
        resetAll()
    }

    @CompileStatic
    static load() {
        List<Resource> propertiesResources = []
        LocalizationsPluginUtils.i18nResources?.each {
            propertiesResources << it
        }
        LocalizationsPluginUtils.allPluginI18nResources?.each {
            propertiesResources << it
        }


        Localization.log.debug("Properties files for localization : " + propertiesResources*.filename)

        propertiesResources.each {
            def locale = getLocaleForFileName(it.filename)
            Localization.loadPropertyFile(new InputStreamReader(it.inputStream, "UTF-8"), locale)
        }
        Integer size = Holders.config.getProperty('localizations.cache.size.kb', Integer)
        if (size != null && size >= 0 && size <= 1024 * 1024) {
            maxCacheSize = size * 1024L
        }
    }

    static Map loadPropertyFile(InputStreamReader inputStreamReader, locale) {
        String loc = locale ? locale.getLanguage() + locale.getCountry() : "*"
        Properties props = new Properties()
        Reader reader = new BufferedReader(inputStreamReader)
        try {
            props.load(reader)
        } finally {
            if (reader) reader.close()
        }

        Localization rec = null
        String txt = null
        Map<String, Integer> counts = [imported: 0, skipped: 0]
        Localization.withSession { session ->
            props.stringPropertyNames().each { key ->
                Boolean exist = !!Localization.countByCodeAndLocale(key, loc)
                if (!exist) {
                    txt = props.getProperty(key)
                    rec = new Localization([code: key, locale: loc, text: txt])
                    if (rec.validate()) {
                        rec.save()
                        counts.imported++
                    } else {
                        counts.skipped++
                    }
                } else {
                    counts.skipped++
                }
            }
            // Clear the whole cache if we actually imported any new keys
            if (counts.imported > 0) {
                Localization.resetAll()
                session.flush()
            }
        }
        return counts
    }

    @CompileStatic
    static Locale getLocaleForFileName(String fileName) {
        Locale locale = null
        if (fileName ==~ /.+_[a-z][a-z]_[A-Z][A-Z]\.properties$/) {
            locale = new Locale(fileName.substring(fileName.length() - 16, fileName.length() - 14), fileName.substring(fileName.length() - 13, fileName.length() - 11))
        } else if (fileName ==~ /.+_[a-z][a-z]\.properties$/) {
            locale = new Locale(fileName.substring(fileName.length() - 13, fileName.length() - 11))
        }
        return locale
    }

    @CompileStatic
    static void resetAll() {
        synchronized (cache) {
            cache.clear()
            currentCacheSize = 0L
            cacheHits = 0L
            cacheMisses = 0L
        }
    }

    static void resetThis(String key) {
        key += keyDelimiter
        synchronized (cache) {
            def entries = cache.entrySet().iterator()
            def entry
            while (entries.hasNext()) {
                entry = entries.next()
                if (entry.getKey().startsWith(key)) {
                    currentCacheSize -= entry.getKey().length() + entry.getValue().length()
                    entries.remove()
                }
            }
        }
    }

    static Map statistics() {
        Map stats = [:]
        synchronized (cache) {
            stats.max = maxCacheSize
            stats.size = currentCacheSize
            stats.count = cache.size()
            stats.hits = cacheHits
            stats.misses = cacheMisses
        }
        return stats
    }

    static List<Localization> search(Map params) {
        String expr = "%${params.q}%".toString().toLowerCase()
        return Localization.createCriteria().list(limit: params.max, order: params.order, sort: params.sort) {
            if (params.locale) {
                eq 'locale', params.locale
            }
            or {
                ilike 'code', expr
                ilike 'text', expr
            }
        }
    }

    static List<String> getUniqLocales() {
        return Localization.createCriteria().list {
            projections {
                distinct 'locale'
            }
        }.sort()
    }

}
