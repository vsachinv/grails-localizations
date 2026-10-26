package org.grails.plugins.localization

import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class LocalizationsPluginUtilsSpec extends Specification {

    @TempDir
    File tmp

    private static Resource res(String name) {
        new ByteArrayResource(new byte[0]) {
            @Override String getFilename() { name }
        }
    }

    @Unroll
    void "default excludes drop config file #name"() {
        expect:
        !LocalizationsPluginUtils.isMessageBundle(name, [], [])

        where:
        name << ['application.properties', 'application-prod.properties', 'build-info.properties',
                 'git.properties', 'logback.properties', 'spring.properties', 'not-a-bundle.txt', null]
    }

    @Unroll
    void "default excludes keep message bundle #name"() {
        expect:
        LocalizationsPluginUtils.isMessageBundle(name, [], [])

        where:
        name << ['messages.properties', 'messages_fr.properties', 'messages_en_US.properties',
                 'localizations.properties', 'validation_de.properties',
                 // Grails Spring Security Core plugin bundle: must not be caught by the spring.properties exclude
                 'spring-security-core.properties', 'spring-security-core_fr.properties']
    }

    void "includes do not restrict bundles that are imported by default"() {
        expect:
        LocalizationsPluginUtils.isMessageBundle('messages_fr.properties', ['messages*'], [])
        LocalizationsPluginUtils.isMessageBundle('validation.properties', ['messages*'], [])
    }

    void "custom excludes are honoured"() {
        expect:
        !LocalizationsPluginUtils.isMessageBundle('internal_en.properties', [], ['internal*'])
    }

    void "includes rescue files a default exclude would drop"() {
        expect: 'a file a default exclude would drop is kept when the consumer included it'
        LocalizationsPluginUtils.isMessageBundle('spring.properties', ['spring*'], [])
        LocalizationsPluginUtils.isMessageBundle('application.properties', ['application*'], [])

        and: 'other default-excluded files stay excluded'
        !LocalizationsPluginUtils.isMessageBundle('git.properties', ['spring*'], [])
    }

    void "consumer excludes win over includes"() {
        expect:
        !LocalizationsPluginUtils.isMessageBundle('spring-security-core.properties', ['spring*'], ['spring-security*'])
    }

    void "filterMessageBundles removes config resources from a classpath scan"() {
        given:
        List<Resource> scanned = [res('application.properties'), res('messages.properties'),
                                  res('git.properties'), res('messages_fr.properties')]

        when:
        List<Resource> kept = LocalizationsPluginUtils.filterMessageBundles(scanned)

        then:
        kept*.filename == ['messages.properties', 'messages_fr.properties']
    }

    // ---- origin scoping (PVCM-13690: jsv-messages.properties from json-schema-validator) ----

    void "bundles inside dependency jars are dropped, the application's own bundles are kept"() {
        given: 'an application classes dir and a dependency jar with a root-level bundle'
        File appClasses = new File(tmp, 'classes')
        appClasses.mkdirs()
        new File(appClasses, 'messages.properties') << 'app.hello=Hello\n'
        new File(appClasses, 'localizations.properties') << 'x=y\n'
        new File(appClasses, 'application.properties') << 'spring.main.banner-mode=off\n'

        File dependencyJar = new File(tmp, 'json-schema-validator.jar')
        new JarOutputStream(dependencyJar.newOutputStream()).withCloseable { JarOutputStream jos ->
            jos.putNextEntry(new JarEntry('jsv-messages.properties'))
            jos.write('false=Boolean schema false is not valid\n'.getBytes('UTF-8'))
            jos.closeEntry()
        }
        ClassLoader cl = new URLClassLoader([appClasses.toURI().toURL(), dependencyJar.toURI().toURL()] as URL[], (ClassLoader) null)
        List<Resource> scanned = new PathMatchingResourcePatternResolver(cl)
                .getResources(LocalizationsPluginUtils.messageBundleLocationPattern).toList()

        expect: 'the raw scan does see the dependency bundle'
        'jsv-messages.properties' in scanned*.filename

        when: 'origin scope then filename filter are applied, as getI18nResources does'
        String root = appClasses.toURI().toURL().toString()
        List<Resource> scoped = LocalizationsPluginUtils.scopeToApplication(scanned, root)
        List<Resource> kept = LocalizationsPluginUtils.filterMessageBundles(scoped)

        then: 'the dependency jar bundle is gone after scoping, config file gone after filtering'
        !('jsv-messages.properties' in scoped*.filename)
        'application.properties' in scoped*.filename
        kept*.filename.sort() == ['localizations.properties', 'messages.properties']
    }

    @Unroll
    void "isInsideRoot handles #layout layout"() {
        expect:
        LocalizationsPluginUtils.isInsideRoot(inside, root)
        !LocalizationsPluginUtils.isInsideRoot(outside, root)

        where:
        layout     | root                                              | inside                                                        | outside
        'war'      | 'file:/srv/app/WEB-INF/classes/'                  | 'file:/srv/app/WEB-INF/classes/messages.properties'           | 'jar:file:/srv/app/WEB-INF/lib/jsv.jar!/jsv-messages.properties'
        'boot jar' | 'jar:file:/srv/app.jar!/BOOT-INF/classes!/'       | 'jar:file:/srv/app.jar!/BOOT-INF/classes!/messages.properties' | 'jar:file:/srv/app.jar!/BOOT-INF/lib/jsv.jar!/jsv-messages.properties'
        'exploded' | 'file:/build/classes/groovy/main/'                | 'file:/build/classes/groovy/main/messages_fr.properties'      | 'file:/build/classes/groovy/main-other/messages.properties'
    }

    void "unknown application root leaves the scan unchanged"() {
        given:
        List<Resource> scanned = [res('messages.properties'), res('jsv-messages.properties')]

        expect:
        LocalizationsPluginUtils.scopeToApplication(scanned, null) == scanned
    }

    void "applicationCodeSourceRoot returns null outside a running Grails application"() {
        expect:
        LocalizationsPluginUtils.applicationCodeSourceRoot() == null
    }
}
