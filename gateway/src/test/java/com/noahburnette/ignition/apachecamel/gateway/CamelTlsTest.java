package com.noahburnette.ignition.apachecamel.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.noahburnette.ignition.apachecamel.camel.CamelSecretReference;
import java.io.ByteArrayOutputStream;
import java.security.KeyStore;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CamelTlsTest {

    @Test void tlsLoadsPasswordProtectedTrustMaterialInMemory() throws Exception {
        var store = KeyStore.getInstance("PKCS12");
        store.load(null, "test-password".toCharArray());
        var output = new ByteArrayOutputStream();
        store.store(output, "test-password".toCharArray());
        var contents = new CamelSecretReference("test", "store");
        var password = new CamelSecretReference("test", "password");
        var config = new CamelRegistryResource.Tls(null, null, contents, password, null, null, null);
        var secrets = Map.of(contents, Base64.getEncoder().encodeToString(output.toByteArray()), password, "test-password");
        var tls = CamelTls.create(config, secrets);
        assertEquals(0, tls.getTrustManagers().getKeyStore().createKeyStore().size());
        assertThrows(Exception.class, () -> CamelTls.create(config, Map.of(contents, secrets.get(contents), password, "wrong")));
        assertNull(tls.getKeyManagers());
    }

}
