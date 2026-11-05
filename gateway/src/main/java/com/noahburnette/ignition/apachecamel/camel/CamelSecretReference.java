package com.noahburnette.ignition.apachecamel.camel;

/** A provider/name pair, never plaintext or embedded credential material. */
public record CamelSecretReference(String providerName, String secretName) {
    public boolean isValid() {
        return providerName != null && !providerName.isBlank() && secretName != null && !secretName.isBlank();
    }
}
