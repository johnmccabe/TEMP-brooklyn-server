package brooklyn.management.internal;

import java.net.URL;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import brooklyn.config.BrooklynProperties;
import brooklyn.config.ConfigKey;
import brooklyn.entity.Effector;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.BrooklynTaskTags;
import brooklyn.entity.basic.ConfigKeys;
import brooklyn.entity.proxying.InternalEntityFactory;
import brooklyn.internal.storage.BrooklynStorage;
import brooklyn.location.Location;
import brooklyn.management.ManagementContext;
import brooklyn.management.Task;
import brooklyn.util.task.TaskTags;

public interface ManagementContextInternal extends ManagementContext {

    public static final String SUB_TASK_TAG = TaskTags.SUB_TASK_TAG;
    
    public static final String EFFECTOR_TAG = BrooklynTaskTags.EFFECTOR_TAG;
    public static final String NON_TRANSIENT_TASK_TAG = BrooklynTaskTags.NON_TRANSIENT_TASK_TAG;
    public static final String TRANSIENT_TASK_TAG = BrooklynTaskTags.TRANSIENT_TASK_TAG;

    public static final ConfigKey<String> BROOKLYN_CATALOG_URL = ConfigKeys.newStringConfigKey("brooklyn.catalog.url",
            "The URL of a catalog.xml descriptor; absent for default (~/.brooklyn/catalog.xml), " +
            "or empty for no URL (use default scanner)", "file://~/.brooklyn/catalog.xml");
    
    ClassLoader getBaseClassLoader();

    Iterable<URL> getBaseClassPathForScanning();

    void setBaseClassPathForScanning(Iterable<URL> urls);

    void addEntitySetListener(CollectionChangeListener<Entity> listener);

    void removeEntitySetListener(CollectionChangeListener<Entity> listener);

    void terminate();
    
    long getTotalEffectorInvocations();

    <T> T invokeEffectorMethodSync(final Entity entity, final Effector<T> eff, final Object args) throws ExecutionException;
    
    <T> Task<T> invokeEffector(final Entity entity, final Effector<T> eff, @SuppressWarnings("rawtypes") final Map parameters);

    BrooklynStorage getStorage();
    
    BrooklynProperties getBrooklynProperties();
    
    AccessManager getAccessManager();

    UsageManager getUsageManager();

    InternalEntityFactory getEntityFactory();
    
    /**
     * Registers an entity that has been created, but that has not yet begun to be managed.
     * <p>
     * This differs from the idea of "preManaged" where the entities are in the process of being
     * managed, but where management is not yet complete.
     */
    // TODO would benefit from better naming! The name has percolated up from LocalEntityManager.
    //      should we just rename here as register or preManage?
    void prePreManage(Entity entity);

    /**
     * Registers a location that has been created, but that has not yet begun to be managed.
     */
    void prePreManage(Location location);
}
