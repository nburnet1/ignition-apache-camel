package com.noahburnette.ignition.apachecamel.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.inductiveautomation.ignition.gateway.datasource.Datasource;
import com.inductiveautomation.ignition.gateway.datasource.DatasourceManager;
import com.inductiveautomation.ignition.gateway.datasource.SRConnection;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.noahburnette.ignition.apachecamel.camel.CamelRouteResource;
import com.noahburnette.ignition.apachecamel.camel.CamelRouteRuntime;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;

class IgnitionDatabaseRepositoryTest {
    @Test void jdbcRouteUsesNativeConnectionWithoutSavedBindingsAndReleasesItsBorrow() throws Exception {
        var host = new Host();
        var resource = jdbcRoute("ignition.database/Main");
        var setup = new CamelContextSetup(host.gateway, resource, Map.of(), Map.of(), null);
        try (var runtime = new CamelRouteRuntime("native-database", resource, setup::install, null, setup)) {
            assertTrue(host.borrowed.isEmpty(), "Registry lookup and route startup must not open a database connection");
            assertEquals(List.of(Map.of("answer", 42)), runtime.request("direct:query", "select 42 as answer"));
            assertEquals(1, host.borrowed.size());
            assertTrue(host.borrowed.get(0).isClosed(), "JDBC must release the host-borrowed connection");
        }
        // Stopping the integration must not stop the host's connection manager.
        try (var connection = host.manager.getConnection("Main")) {
            assertFalse(connection.isClosed());
        }
    }

    @Test void savedDatabaseAliasesContinueToWork() throws Exception {
        var host = new Host();
        var resource = jdbcRoute("ignition.database.Reporting");
        var setup = new CamelContextSetup(host.gateway, resource, Map.of(),
            Map.of("ignition.database.Reporting", new CamelRegistryResource("DATABASE", null, null, "Main")), null);
        try (var runtime = new CamelRouteRuntime("existing-alias", resource, setup::install, null, setup)) {
            assertEquals(List.of(Map.of("answer", 7)), runtime.request("direct:query", "select 7 as answer"));
        }
        assertTrue(host.borrowed.get(0).isClosed());
    }

    @Test void nativeLookupsAreExplicitAndUseCurrentHostStateOnEveryBorrow() throws Exception {
        var host = new Host();
        var repository = new IgnitionDatabaseRepository(host.gateway);
        try (var context = new DefaultCamelContext()) {
            context.getRegistry(org.apache.camel.support.DefaultRegistry.class).addBeanRepository(repository);
            assertNull(context.getRegistry().lookupByName("Main"));
            assertNull(context.getRegistry().lookupByName("ignition.database/Missing"));
            assertNull(context.getRegistry().lookupByNameAndType("ignition.database/Main", String.class));
            assertTrue(context.getRegistry().findByType(DataSource.class).isEmpty());
            var source = context.getRegistry().lookupByNameAndType("ignition.database/Main", DataSource.class);
            assertNotNull(source);
            assertTrue(host.borrowed.isEmpty());
            host.available = false;
            assertThrows(SQLException.class, source::getConnection);
            assertNull(context.getRegistry().lookupByName("ignition.database/Main"));
            host.available = true;
            try (var connection = source.getConnection()) { assertFalse(connection.isClosed()); }
            assertEquals(1, host.borrowed.size());
        }
    }

    private static CamelRouteResource jdbcRoute(String reference) {
        return new CamelRouteResource("""
            - route:
                from:
                  uri: direct:query
                  steps:
                    - to: jdbc:%s
            """.formatted(reference));
    }

    /** Native SDK boundary fake; queries and connection closure use real SQLite JDBC. */
    private static final class Host {
        boolean available = true;
        final List<Connection> borrowed = new ArrayList<>();
        final Datasource metadata = (Datasource) Proxy.newProxyInstance(
            Datasource.class.getClassLoader(), new Class<?>[]{Datasource.class},
            (proxy, method, args) -> { throw new AssertionError("Lookup must not use datasource internals: " + method.getName()); });
        final DatasourceManager manager = (DatasourceManager) Proxy.newProxyInstance(
            DatasourceManager.class.getClassLoader(), new Class<?>[]{DatasourceManager.class}, (proxy, method, args) -> {
                if (method.getName().equals("getDatasource"))
                    return available && "Main".equals(args[0]) ? metadata : null;
                if (method.getName().equals("getConnection")) {
                    if (!available || !"Main".equals(args[0])) throw new SQLException("Native database unavailable");
                    Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
                    borrowed.add(connection);
                    return Proxy.newProxyInstance(SRConnection.class.getClassLoader(), new Class<?>[]{SRConnection.class},
                        (target, operation, values) -> {
                            try { return operation.invoke(connection, values); }
                            catch (InvocationTargetException failure) { throw failure.getCause(); }
                        });
                }
                throw new AssertionError("Unexpected host operation: " + method.getName());
            });
        final GatewayContext gateway = (GatewayContext) Proxy.newProxyInstance(
            GatewayContext.class.getClassLoader(), new Class<?>[]{GatewayContext.class}, (proxy, method, args) -> {
                if (method.getName().equals("getDatasourceManager")) return manager;
                throw new AssertionError("Unexpected Gateway operation: " + method.getName());
            });
    }
}
