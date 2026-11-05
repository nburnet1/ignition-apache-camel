package com.noahburnette.ignition.apachecamel.camel;

import java.util.HashMap;
import java.util.Map;

/** Gateway-local exact consumer ownership. Never calls a resource manager while locked. */
public final class CamelHttpPathRegistry {
    private record Claim(String resource, Object consumer) { }
    private final Map<String, Claim> claims = new HashMap<>();

    public synchronized void register(String resource, String path, Object consumer) {
        if (claims.containsKey(path)) throw new DuplicatePathException(path);
        claims.put(path, new Claim(resource, consumer));
    }

    public synchronized void unregister(String path, Object consumer) {
        var claim = claims.get(path);
        if (claim != null && claim.consumer() == consumer) claims.remove(path);
    }

    public synchronized String owner(String path) {
        var claim = claims.get(path);
        return claim == null ? null : claim.resource();
    }

    public static final class DuplicatePathException extends IllegalArgumentException {
        private final String path;
        DuplicatePathException(String path) { super("HTTP path is already registered: " + path); this.path = path; }
        public String path() { return path; }
    }
}
