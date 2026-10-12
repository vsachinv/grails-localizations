package org.grails.plugins.localization

import grails.util.BuildSettings
import grails.util.Environment
import grails.util.Holders
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.grails.core.io.StaticResourceLoader
import org.grails.plugins.BinaryGrailsPlugin
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.io.support.ResourcePatternResolver

@Slf4j
class LocalizationsPluginUtils {

    static final String GRAILS_APP_I18N_PATH_COMPONENT = "/grails-app/i18n/";
    static String messageBundleLocationPattern = "classpath*:*.properties";

    /** Config key: list of filename globs to import (e.g. ['messages*']). Empty = import everything not excluded. */
    static final String INCLUDES_CONFIG_KEY = 'grails.plugin.localizations.includes'
    /** Config key: list of filename globs to skip, merged with DEFAULT_EXCLUDES. */
    static final String EXCLUDES_CONFIG_KEY = 'grails.plugin.localizations.excludes'

    /**
     * Since Grails 4 the grails-app/i18n files are packaged at the classpath root, next to
     * application/build/git properties. Those are config files, not message bundles, and must
     * never be imported into the Localization table.
     */
    static final List<String> DEFAULT_EXCLUDES = [
        'application*.properties',
        'build-info.properties',
        'git.properties',
        'grails.build.info',
        'logback*.properties',
        'log4j*.properties',
        'spring*.properties'
    ].asImmutable()

    @CompileStatic
    static Resource[] getI18nResources() {
        Resource[] resources = []
        if (Environment.isDevelopmentEnvironmentAvailable()) {
            File[] propertiesFiles = new File(BuildSettings.BASE_DIR, GRAILS_APP_I18N_PATH_COMPONENT).listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.endsWith(".properties");
                }
            });
            if (propertiesFiles != null && propertiesFiles.length > 0) {
                List<Resource> resourceList = new ArrayList<Resource>(propertiesFiles.length);
                for (File propertiesFile : propertiesFiles) {
                    log.debug "Project - " + propertiesFile.name
                    resourceList.add(new FileSystemResource(propertiesFile));
                }
                resources = resourceList.toArray(new Resource[resourceList.size()]);
            } else {
                log.debug "No Messages Properties files found within project"
                resources = new Resource[0];
            }
        } else {
            ResourcePatternResolver resourcePatternResolver = new PathMatchingResourcePatternResolver(
                Thread.currentThread().getContextClassLoader()
            );
            List<Resource> scanned = resourcePatternResolver.getResources(messageBundleLocationPattern).toList()
            // 1. origin scope: only the application's own classes (as the 6.x ClassRelative resolver did)
            // 2. filename scope: drop config files that live next to the bundles
            List<Resource> bundles = filterMessageBundles(scopeToApplication(scanned, applicationCodeSourceRoot()))
            resources = bundles as Resource[]
        }
        //Sort based on underscore tokens count as more underscores means more exact locale value
        Resource[] sorted = resources.sort { Resource x, Resource y ->
            x.filename.tokenize('_').size() <=> y.filename.tokenize('_').size()
        } as Resource[]
        return sorted.reverse() as Resource[]
    }

    @CompileStatic
    static List<Resource> getAllPluginI18nResources() {
        List<Resource> resources = []
        Holders.pluginManager.allPlugins.each {
            if (it instanceof BinaryGrailsPlugin) {
                Resource[] scanned = getPluginI18nResources(it)
                List<Resource> pluginResources = scanned ? filterMessageBundles(scanned.toList()) : []
                if (pluginResources) {
                    log.debug "Plugin - " + it.name
                    resources.addAll(pluginResources)
                }
            }
        }
        //Sort based on underscore tokens count as more underscores means more exact locale value
        return resources.sort { x, y ->
            x.filename.tokenize('_').size() <=> y.filename.tokenize('_').size()
        }.reverse()

    }

    static Resource[] getPluginI18nResources(BinaryGrailsPlugin plugin) {
        Resource url = plugin.baseResourcesResource;
        if (url != null) {
            StaticResourceLoader resourceLoader = new StaticResourceLoader();
            resourceLoader.setBaseResource(url);
            ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(resourceLoader);
            try {
                // first load all properties
                return resolver.getResources('*' + plugin.PROPERTIES_EXTENSION)
            } catch (IOException e) {
            }
        }
        return null;
    }


    /**
     * Keeps only resources whose filename looks like a message bundle according to the
     * configured include/exclude globs (see INCLUDES_CONFIG_KEY / EXCLUDES_CONFIG_KEY).
     */
    @CompileStatic
    static List<Resource> filterMessageBundles(List<Resource> resources) {
        List<String> includes = configList(INCLUDES_CONFIG_KEY)
        List<String> excludes = DEFAULT_EXCLUDES + configList(EXCLUDES_CONFIG_KEY)
        List<Resource> kept = []
        resources.each { Resource r ->
            if (isMessageBundle(r.filename, includes, excludes)) {
                kept << r
            } else {
                log.debug "Skipping non-i18n properties resource - " + r.filename
            }
        }
        return kept
    }

    @CompileStatic
    static boolean isMessageBundle(String filename, List<String> includes, List<String> excludes) {
        if (!filename || !filename.endsWith('.properties')) {
            return false
        }
        if (excludes.any { String glob -> globMatches(glob, filename) }) {
            return false
        }
        if (includes) {
            return includes.any { String glob -> globMatches(glob, filename) }
        }
        return true
    }

    @CompileStatic
    private static boolean globMatches(String glob, String filename) {
        String regex = glob.split('\\*', -1).collect { String part -> java.util.regex.Pattern.quote(part) }.join('.*')
        return filename ==~ regex
    }

    @CompileStatic
    private static List<String> configList(String key) {
        Object value = null
        try {
            value = Holders.config?.getProperty(key, Object)
        } catch (IllegalStateException ignored) {
            // No Grails application bound (e.g. plain unit test) - no config overrides
        }
        if (value == null) return []
        if (value instanceof Collection) return ((Collection) value).collect { it.toString() }
        return value.toString().split(',').collect { String s -> s.trim() }.findAll { String s -> s } as List<String>
    }

    /**
     * Keeps only resources that live inside the application's own code source (WEB-INF/classes,
     * BOOT-INF/classes or build/classes). Resources contributed by dependency jars such as
     * json-schema-validator's jsv-messages.properties are dropped. With a null root (origin
     * unknown) the list is returned unchanged and only the filename filter applies.
     */
    @CompileStatic
    static List<Resource> scopeToApplication(List<Resource> scanned, String applicationRoot) {
        if (!applicationRoot) {
            return scanned
        }
        List<Resource> kept = []
        scanned.each { Resource r ->
            String url = resourceUrl(r)
            if (url != null && isInsideRoot(url, applicationRoot)) {
                kept << r
            } else {
                log.debug "Skipping properties resource outside the application - " + url
            }
        }
        return kept
    }

    /**
     * The URL prefix of the Application class's code source, normalised so both exploded/war
     * (file:/.../WEB-INF/classes/) and Spring Boot fat jar (jar:file:/app.jar!/BOOT-INF/classes!/)
     * layouts can be prefix-matched against resource URLs. Null if it cannot be determined.
     */
    @CompileStatic
    static String applicationCodeSourceRoot() {
        try {
            Object app = Holders.findApplication()
            Object appClass = app?.hasProperty('applicationClass') ? app['applicationClass'] : null
            URL location = appClass?.getClass()?.protectionDomain?.codeSource?.location
            if (location == null) {
                log.warn "Cannot determine the Application class code source; i18n scan is filtered by filename only"
                return null
            }
            return normaliseRoot(location.toString())
        } catch (Exception e) {
            log.warn "Cannot determine the Application class code source; i18n scan is filtered by filename only", e
            return null
        }
    }

    @CompileStatic
    static String normaliseRoot(String root) {
        String r = root
        if (r.endsWith('!/')) r = r.substring(0, r.length() - 2)
        if (r.endsWith('/')) r = r.substring(0, r.length() - 1)
        if (r.startsWith('jar:')) r = r.substring(4)
        return r
    }

    @CompileStatic
    static boolean isInsideRoot(String resourceUrl, String applicationRoot) {
        String u = normaliseRoot(resourceUrl)
        String root = normaliseRoot(applicationRoot)
        if (!u.startsWith(root)) return false
        String rest = u.substring(root.length())
        // the resource must sit directly under the root, not in a sibling jar sharing the prefix
        return rest.startsWith('/') || rest.startsWith('!/')
    }

    @CompileStatic
    private static String resourceUrl(Resource r) {
        try {
            return r.URL.toString()
        } catch (IOException ignored) {
            return null
        }
    }
}
