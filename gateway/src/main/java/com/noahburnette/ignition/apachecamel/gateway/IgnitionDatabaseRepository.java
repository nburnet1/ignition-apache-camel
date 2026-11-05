package com.noahburnette.ignition.apachecamel.gateway;

import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.apache.camel.spi.BeanRepository;

/** Explicit native database references; lookup never borrows a connection or owns a pool. */
final class IgnitionDatabaseRepository implements BeanRepository {
    // '/' cannot occur in a saved Camel registry resource name.
    static final String PREFIX = "ignition.database/";
    private final GatewayContext gateway;

    IgnitionDatabaseRepository(GatewayContext gateway) { this.gateway = gateway; }

    static String databaseName(String reference) {
        if (reference == null || !reference.startsWith(PREFIX)) return null;
        String name = reference.substring(PREFIX.length());
        return name.isBlank() ? null : name;
    }

    @Override public Object lookupByName(String name) {
        return lookupByNameAndType(name, DataSource.class);
    }

    @Override public <T> T lookupByNameAndType(String name, Class<T> type) {
        String database = databaseName(name);
        if (database == null || !type.isAssignableFrom(DataSource.class)
            || gateway.getDatasourceManager().getDatasource(database) == null) return null;
        return type.cast(CamelContextSetup.datasource(gateway, database));
    }

    // Never auto-wire an arbitrary native database when a component omits its selection.
    @Override public <T> Map<String, T> findByTypeWithName(Class<T> type) { return Map.of(); }
    @Override public <T> Set<T> findByType(Class<T> type) { return Set.of(); }
}
