package com.noahburnette.ignition.apachecamel.camel;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;
import javax.management.InstanceNotFoundException;
import javax.management.JMException;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import org.jolokia.server.core.util.jmx.MBeanServerAccess;

/** Local connections supplied by the owner, already filtered before Jolokia can use them. */
final class LocalMBeanServerAccess implements MBeanServerAccess {
    private final Supplier<Set<MBeanServerConnection>> connections;

    LocalMBeanServerAccess(Supplier<Set<MBeanServerConnection>> connections) { this.connections = connections; }

    @Override public Set<MBeanServerConnection> getMBeanServers() { return connections.get(); }

    @Override public void each(ObjectName pattern, MBeanEachCallback callback) throws IOException, JMException {
        for (var connection : getMBeanServers()) {
            for (var instance : connection.queryMBeans(pattern, null)) {
                try { callback.callback(connection, instance); }
                catch (InstanceNotFoundException removed) {
                    if (pattern != null && !pattern.isPattern()) throw removed;
                }
            }
        }
    }

    @Override public <R> R call(ObjectName name, MBeanAction<R> action, Object... arguments) throws IOException, JMException {
        for (var connection : getMBeanServers()) {
            if (connection.isRegistered(name)) return action.execute(connection, name, arguments);
        }
        throw new InstanceNotFoundException("Local MBean not found");
    }

    @Override public Set<ObjectName> queryNames(ObjectName pattern) throws IOException {
        Set<ObjectName> result = new LinkedHashSet<>();
        for (var connection : getMBeanServers()) result.addAll(connection.queryNames(pattern, null));
        return result;
    }

    // No listeners on Gateway-owned servers, and no stale metadata after another module registers/unregisters.
    @Override public boolean hasMBeansListChangedSince(long timestamp) { return true; }
}
