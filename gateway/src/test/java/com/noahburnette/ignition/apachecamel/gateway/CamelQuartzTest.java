package com.noahburnette.ignition.apachecamel.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.noahburnette.ignition.apachecamel.camel.CamelRouteResource;
import com.noahburnette.ignition.apachecamel.camel.CamelRouteRuntime;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.camel.Processor;
import org.apache.camel.component.quartz.QuartzComponent;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.quartz.simpl.RAMJobStore;

class CamelQuartzTest {
    private static final CamelRouteResource ROUTE = new CamelRouteResource("""
        - route:
            id: memory-clock
            from:
              uri: clock:proof/tick?trigger.repeatInterval=100&trigger.repeatCount=0
              steps:
                - process:
                    ref: observe
        """);

    @Test void configurationHasOnlyThreadCountAndValidatesBoundsWithoutDatabaseAccess() {
        assertEquals(2, new CamelComponentResource.Quartz(null).threads());
        assertEquals(java.util.List.of("threads"), Arrays.stream(CamelComponentResource.Quartz.class.getRecordComponents()).map(component -> component.getName()).toList());
        for (int threads : new int[]{1, 2, 16}) assertTrue(component(threads).validationErrors().isEmpty());
        for (int threads : new int[]{0, 17}) assertTrue(component(threads).validationErrors().containsKey("quartz.threads"));
    }

    @Test void namedQuartzFiresWithoutAGatewayAndRecreatesItsScheduleAfterReplacement() throws Exception {
        Scheduler previous = null;
        for (int generation = 0; generation < 2; generation++) {
            var ticks = new LinkedBlockingQueue<String>();
            var scheduler = new AtomicReference<Scheduler>();
            try (var runtime = start("memory-quartz-replacement", ticks, scheduler)) {
                assertEquals("tick", ticks.poll(5, TimeUnit.SECONDS));
                var metadata = scheduler.get().getMetaData();
                assertEquals(RAMJobStore.class, metadata.getJobStoreClass());
                assertFalse(metadata.isJobStoreSupportsPersistence());
                assertEquals(2, metadata.getThreadPoolSize());
                assertNotSame(previous, scheduler.get());
            }
            assertTrue(scheduler.get().isShutdown());
            previous = scheduler.get();
        }
    }

    @Test void separateRouteContextsOwnSeparateSchedulersForTheSameComponentAndTriggerNames() throws Exception {
        var first = new AtomicReference<Scheduler>();
        var second = new AtomicReference<Scheduler>();
        var firstTicks = new LinkedBlockingQueue<String>();
        var secondTicks = new LinkedBlockingQueue<String>();
        try (var a = start("memory-clock-a", firstTicks, first); var b = start("memory-clock-b", secondTicks, second)) {
            assertEquals("tick", firstTicks.poll(5, TimeUnit.SECONDS));
            assertEquals("tick", secondTicks.poll(5, TimeUnit.SECONDS));
            assertNotSame(first.get(), second.get());
            assertNotEquals(first.get().getSchedulerName(), second.get().getSchedulerName());
        }
        assertTrue(first.get().isShutdown());
        assertTrue(second.get().isShutdown());
    }

    private static CamelComponentResource component(int threads) {
        return new CamelComponentResource("QUARTZ", null, null, new CamelComponentResource.Quartz(threads));
    }

    private static CamelRouteRuntime start(String name, LinkedBlockingQueue<String> ticks, AtomicReference<Scheduler> scheduler) throws Exception {
        // A null Gateway proves this path cannot query a datasource or provision a schema.
        var setup = new CamelContextSetup(null, ROUTE, Map.of("clock", component(2)), Map.of(), null);
        return new CamelRouteRuntime(name, ROUTE, context -> {
            setup.install(context);
            context.getRegistry().bind("observe", (Processor) exchange -> ticks.add("tick"));
            try {
                context.addStartupListener((started, alreadyStarted) -> scheduler.set(started.getComponent("clock", QuartzComponent.class).getScheduler()));
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }, null, setup);
    }
}
