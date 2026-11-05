package com.noahburnette.ignition.apachecamel.gateway;

import com.inductiveautomation.ignition.common.model.ApplicationScope;
import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceFilter;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceId;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.common.resourcecollection.RuntimeResourceCollection;
import com.inductiveautomation.ignition.gateway.project.ProjectManager;
import com.inductiveautomation.ignition.gateway.resourcecollection.ResourceCollectionLifecycle;
import com.inductiveautomation.ignition.gateway.resourcecollection.ResourceCollectionLifecycleFactory;
import java.util.List;
import java.util.function.Consumer;

/** Uses effective project resources, so inherited script changes reach the child project too. */
final class CamelProjectLifecycle extends ResourceCollectionLifecycleFactory<CamelProjectLifecycle.Project> {
    private final Consumer<String> changed;

    CamelProjectLifecycle(ProjectManager projects, Consumer<String> changed) {
        super(projects);
        this.changed = changed;
    }

    @Override
    public Project createLifecycle(RuntimeResourceCollection project) {
        return new Project(project);
    }

    @Override
    protected ResourceFilter getResourceFilter() {
        return new ResourceFilter(ApplicationScope.GATEWAY, List.of(new ResourceType("ignition", "script-python")));
    }

    final class Project extends ResourceCollectionLifecycle {
        private boolean scriptsChanged;
        Project(RuntimeResourceCollection project) { super(project); }

        private void notifyChanged() { changed.accept(getResourceCollection().getName()); }

        @Override
        protected void onStartup(List<Resource> resources) { notifyChanged(); }

        @Override
        protected void onShutdown(List<ResourceId> resources) { notifyChanged(); }

        @Override
        protected void onResourcesCreated(List<Resource> resources) { scriptsChanged |= !resources.isEmpty(); }

        @Override
        protected void onResourcesModified(List<Resource> resources) { scriptsChanged |= !resources.isEmpty(); }

        @Override
        protected void onResourcesDeleted(List<ResourceId> resources) { scriptsChanged |= !resources.isEmpty(); }

        @Override
        protected void onAfterChanges() {
            if (scriptsChanged) {
                scriptsChanged = false;
                notifyChanged();
            }
        }
    }
}
