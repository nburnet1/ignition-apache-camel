package com.noahburnette.ignition.apachecamel.gateway;

import static org.junit.jupiter.api.Assertions.*;
import com.inductiveautomation.ignition.common.script.ScriptFunction;
import com.inductiveautomation.ignition.common.tags.model.SecurityContext;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.python.core.Py;
import org.python.core.PyObject;

class GatewayCamelMessagingTest {
    @Test void passesSecurityContextToWorkerAndDoesNotLeakItIntoNextCall() throws Exception {
        var seen = new AtomicReference<SecurityContext>();
        try (var services = services(args -> {
            seen.set(SecurityContext.THREAD_LOCAL.get()); return args[2];
        })) {
            var previous = SecurityContext.THREAD_LOCAL.get();
            try {
                var security = SecurityContext.emptyContext();
                SecurityContext.THREAD_LOCAL.set(security);
                assertEquals(Map.of("number", 9007199254740993L), services.message("Plant", "echo", null,
                    Map.of("number", 9007199254740993L), true, 2));
                assertSame(security, seen.get()); assertSame(security, SecurityContext.THREAD_LOCAL.get());
                SecurityContext.THREAD_LOCAL.remove();
                services.message("Plant", "echo", null, Map.of(), true, 2);
                assertNull(seen.get());
            } finally {
                if (previous == null) SecurityContext.THREAD_LOCAL.remove(); else SecurityContext.THREAD_LOCAL.set(previous);
            }
        }
    }

    @Test void boundsLocalWaitEvenWhenNativeHandlerIgnoresInterruption() throws Exception {
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        try (var services = services(args -> {
            try {
                boolean done = false;
                while (!done) try { release.await(); done = true; } catch (InterruptedException ignored) { }
                return Py.None;
            } finally { exited.countDown(); }
        })) {
            long started = System.nanoTime();
            assertThrows(TimeoutException.class, () -> services.message("Plant", "slow", null, Map.of(), true, 1));
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2));
            assertEquals(1, exited.getCount(), "timeout must not pretend the handler stopped");
            release.countDown(); assertTrue(exited.await(3, TimeUnit.SECONDS));
        } finally { release.countDown(); }
    }

    @Test void enforcesJsonSizeBeforeInvokingTheNativeFacade() throws Exception {
        try (var services = services(args -> { fail("Invalid payload reached handler"); return Py.None; })) {
            assertThrows(IllegalArgumentException.class, () -> services.message("Plant", "echo", null, "scalar", true, 1));
            assertThrows(IllegalArgumentException.class, () -> services.message("Plant", "echo", null,
                Map.of("text", "x".repeat(65536)), true, 1));
        }
    }

    @Test void admitsSixteenOperationsWithoutQueueingAndRejectsAfterShutdown() throws Exception {
        var entered = new CountDownLatch(16);
        var release = new CountDownLatch(1);
        var callers = java.util.concurrent.Executors.newFixedThreadPool(16);
        try (var services = services(args -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            return Py.None;
        })) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<Object>>();
            for (int i = 0; i < 16; i++) tasks.add(callers.submit(() -> services.message("Plant", "echo", null, Map.of(), true, 10)));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> services.message("Plant", "echo", null, Map.of(), true, 1));
            release.countDown();
            for (var task : tasks) assertNull(task.get(5, TimeUnit.SECONDS));
            services.close();
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> services.message("Plant", "echo", null, Map.of(), true, 1));
        } finally { release.countDown(); callers.shutdownNow(); }
    }

    @Test void rejectsOversizedRepliesAndMissingPerspectiveClearly() throws Exception {
        try (var services = services(args -> Py.newString("x".repeat(65536)))) {
            assertThrows(IllegalArgumentException.class, () -> services.message("Plant", "echo", null, Map.of(), true, 2));
            var missing = assertThrows(IllegalStateException.class, () -> services.perspectiveMessage("update",
                "6afc68d3-2f1e-4dd2-8868-f32d60c913dd", "main", "page", Map.of()));
            assertTrue(missing.getMessage().contains("Perspective"));
        }
    }

    private static GatewayCamelServices services(Function<PyObject[], PyObject> invoke) throws Exception {
        var context = (com.inductiveautomation.ignition.gateway.model.GatewayContext) java.lang.reflect.Proxy.newProxyInstance(
            GatewayCamelMessagingTest.class.getClassLoader(),
            new Class<?>[]{com.inductiveautomation.ignition.gateway.model.GatewayContext.class},
            (proxy, method, args) -> method.getName().equals("getModuleManager")
                ? java.lang.reflect.Proxy.newProxyInstance(GatewayCamelMessagingTest.class.getClassLoader(),
                    new Class<?>[]{com.inductiveautomation.ignition.gateway.model.ModuleManager.class},
                    (manager, member, parameters) -> null) : null);
        var services = new GatewayCamelServices(context);
        var function = new ScriptFunction() {
            public PyObject invoke(PyObject... args) { return invoke.apply(args); }
            public PyObject invoke(PyObject[] args, String[] keywords) { return invoke.apply(args); }
        };
        var field = GatewayCamelServices.class.getDeclaredField("messageFunction");
        field.setAccessible(true); field.set(services, function);
        return services;
    }
}
