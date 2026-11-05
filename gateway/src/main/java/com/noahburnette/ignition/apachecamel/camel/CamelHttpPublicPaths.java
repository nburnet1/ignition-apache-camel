package com.noahburnette.ignition.apachecamel.camel;

/** Exact, unencoded public paths; never normalize ambiguous paths into another mapping. */
public final class CamelHttpPublicPaths {
    public static final String PREFIX = "/system/integrations";
    private CamelHttpPublicPaths() { }

    public static boolean validPublicPath(String path) {
        return path != null && path.length() <= 512
            && path.matches("/[A-Za-z0-9][A-Za-z0-9._-]{0,63}(/[A-Za-z0-9][A-Za-z0-9._-]{0,63}){0,15}");
    }

}
