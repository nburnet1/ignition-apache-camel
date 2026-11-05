package com.noahburnette.ignition.apachecamel.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.inductiveautomation.ignition.common.auth.security.level.SecurityLevelConfig;
import com.inductiveautomation.ignition.common.gson.JsonParser;
import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.noahburnette.ignition.apachecamel.camel.CamelRouteResource;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteAccess;
import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteMounterContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CamelHttpAccessTest {
    private static final String YAML = "- route:\n    from:\n      uri: direct:test\n      steps:\n        - to: log:test\n";

    private static CamelRouteResource resource(List<String> levels) {
        return new CamelRouteResource("YAML", YAML, null, null, null, null, null, null, levels);
    }

    @Test
    void authenticationCompositionPreservesUnauthorizedVersusForbidden() {
        for (RouteAccess token : RouteAccess.values()) {
            for (RouteAccess browser : RouteAccess.values()) {
                RouteAccess expected = token == RouteAccess.GRANTED || browser == RouteAccess.GRANTED ? RouteAccess.GRANTED
                    : token == RouteAccess.UNAUTHORIZED && browser == RouteAccess.UNAUTHORIZED ? RouteAccess.UNAUTHORIZED
                    : RouteAccess.FORBIDDEN;
                assertEquals(expected, CamelHttpAccess.either(ignored -> token, ignored -> browser).canAccess(null));
            }
        }
    }

    @Test
    void compositionRetainsNativeMountValidation() throws Exception {
        var validations = new java.util.concurrent.atomic.AtomicInteger();
        var strategy = new AccessControlStrategy() {
            @Override public RouteAccess canAccess(RequestContext request) { return RouteAccess.FORBIDDEN; }
            @Override public void validate(RouteMounterContext mount) { validations.incrementAndGet(); }
        };
        CamelHttpAccess.either(strategy, strategy).validate(null);
        assertEquals(2, validations.get());
    }

    @Test
    void absentAndEmptyPoliciesDenyEvenAdministrators() {
        assertTrue(CamelHttpAccess.permissions(null).isEmpty());
        assertTrue(CamelHttpAccess.permissions(new CamelRouteResource(YAML)).isEmpty());
        assertTrue(CamelHttpAccess.permissions(resource(List.of())).isEmpty());
    }

    @Test
    void usesNativeAnyOfAndHierarchyWithoutGatewayPermissionFallback() {
        var permissions = CamelHttpAccess.permissions(resource(List.of(
            "Authenticated/Integrations/OrdersSubmit", "Authenticated/Roles/Administrator")));
        assertEquals(1, permissions.size());
        var policy = permissions.iterator().next();
        assertTrue(policy.isAuthorized(SecurityLevelConfig.fromPaths(new String[]{"Authenticated", "Integrations", "OrdersSubmit"})));
        assertTrue(policy.isAuthorized(SecurityLevelConfig.fromPaths(new String[]{"Authenticated", "Roles", "Administrator"})));
        assertFalse(policy.isAuthorized(SecurityLevelConfig.fromPaths(new String[]{"Authenticated", "Integrations", "Inventory"})));
        assertFalse(policy.isAuthorized(SecurityLevelConfig.fromPaths(new String[]{"Authenticated"})));
        assertFalse(policy.isAuthorized(SecurityLevelConfig.fromPaths(new String[]{"Authenticated", "Integrations"})));
    }

    @Test
    void invalidPoliciesFailClosedAndReportTheirField() {
        for (var levels : List.of(List.of(""), List.of("Authenticated"), List.of("Public/Orders"),
            List.of("Authenticated/"), List.of("Authenticated//Orders"), List.of("Authenticated/ Orders"),
            List.of("Authenticated/Orders\n"), List.of("Authenticated/Orders", "Authenticated/Orders"),
            Arrays.asList((String) null))) {
            var config = resource(levels);
            assertTrue(config.validationErrors().containsKey("httpSecurityLevels"), levels.toString());
            assertTrue(CamelHttpAccess.permissions(config).isEmpty());
        }
    }

    @Test
    void policyListsAreDefensiveAndBounded() {
        var levels = new ArrayList<>(List.of("Authenticated/Orders"));
        var config = resource(levels);
        levels.clear();
        assertEquals(List.of("Authenticated/Orders"), config.httpSecurityLevels());
        assertThrows(UnsupportedOperationException.class, () -> config.httpSecurityLevels().clear());
        var tooMany = java.util.stream.IntStream.range(0, 33).mapToObj(i -> "Authenticated/Level" + i).toList();
        assertTrue(resource(tooMany).validationErrors().containsKey("httpSecurityLevels"));
    }

    @Test
    void missingGrantsDenyAndPrimaryAndBackupPoliciesRoundTrip() throws Exception {
        var codec = new CamelRouteCodec();
        var builder = Resource.newBuilder().setResourceCollectionName("core")
            .setResourcePath(new ResourcePath(new ResourceType("test", "camel-route"), "test"))
            .putData("config.json", "{}")
            .putData("config.camel.yaml", YAML);
        assertTrue(codec.decode(builder.build()).config().httpSecurityLevels().isEmpty());
        var primary = resource(List.of("Authenticated/Orders"));
        var backup = resource(List.of("Authenticated/Inventory"));
        codec.encode(primary, builder);
        builder.putData("backupConfig.json", codec.encodeToJson(backup).toString());
        CamelRouteCodec.splitYaml(builder);
        var decoded = codec.decode(builder.build());
        assertEquals(primary, decoded.config());
        assertEquals(backup, decoded.backupConfig().orElseThrow());
        var invalid = JsonParser.parseString(codec.encodeToJson(primary).toString()).getAsJsonObject();
        invalid.add("httpSecurityLevels", JsonParser.parseString("[\"Public/Orders\"]"));
        builder.putData("config.json", invalid.toString());
        assertThrows(Exception.class, () -> codec.decode(builder.build()));
    }
}
