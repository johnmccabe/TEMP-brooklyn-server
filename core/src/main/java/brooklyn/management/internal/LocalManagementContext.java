package brooklyn.management.internal;

import static brooklyn.util.JavaGroovyEquivalents.elvis;
import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.config.BrooklynProperties;
import brooklyn.config.BrooklynProperties.Factory.Builder;
import brooklyn.entity.Application;
import brooklyn.entity.Effector;
import brooklyn.entity.Entity;
import brooklyn.entity.drivers.downloads.BasicDownloadsManager;
import brooklyn.entity.effector.Effectors;
import brooklyn.internal.storage.DataGridFactory;
import brooklyn.location.Location;
import brooklyn.management.AccessController;
import brooklyn.management.ExecutionContext;
import brooklyn.management.ExecutionManager;
import brooklyn.management.ManagementContext;
import brooklyn.management.SubscriptionManager;
import brooklyn.management.Task;
import brooklyn.util.task.BasicExecutionContext;
import brooklyn.util.task.BasicExecutionManager;
import brooklyn.util.text.Strings;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;

/**
 * A local (single node) implementation of the {@link ManagementContext} API.
 */
public class LocalManagementContext extends AbstractManagementContext {
    
    private static final Logger log = LoggerFactory.getLogger(LocalManagementContext.class);

    private static final Set<LocalManagementContext> INSTANCES = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<LocalManagementContext, Boolean>()));
    
    private final Builder builder;

    @VisibleForTesting
    static Set<LocalManagementContext> getInstances() {
        synchronized (INSTANCES) {
            return ImmutableSet.copyOf(INSTANCES);
        }
    }

    // Note also called reflectively by BrooklynLeakListener
    public static void logAll(Logger logger){
        for (LocalManagementContext context : getInstances()) {
            logger.warn("Management Context "+context+" running, creation stacktrace:\n" + Throwables.getStackTraceAsString(context.constructionStackTrace));
        }
    }

    
    // Note also called reflectively by BrooklynLeakListener
    public static void terminateAll() {
        for (LocalManagementContext context : getInstances()) {
            try {
                context.terminate();
            }catch (Throwable t) {
                log.warn("Failed to terminate management context", t);
            }
        }
    }

    private String managementPlaneId;
    private String managementNodeId;
    private BasicExecutionManager execution;
    private SubscriptionManager subscriptions;
    private LocalEntityManager entityManager;
    private final LocalLocationManager locationManager;
    private final LocalAccessManager accessManager;
    private final LocalUsageManager usageManager;
    
    private final Throwable constructionStackTrace = new Throwable("for construction stacktrace").fillInStackTrace();
    
    private final Map<String, Object> brooklynAdditionalProperties;
    
    /**
     * Creates a LocalManagement with default BrooklynProperties.
     */
    public LocalManagementContext() {
        this(BrooklynProperties.Factory.newDefault());
    }

    public LocalManagementContext(BrooklynProperties brooklynProperties) {
        this(brooklynProperties, (DataGridFactory)null);
    }

    /**
     * Creates a new LocalManagementContext.
     *
     * @param brooklynProperties the BrooklynProperties.
     * @param datagridFactory the DataGridFactory to use. If this instance is null, it means that the system
     *                        is going to use BrooklynProperties to figure out which instance to load or otherwise
     *                        use a default instance.
     */
    @VisibleForTesting
    public LocalManagementContext(BrooklynProperties brooklynProperties, DataGridFactory datagridFactory) {
        this(Builder.fromProperties(brooklynProperties), datagridFactory);
    }
    
    public LocalManagementContext(Builder builder) {
        this(builder, null, null);
    }
    
    public LocalManagementContext(Builder builder, DataGridFactory datagridFactory) {
        this(builder, null, datagridFactory);
    }

    public LocalManagementContext(Builder builder, Map<String, Object> brooklynAdditionalProperties) {
        this(builder, brooklynAdditionalProperties, null);
    }
    
    public LocalManagementContext(BrooklynProperties brooklynProperties, Map<String, Object> brooklynAdditionalProperties) {
        this(Builder.fromProperties(brooklynProperties), brooklynAdditionalProperties, null);
    }
    
    public LocalManagementContext(Builder builder, Map<String, Object> brooklynAdditionalProperties, DataGridFactory datagridFactory) {
        super(builder.build(), datagridFactory);
        // TODO in a persisted world the planeId may be injected
        this.managementPlaneId = Strings.makeRandomId(8);
        
        this.managementNodeId = Strings.makeRandomId(8);
        checkNotNull(configMap, "brooklynProperties");
        this.builder = builder;
        this.locationManager = new LocalLocationManager(this);
        this.accessManager = new LocalAccessManager();
        this.usageManager = new LocalUsageManager(this);
        this.brooklynAdditionalProperties = brooklynAdditionalProperties;
        if (brooklynAdditionalProperties != null)
            configMap.addFromMap(brooklynAdditionalProperties);
        INSTANCES.add(this);
        log.debug("Created management context "+this);
    }

    @Override
    public String getManagementPlaneId() {
        return managementPlaneId;
    }
    
    @Override
    public String getManagementNodeId() {
        return managementNodeId;
    }
    
    @Override
    public void prePreManage(Entity entity) {
        getEntityManager().prePreManage(entity);
    }

    @Override
    public void prePreManage(Location location) {
        getLocationManager().prePreManage(location);
    }

    @Override
    public synchronized Collection<Application> getApplications() {
        return getEntityManager().getApplications();
    }

    @Override
    public void addEntitySetListener(CollectionChangeListener<Entity> listener) {
        getEntityManager().addEntitySetListener(listener);
    }

    @Override
    public void removeEntitySetListener(CollectionChangeListener<Entity> listener) {
        getEntityManager().removeEntitySetListener(listener);
    }

    @Override
    protected void manageIfNecessary(Entity entity, Object context) {
        getEntityManager().manageIfNecessary(entity, context);
    }

    @Override
    public synchronized LocalEntityManager getEntityManager() {
        if (!isRunning()) throw new IllegalStateException("Management context no longer running");

        if (entityManager == null) {
            entityManager = new LocalEntityManager(this);
        }
        return entityManager;
    }

    @Override
    public synchronized LocalLocationManager getLocationManager() {
        if (!isRunning()) throw new IllegalStateException("Management context no longer running");
        return locationManager;
    }

    @Override
    public synchronized LocalAccessManager getAccessManager() {
        if (!isRunning()) throw new IllegalStateException("Management context no longer running");
        return accessManager;
    }

    @Override
    public synchronized LocalUsageManager getUsageManager() {
        if (!isRunning()) throw new IllegalStateException("Management context no longer running");
        return usageManager;
    }

    @Override
    public synchronized AccessController getAccessController() {
        return getAccessManager().getAccessController();
    }
    
    @Override
    public synchronized  SubscriptionManager getSubscriptionManager() {
        if (!isRunning()) throw new IllegalStateException("Management context no longer running");

        if (subscriptions == null) {
            subscriptions = new LocalSubscriptionManager(getExecutionManager());
        }
        return subscriptions;
    }

    @Override
    public synchronized ExecutionManager getExecutionManager() {
        if (!isRunning()) throw new IllegalStateException("Management context no longer running");

        if (execution == null) {
            execution = new BasicExecutionManager(getManagementNodeId());
            gc = new BrooklynGarbageCollector(configMap, execution);
        }
        return execution;
    }

    @Override
    public void terminate() {
        INSTANCES.remove(this);
        super.terminate();
        if (execution != null) execution.shutdownNow();
        if (gc != null) gc.shutdownNow();
    }

    @Override
    protected void finalize() {
        terminate();
    }

    @Override
    public <T> Task<T> runAtEntity(@SuppressWarnings("rawtypes") Map flags, Entity entity, Callable<T> c) {
		manageIfNecessary(entity, elvis(Arrays.asList(flags.get("displayName"), flags.get("description"), flags, c)));
        return getExecutionContext(entity).submit(flags, c);
    }

    
    @Override
    protected <T> Task<T> runAtEntity(final Entity entity, final Effector<T> eff, @SuppressWarnings("rawtypes") final Map parameters) {
        manageIfNecessary(entity, eff);
        // prefer to submit this from the current execution context so it sets up correct cross-context chaining
        ExecutionContext ec = BasicExecutionContext.getCurrentExecutionContext();
        if (ec == null) {
            log.debug("Top-level effector invocation: {} on {}", eff, entity);
            ec = getExecutionContext(entity);
        }
        return ec.submit(Effectors.invocation(entity, eff, parameters));
    }

    @Override
    public boolean isManagedLocally(Entity e) {
        return true;
    }

    @Override
    public String toString() {
        return LocalManagementContext.class.getSimpleName()+"["+getManagementPlaneId()+"-"+getManagementNodeId()+"]";
    }

    @Override
    public void reloadBrooklynProperties() {
        BrooklynProperties properties = builder.build();
        configMap = properties;
        if (brooklynAdditionalProperties != null)
            configMap.addFromMap(brooklynAdditionalProperties);
        this.downloadsManager = BasicDownloadsManager.newDefault(configMap);

        // Force reload of location registry
        this.locationRegistry = null;
    }
}
