package org.grails.plugins.localization;

import groovy.transform.CompileStatic;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.support.AbstractMessageSource;
import org.springframework.core.io.ResourceLoader;

import java.text.MessageFormat;
import java.util.Locale;

/**
 * Spring {@code MessageSource} implementation that resolves messages from the database-backed
 * {@link Localization} domain class instead of {@code .properties} files.
 *
 * <p>Registered as the {@code messageSource} bean by {@code GrailsLocalizationsGrailsPlugin}
 * when {@code grails.plugin.localizations.enabled = true}. Delegates all lookups to
 * {@link Localization#decodeMessage(String, java.util.Locale)}, which checks the in-memory
 * LRU cache before hitting the database.</p>
 */
@CompileStatic
public class LocalizationMessageSource extends AbstractMessageSource implements ResourceLoaderAware {

    private ResourceLoader resourceLoader = null;

    @Override
    protected MessageFormat resolveCode(String code, Locale locale) {
        String msg = Localization.decodeMessage(code, locale);
        return (msg != null) ? new MessageFormat(msg) : null;
    }

    @Override
    protected String resolveCodeWithoutArguments(String code, Locale locale) {
        return Localization.decodeMessage(code, locale);
    }

    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }
}
