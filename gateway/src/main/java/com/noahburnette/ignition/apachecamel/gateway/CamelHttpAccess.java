package com.noahburnette.ignition.apachecamel.gateway;

import com.inductiveautomation.ignition.common.auth.permissions.PermissionsConfig;
import com.inductiveautomation.ignition.common.auth.security.level.SecurityLevelConfig;
import com.inductiveautomation.ignition.gateway.auth.apitoken.ApiTokenManager;
import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.PermissionType;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteAccess;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteMounterContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteMounterValidationException;
import com.inductiveautomation.ignition.gateway.web.session.WebUiSession;
import com.noahburnette.ignition.apachecamel.camel.CamelRouteResource;
import java.util.Set;
import java.util.function.Function;

/** Native authentication/CSRF, with invocation permissions independent of Gateway configuration. */
final class CamelHttpAccess {
    private CamelHttpAccess() { }

    static Set<PermissionsConfig> permissions(CamelRouteResource resource) {
        // An empty SET is deny-all in AbstractAccessControlStrategy. Do not use an
        // empty AllOf PermissionsConfig: that native expression allows everyone.
        if (resource == null || !resource.validationErrors().isEmpty() || resource.httpSecurityLevels().isEmpty()) {
            return Set.of();
        }
        String[][] paths = resource.httpSecurityLevels().stream().map(path -> path.split("/"))
            .toArray(String[][]::new);
        return Set.of(PermissionsConfig.of(PermissionsConfig.Type.AnyOf, SecurityLevelConfig.fromPaths(paths)));
    }

    static AccessControlStrategy strategy(Function<RequestContext, CamelRouteResource> resource) {
        var token = new ApiTokenManager.TokenAccessControl(PermissionType.ACCESS) {
            @Override
            protected Set<PermissionsConfig> getRequiredPermissions(RequestContext request) {
                return permissions(resource.apply(request));
            }
        };
        var browser = new WebUiSession.CsrfWebUiSessionAccessControl(PermissionType.ACCESS) {
            @Override
            protected Set<PermissionsConfig> getRequiredPermissions(RequestContext request) {
                return permissions(resource.apply(request));
            }
        };
        return either(token, browser);
    }

    static AccessControlStrategy either(AccessControlStrategy token, AccessControlStrategy browser) {
        // SDK AccessControlStrategy.or collapses every denial into 403 and does
        // not delegate validate. Preserve 401 and native CSRF mount validation.
        return new AccessControlStrategy() {
            @Override
            public RouteAccess canAccess(RequestContext request) {
                RouteAccess keyAccess = token.canAccess(request);
                if (keyAccess == RouteAccess.GRANTED) return keyAccess;
                RouteAccess sessionAccess = browser.canAccess(request);
                if (sessionAccess == RouteAccess.GRANTED) return sessionAccess;
                return keyAccess == RouteAccess.UNAUTHORIZED && sessionAccess == RouteAccess.UNAUTHORIZED
                    ? RouteAccess.UNAUTHORIZED : RouteAccess.FORBIDDEN;
            }

            @Override
            public void validate(RouteMounterContext mount) throws RouteMounterValidationException {
                token.validate(mount);
                browser.validate(mount);
            }
        };
    }
}
