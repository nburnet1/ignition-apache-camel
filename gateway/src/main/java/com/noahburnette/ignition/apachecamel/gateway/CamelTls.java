package com.noahburnette.ignition.apachecamel.gateway;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import com.noahburnette.ignition.apachecamel.camel.CamelSecretReference;
import org.apache.camel.support.jsse.KeyManagersParameters;
import org.apache.camel.support.jsse.KeyStoreParameters;
import org.apache.camel.support.jsse.SSLContextParameters;
import org.apache.camel.support.jsse.TrustManagersParameters;

/** TLS material stays in memory. No trust-all manager, disabled hostname checks, or staged key files. */
final class CamelTls {
    private CamelTls() { }

    static SSLContextParameters create(CamelRegistryResource.Tls config, Map<CamelSecretReference, String> secrets) throws Exception {
        var parameters = new SSLContextParameters();
        parameters.setSecureSocketProtocol(config.protocol());
        if (config.trustStore() != null) {
            var trust = new TrustManagersParameters();
            trust.setKeyStore(store(config.storeType(), secrets.get(config.trustStore()), secrets.get(config.trustStorePassword())));
            parameters.setTrustManagers(trust);
        }
        if (config.keyStore() != null) {
            var keys = new KeyManagersParameters();
            keys.setKeyStore(store(config.storeType(), secrets.get(config.keyStore()), secrets.get(config.keyStorePassword())));
            keys.setKeyPassword(secrets.get(config.privateKeyPassword() == null ? config.keyStorePassword() : config.privateKeyPassword()));
            parameters.setKeyManagers(keys);
        }
        return parameters;
    }

    private static KeyStoreParameters store(String type, String encoded, String password) throws Exception {
        byte[] bytes = Base64.getMimeDecoder().decode(encoded);
        char[] characters = password.toCharArray();
        try {
            var store = KeyStore.getInstance(type);
            store.load(new ByteArrayInputStream(bytes), characters);
            var parameters = new KeyStoreParameters();
            parameters.setKeyStore(store);
            return parameters;
        } finally {
            Arrays.fill(bytes, (byte) 0);
            Arrays.fill(characters, '\0');
        }
    }
}
