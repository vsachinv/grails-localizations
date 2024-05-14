grails-localization
===================

The localizations plugin alters Grails to use the database as its means of
internationalization rather than message bundles (property files) in the i18n
directory of your application. All property files in the i18n directory of your
application (but not subdirectories of i18n) are automatically loaded in to the
database the first time a message is requested after the plugin is installed.

##Installation
Add dependency to your build.gradle for > Grails 6.x:

```
repositories {
  ...
  maven { url "https://jitpack.io" }
}

dependencies {
    compile 'com.github.vsachinv:grails-localizations:6.0-M1'
}
```

In addition if you don't want to use jitpack.io then use following github package registry:

```groovy
repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/vsachinv/grails-localizations")
            credentials {
                username = project.findProperty("gpr.user") ?: System.getenv("GITHUB_USERNAME")
                password = project.findProperty("gpr.key") ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
    
```  



Add dependency to your build.gradle for > Grails 5.x and < Grails 6.x:

```
repositories {
  ...
  maven { url "https://jitpack.io" }
}

dependencies {
    compile 'com.github.vsachinv:grails-localizations:5.0-M1'
}
```

In addition if you don't want to use jitpack.io then use following github package registry:

```groovy
repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/vsachinv/grails-localizations")
            credentials {
                username = project.findProperty("gpr.user") ?: System.getenv("GITHUB_USERNAME")
                password = project.findProperty("gpr.key") ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
    
```    

Add dependency to your build.gradle for > Grails 4.x and < Grails 5.x:

```
repositories {
  ...
  maven { url "https://jitpack.io" }
}

dependencies {
    compile 'com.github.vsachinv:grails-localizations:4.0-M1'
}
```

Add dependency to your build.gradle for > Grails 3.2.x and < Grails 4.x:

```
repositories {
  ...
  // maven { url "http://dl.bintray.com/sachinverma/plugins" } remove this as Bintray repo Services down now.
//  maven { url "https://softclinic.jfrog.io/artifactory/grails-plugins-release" }
  maven { url "https://jitpack.io" }
}

dependencies {
    //compile 'org.grails.plugins:grails-localizations:0.1.3'
    compile 'com.github.vsachinv:grails-localizations:0.1.3'
}
```
Source Code for Grails 3.x: https://github.com/vsachinv/grails-localizations/tree/grails3-upgrade


Add dependency to your BuildConfig.groovy for Grails 2.x:

```
plugins {
        compile ":localizations:2.4"
}
```
Source Code for Grails 2.x:
https://github.com/vsachinv/grails-localizations/tree/Plugin_2.X

####Enhancement for > Grails 3.2.x

You can enable/disable the localization value from DB using following configurations in `application.groovy` or `application.yml`

```
grails.plugin.localizations.enabled = true
```

Importing
----------

There is also an import facility (at a URL similar to
http://myServer/myApp/localization/imports) to load subsequently created
property files - often the result of later installation of plugins. A 'message'
method is added to all domain classes and service classes as a convenience. An
'errorMessage' method is added to all domain classes that can set an error
message on either the domain as a whole or on a particular property of the
domain. 

CRUD
----

A localizations controller and CRUD screens are included with the plugin.
The screens assume you are using a layout called main. Your database must be
configured to allow the use of Unicode data for this plugin to work.

Caching
-------

Localizations are cached for speed. You can reset the cache by going to 
http://myServer/myApp/localization/cache

