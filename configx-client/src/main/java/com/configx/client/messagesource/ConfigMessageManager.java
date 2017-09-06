package com.configx.client.messagesource;

import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 配置消息管理器
 * <p>
 * Created by zouzhirong on 2017/3/13.
 */
public class ConfigMessageManager implements EnvironmentAware {

    public static String SPRING_MESSAGES_BASENAME = "spring.messages.basename";

    private static final String PROPERTIES_SUFFIX = ".properties";

    private static final String XML_SUFFIX = ".xml";

    private Environment environment;

    private String[] basenames = new String[0];

    /**
     * Cache all filenames
     */
    private final ConcurrentHashMap<String, Object> filenameSet = new ConcurrentHashMap<>();

    /**
     * Cache to hold filename lists per Locale
     */
    private final ConcurrentMap<String, Map<Locale, List<String>>> cachedFilenames =
            new ConcurrentHashMap<>();

    /**
     * Cache to hold already loaded properties per filename
     */
    private final ConcurrentMap<String, PropertiesHolder> cachedProperties =
            new ConcurrentHashMap<>();


    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    /**
     * Set basename
     *
     * @param basename
     */
    public void setBasename(String basename) {
        if (basename != null) {
            String[] basenames = StringUtils.commaDelimitedListToStringArray(basename);
            setBasenames(basenames);
        } else {
            String[] basenames = null;
            setBasenames(basenames);
        }
    }

    /**
     * Set an array of basenames
     *
     * @param basenames
     */
    public void setBasenames(String... basenames) {
        if (basenames != null) {
            this.basenames = new String[basenames.length];
            for (int i = 0; i < basenames.length; i++) {
                String basename = basenames[i];
                Assert.hasText(basename, "Basename must not be empty");
                this.basenames[i] = basename.trim();
            }
        } else {
            this.basenames = new String[0];
        }

        calculateAllFilenames();
    }


    /**
     * Get basenames
     *
     * @return
     */
    public String[] getBasenames() {
        if (ObjectUtils.isEmpty(this.basenames)) {
            String basename = environment.getProperty("spring.messages.basename", "messages");
            return new String[]{basename};
        } else {
            return this.basenames;
        }
    }

    /**
     * Calculate all basenames filenames
     */
    private void calculateAllFilenames() {
        String[] basenames = getBasenames();
        for (String basename : basenames) {
            for (Locale locale : Locale.getAvailableLocales()) {
                List<String> filenames = calculateAllFilenames(basename, locale);
                for (String filename : filenames) {
                    this.filenameSet.put(filename, new Object());
                }
            }
        }
    }

    /**
     * 判断文件名是否是国际化文件
     *
     * @param fullFilename
     * @return
     */
    public boolean isLocalFile(String fullFilename) {
        if (!fullFilename.endsWith(PROPERTIES_SUFFIX) && !fullFilename.endsWith(XML_SUFFIX)) {
            return false;
        }

        // 去掉文件后缀.xml或.properties
        String filename = fullFilename;
        int indexOfSuffix = fullFilename.lastIndexOf(".");
        if (indexOfSuffix >= 0) {
            filename = fullFilename.substring(0, indexOfSuffix);
        }

        return filenameSet.containsKey(filename);
    }

    protected String resolveCodeWithoutArguments(String code, Locale locale) {
        String[] basenames = getBasenames();
        for (String basename : basenames) {
            List<String> filenames = calculateAllFilenames(basename, locale);
            for (String filename : filenames) {
                PropertiesHolder propHolder = getProperties(filename);
                if (propHolder != null) {
                    String result = propHolder.getProperty(code);
                    if (result != null) {
                        return result;
                    }
                }
            }
        }

        return null;
    }

    protected MessageFormat resolveCode(String code, Locale locale) {
        String[] basenames = getBasenames();
        for (String basename : basenames) {
            List<String> filenames = calculateAllFilenames(basename, locale);
            for (String filename : filenames) {
                PropertiesHolder propHolder = getProperties(filename);
                if (propHolder != null) {
                    MessageFormat result = propHolder.getMessageFormat(code, locale);
                    if (result != null) {
                        return result;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Refresh Properties
     *
     * @param fullFilename
     * @param value
     */
    public void refreshProperties(String fullFilename, String value) {
        PropertiesHolder propHolder = new PropertiesHolder(fullFilename, value);

        // 去掉文件后缀.xml或.properties
        String filename = fullFilename;
        int indexOfSuffix = fullFilename.lastIndexOf(".");
        if (indexOfSuffix >= 0) {
            filename = fullFilename.substring(0, indexOfSuffix);
        }
        cachedProperties.put(filename, propHolder);
    }

    /**
     * Get a PropertiesHolder for the given filename, either from the
     * cache or freshly loaded.
     *
     * @param filename the bundle filename (basename + Locale)
     * @return the current PropertiesHolder for the bundle
     */
    protected PropertiesHolder getProperties(String filename) {
        return cachedProperties.get(filename);
    }

    /**
     * Calculate all filenames for the given bundle basename and Locale.
     * Will calculate filenames for the given Locale, the system Locale
     * (if applicable), and the default file.
     *
     * @param basename the basename of the bundle
     * @param locale   the locale
     * @return the List of filenames to check
     * @see #calculateFilenamesForLocale
     */
    protected List<String> calculateAllFilenames(String basename, Locale locale) {
        Map<Locale, List<String>> localeMap = this.cachedFilenames.get(basename);
        if (localeMap != null) {
            List<String> filenames = localeMap.get(locale);
            if (filenames != null) {
                return filenames;
            }
        }
        List<String> filenames = new ArrayList<String>(7);
        filenames.addAll(calculateFilenamesForLocale(basename, locale));
        filenames.addAll(calculateFilenamesForLanguageTag(basename, locale));
        filenames.add(basename);
        if (localeMap == null) {
            localeMap = new ConcurrentHashMap<Locale, List<String>>();
            Map<Locale, List<String>> existing = this.cachedFilenames.putIfAbsent(basename, localeMap);
            if (existing != null) {
                localeMap = existing;
            }
        }
        localeMap.put(locale, filenames);

        return filenames;
    }


    /**
     * Calculate the filenames for the given bundle basename and Locale,
     * appending language code, country code, and variant code.
     * E.g.: basename "messages", Locale "de_AT_oo" -&gt; "messages_de_AT_OO",
     * "messages_de_AT", "messages_de".
     * <p>Follows the rules defined by {@link java.util.Locale#toString()}.
     *
     * @param basename the basename of the bundle
     * @param locale   the locale
     * @return the List of filenames to check
     */
    protected List<String> calculateFilenamesForLocale(String basename, Locale locale) {
        List<String> result = new ArrayList<String>(3);
        String language = locale.getLanguage();
        String country = locale.getCountry();
        String variant = locale.getVariant();
        StringBuilder temp = new StringBuilder(basename);

        temp.append('_');
        if (language.length() > 0) {
            temp.append(language);
            result.add(0, temp.toString());
        }

        temp.append('_');
        if (country.length() > 0) {
            temp.append(country);
            result.add(0, temp.toString());
        }

        if (variant.length() > 0 && (language.length() > 0 || country.length() > 0)) {
            temp.append('_').append(variant);
            result.add(0, temp.toString());
        }

        return result;
    }

    protected List<String> calculateFilenamesForLanguageTag(String basename, Locale locale) {
        List<String> result = new ArrayList<String>(3);
        String language = locale.getLanguage();
        String script = locale.getScript();
        String country = locale.getCountry();

        StringBuilder temp = new StringBuilder(basename);

        temp.append('-');
        if (language.length() > 0) {
            temp.append(language);
            result.add(0, temp.toString());
        }

        temp.append('-');
        if (script.length() > 0) {
            temp.append(script);
            result.add(0, temp.toString());
        }

        if (country.length() > 0 && (language.length() > 0 || script.length() > 0)) {
            temp.append('-').append(country);
            result.add(0, temp.toString());
        }

        return result;
    }

}
