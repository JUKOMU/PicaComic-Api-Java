package io.github.jukomu.picacomic.core.internal.util;

/**
 * Narrow string predicates used by the parser and request coordinator.
 */
public final class Strings {

    private Strings() {
    }

    public static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    public static String defaultIfBlank(String value, String defaultValue) {
        return isNotBlank(value) ? value : defaultValue;
    }
}
