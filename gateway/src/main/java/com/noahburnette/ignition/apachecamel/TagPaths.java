package com.noahburnette.ignition.apachecamel;

import com.inductiveautomation.ignition.common.tags.model.TagPath;
import com.inductiveautomation.ignition.common.tags.paths.parser.TagPathParser;

public final class TagPaths {
    private TagPaths() {
    }

    public static TagPath requireWritable(String value) {
        return requirePath(value, false);
    }

    /** Native Ignition 8.3.8 subscriptions accept glob paths; writes never do. */
    public static TagPath requireSubscribable(String value) {
        return requirePath(value, true);
    }

    private static TagPath requirePath(String value, boolean allowGlob) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("tag path must not be blank");
        }
        String path = value.trim();
        if (!path.startsWith("[") || path.indexOf(']') < 2) {
            throw new IllegalArgumentException(
                "tag path must include a provider, for example [default]Area/Setpoint"
            );
        }
        String provider = path.substring(1, path.indexOf(']'));
        if (provider.indexOf('*') >= 0 || provider.indexOf('?') >= 0) {
            throw new IllegalArgumentException("tag provider must be exact, not a wildcard");
        }
        if (!allowGlob && (path.indexOf('*') >= 0 || path.indexOf('?') >= 0)) {
            throw new IllegalArgumentException("tag writes do not support wildcards");
        }
        TagPath parsed;
        try {
            parsed = TagPathParser.parse(path);
        } catch (Exception error) {
            throw new IllegalArgumentException("invalid tag path: " + path, error);
        }
        if (parsed == null || parsed.getItemName() == null || parsed.getItemName().isBlank()) {
            throw new IllegalArgumentException("tag path must address a tag: " + path);
        }
        if (parsed.getProperty() != null) {
            throw new IllegalArgumentException(
                "tag path must address a tag, not a tag property: " + path
            );
        }
        return parsed;
    }
}
