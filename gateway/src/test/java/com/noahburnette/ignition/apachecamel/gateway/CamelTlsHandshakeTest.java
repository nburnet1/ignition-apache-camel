package com.noahburnette.ignition.apachecamel.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.noahburnette.ignition.apachecamel.camel.CamelRouteResource;
import com.noahburnette.ignition.apachecamel.camel.CamelRouteRuntime;
import com.noahburnette.ignition.apachecamel.camel.CamelSecretReference;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.apache.camel.support.jsse.SSLContextParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CamelTlsHandshakeTest {
    @TempDir Path directory;

    @Test void realMutualTlsRequiresTrustedCertificateAndValidHostname() throws Exception {
        Path keys = directory.resolve("keys.p12");
        var generation = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-keystore", keys.toString(),
            "-storetype", "PKCS12", "-storepass", "test-password", "-noprompt")
            .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
        assertTrue(generation.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, generation.exitValue());
        var store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keys)) { store.load(input, "test-password".toCharArray()); }
        var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(store, "test-password".toCharArray());
        var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(store);
        var serverContext = SSLContext.getInstance("TLS");
        serverContext.init(km.getKeyManagers(), tm.getTrustManagers(), null);
        var server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext) {
            @Override public void configure(HttpsParameters parameters) {
                var ssl = serverContext.getDefaultSSLParameters();
                ssl.setNeedClientAuth(true);
                parameters.setSSLParameters(ssl);
            }
        });
        server.createContext("/proof", exchange -> {
            byte[] body = "mutual-tls-ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
            exchange.close();
        });
        server.start();
        try {
            var contents = new CamelSecretReference("test", "store");
            var password = new CamelSecretReference("test", "password");
            var secrets = Map.of(contents, Base64.getEncoder().encodeToString(Files.readAllBytes(keys)), password, "test-password");
            var config = new CamelRegistryResource.Tls(null, null, contents, password, contents, password, null);
            assertEquals("mutual-tls-ok", request(server.getAddress().getPort(), "localhost", CamelTls.create(config, secrets)));
            assertThrows(Exception.class, () -> request(server.getAddress().getPort(), "127.0.0.1", CamelTls.create(config, secrets)));
            var untrusted = new CamelRegistryResource.Tls(null, null, null, null, contents, password, null);
            assertThrows(Exception.class, () -> request(server.getAddress().getPort(), "localhost", CamelTls.create(untrusted, secrets)));
            var noClientKeys = new CamelRegistryResource.Tls(null, null, contents, password, null, null, null);
            assertThrows(Exception.class, () -> request(server.getAddress().getPort(), "localhost", CamelTls.create(noClientKeys, secrets)));
        } finally { server.stop(0); }
    }

    private static Object request(int port, String hostname, SSLContextParameters tls) throws Exception {
        var resource = new CamelRouteResource("""
            - route:
                from:
                  uri: direct:test
                  steps:
                    - to: "secure-orders://%s:%d/proof?connectTimeout=2000&responseTimeout=2000"
                    - convertBodyTo:
                        type: java.lang.String
            """.formatted(hostname, port));
        try (var runtime = new CamelRouteRuntime("tls-handshake", resource, context -> {
            var http = new com.noahburnette.ignition.apachecamel.gateway.optional.NamedHttpComponent("https");
            http.setSslContextParameters(tls);
            context.addComponent("secure-orders", http);
        })) {
            return runtime.request("direct:test", null);
        }
    }
}
