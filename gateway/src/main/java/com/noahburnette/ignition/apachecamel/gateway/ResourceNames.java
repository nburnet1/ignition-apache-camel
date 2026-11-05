package com.noahburnette.ignition.apachecamel.gateway;

import com.inductiveautomation.ignition.gateway.config.DecodedResource;
import com.inductiveautomation.ignition.gateway.config.actions.DefaultResourceActions;
import com.inductiveautomation.ignition.gateway.config.actions.ResourceAction;
import com.inductiveautomation.ignition.gateway.config.actions.StatefulModificationResponse;
import com.inductiveautomation.ignition.gateway.config.actions.StructuralModificationResponse;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared name validation for module resource managers.
 *
 * <p>Ignition's Gateway UI and REST layer allow arbitrary characters
 * (including whitespace) in resource names. Our {@code *Definition} records
 * reject anything outside {@link #NAME_PATTERN} because names flow into
 * scripting identifiers, JSON pointer segments, URL path components, and
 * topic segments where whitespace is not tolerable.</p>
 *
 * <p>Before this filter existed a single space-in-name resource would throw
 * from the definition constructor and abort the entire {@code onResourcesUpdated}
 * callback for its type. That silently cleared the whole registry until the
 * next Gateway restart. Use {@link #filter(String)} on every
 * {@code NamedResourceHandler} so bad names are dropped from reconciliation
 * with a single warning log entry and the remaining resources still load.</p>
 */
final class ResourceNames {
    static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private static final Logger LOGGER = LoggerFactory.getLogger(ResourceNames.class);

    private ResourceNames() { }

    static boolean isValid(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    /**
     * Returns a filter suitable for
     * {@code NamedResourceHandler.Builder#filter(Predicate)}. Invalid names
     * are logged once per reconciliation and excluded from the callback list.
     * This is defense in depth for resources that somehow bypass the
     * validating create/rename actions (for example a resource seeded on disk
     * or created via a redundant peer running an older module version).
     */
    static <R> Predicate<DecodedResource<R>> filter(String resourceType) {
        return decoded -> {
            String name = decoded.name();
            if (isValid(name)) {
                return true;
            }
            LOGGER.warn(
                "Ignoring {} resource with invalid name \"{}\": must match {}",
                resourceType, name, NAME_PATTERN
            );
            return false;
        };
    }

    /**
     * Wraps {@link DefaultResourceActions.Create} so that resource creation
     * fails with a 400-style error before Ignition persists an invalid name.
     * Every request in the batch is checked; if any one fails, the whole
     * batch is rejected (matching Ignition's own atomic batch semantics).
     */
    static ResourceAction.Create validatingCreate(String resourceType) {
        DefaultResourceActions.Create delegate = new DefaultResourceActions.Create();
        return (configurationManager, requests, context) -> {
            for (ResourceAction.CreateRequest request : requests) {
                String name = request.path().getName();
                if (!isValid(name)) {
                    return new StatefulModificationResponse.Failure(
                        new IllegalArgumentException(invalidNameMessage(resourceType, name))
                    );
                }
            }
            return delegate.create(configurationManager, requests, context);
        };
    }

    /**
     * Wraps {@link DefaultResourceActions.Rename} so a rename to an invalid
     * name is rejected before the on-disk resource is moved.
     */
    static ResourceAction.Rename validatingRename(String resourceType) {
        DefaultResourceActions.Rename delegate = new DefaultResourceActions.Rename();
        return (configurationManager, type, oldName, newName, referenceHandling, actor, context) -> {
            if (!isValid(newName)) {
                return new StructuralModificationResponse.Failure(
                    new IllegalArgumentException(invalidNameMessage(resourceType, newName))
                );
            }
            return delegate.rename(configurationManager, type, oldName, newName, referenceHandling, actor, context);
        };
    }

    private static String invalidNameMessage(String resourceType, String name) {
        return "invalid " + resourceType + " name \"" + name
            + "\": must match " + NAME_PATTERN + " (no spaces or special characters)";
    }
}
