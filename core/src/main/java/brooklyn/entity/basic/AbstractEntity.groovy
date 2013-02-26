package brooklyn.entity.basic

import static com.google.common.base.Preconditions.checkNotNull

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import brooklyn.config.ConfigKey
import brooklyn.config.ConfigKey.HasConfigKey
import brooklyn.enricher.basic.AbstractEnricher
import brooklyn.entity.Application
import brooklyn.entity.Effector
import brooklyn.entity.Entity
import brooklyn.entity.EntityType
import brooklyn.entity.Group
import brooklyn.entity.basic.EntityReferences.EntityCollectionReference
import brooklyn.entity.proxying.InternalEntityFactory
import brooklyn.entity.rebind.BasicEntityRebindSupport
import brooklyn.entity.rebind.RebindSupport
import brooklyn.event.AttributeSensor
import brooklyn.event.Sensor
import brooklyn.event.SensorEvent
import brooklyn.event.SensorEventListener
import brooklyn.event.basic.AttributeMap
import brooklyn.event.basic.AttributeSensorAndConfigKey
import brooklyn.event.basic.BasicNotificationSensor
import brooklyn.location.Location
import brooklyn.management.EntityManager
import brooklyn.management.ExecutionContext
import brooklyn.management.ManagementContext
import brooklyn.management.SubscriptionContext
import brooklyn.management.SubscriptionHandle
import brooklyn.management.Task
import brooklyn.management.internal.EntityManagementSupport
import brooklyn.management.internal.SubscriptionTracker
import brooklyn.mementos.EntityMemento
import brooklyn.policy.Enricher
import brooklyn.policy.Policy
import brooklyn.policy.basic.AbstractPolicy
import brooklyn.util.BrooklynLanguageExtensions
import brooklyn.util.config.ConfigBag;
import brooklyn.util.flags.FlagUtils
import brooklyn.util.flags.SetFromFlag
import brooklyn.util.task.DeferredSupplier;
import brooklyn.util.text.Identifiers

import com.google.common.annotations.Beta
import com.google.common.base.Objects
import com.google.common.base.Objects.ToStringHelper
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Iterables
import com.google.common.collect.Maps

/**
 * Default {@link Entity} implementation, which should be extended whenever implementing an entity.
 *
 * Provides several common fields ({@link #name}, {@link #id}), and supports the core features of
 * an entity such as configuration keys, attributes, subscriptions and effector invocation.
 * 
 * Though currently Groovy code, this is very likely to change to pure Java in a future release of 
 * Brooklyn so Groovy'isms should not be relied on.
 * 
 * Sub-classes should have a no-argument constructor. When brooklyn creates an entity, it will:
 * <ol>
 *   <li>Construct the entity via the no-argument constructor
 *   <li>Set the managment context
 *   <li>Set the proxy, which should be used by everything else when referring to this entity
 *       (except for drivers/policies that are attached to the entity, which can be given a 
 *       reference to this entity itself).
 *   <li>Configure the entity, first via the "flags" map and then via configuration keys
 *   <li>Set  the parent
 * </ol>
 * 
 * The legacy (pre 0.5) mechanism for creating entities is for others to call the constructor directly.
 * This is now deprecated.
 * 
 * Note that config is typically inherited by children, whereas the fields and attributes are not.
 */
public abstract class AbstractEntity extends GroovyObjectSupport implements EntityLocal, EntityInternal, GroovyInterceptable {
    
    protected static final Logger LOG = LoggerFactory.getLogger(AbstractEntity.class)
    static { BrooklynLanguageExtensions.init(); }
    
    public static BasicNotificationSensor<Sensor> SENSOR_ADDED = new BasicNotificationSensor<Sensor>(Sensor.class,
            "entity.sensor.added", "Sensor dynamically added to entity")
    public static BasicNotificationSensor<Sensor> SENSOR_REMOVED = new BasicNotificationSensor<Sensor>(Sensor.class,
            "entity.sensor.removed", "Sensor dynamically removed from entity")

    @SetFromFlag(value="id")
    private String id = Identifiers.makeRandomId(8);
    
    String displayName
    
    private Entity selfProxy;
    private volatile Entity parent
    private volatile Application application
    final EntityCollectionReference<Group> groups = new EntityCollectionReference<Group>(this);
    
    final EntityCollectionReference children = new EntityCollectionReference<Entity>(this);

    Map<String,Object> presentationAttributes = [:]
    Collection<AbstractPolicy> policies = [] as CopyOnWriteArrayList
    Collection<AbstractEnricher> enrichers = [] as CopyOnWriteArrayList
    Collection<Location> locations =
        new ConcurrentLinkedQueue<Location>();
        // prefer above, because we want to preserve order, FEB 2013
        // with duplicates removed in addLocations
        //Collections.newSetFromMap(new ConcurrentHashMap<Location,Boolean>())

    // FIXME we do not currently support changing parents, but to implement a cluster that can shrink we need to support at least
    // orphaning (i.e. removing ownership). This flag notes if the entity has previously had a parent, and if an attempt is made to
    // set a new parent an exception will be thrown.
    boolean previouslyOwned = false

    /**
     * Whether we are still being constructed, in which case never warn in "assertNotYetOwned"
     */
    private boolean inConstruction = true;
    
    private final EntityDynamicType entityType;
    
    protected transient EntityManagementSupport managementSupport;
    
    /**
     * The config values of this entity. Updating this map should be done
     * via getConfig/setConfig.
     */
    protected final EntityConfigMap configsInternal = new EntityConfigMap(this)

    /**
     * The sensor-attribute values of this entity. Updating this map should be done
     * via getAttribute/setAttribute; it will automatically emit an attribute-change event.
     */
    protected final AttributeMap attributesInternal = new AttributeMap(this)

    /**
     * For temporary data, e.g. timestamps etc for calculating real attribute values, such as when
     * calculating averages over time etc.
     */
    protected final Map<String,Object> tempWorkings = [:]

    protected transient SubscriptionTracker _subscriptionTracker;

    private final boolean _legacyConstruction;
    
    public AbstractEntity() {
        this([:], null)
    }

    /**
     * @deprecated since 0.5; instead use no-arg constructor with EntityManager().createEntity(spec)
     */
    @Deprecated
    public AbstractEntity(Map flags) {
        this(flags, null);
    }

    /**
     * @deprecated since 0.5; instead use no-arg constructor with EntityManager().createEntity(spec)
     */
    @Deprecated
    public AbstractEntity(Entity parent) {
        this([:], parent);
    }

    // FIXME don't leak this reference in constructor - even to utils
    /**
     * @deprecated since 0.5; instead use no-arg constructor with EntityManager().createEntity(spec)
     */
    @Deprecated
    public AbstractEntity(Map flags, Entity parent) {
        this.@skipInvokeMethodEffectorInterception.set(true)
        try {
            if (flags==null) {
                throw new IllegalArgumentException("Flags passed to entity $this must not be null (try no-arguments or empty map)")
            }
            if (flags.parent != null && parent != null && flags.parent != parent) {
                throw new IllegalArgumentException("Multiple parents supplied, ${flags.parent} and $parent")
            }
            if (flags.owner != null && parent != null && flags.owner != parent) {
                throw new IllegalArgumentException("Multiple parents supplied with flags.parent, ${flags.owner} and $parent")
            }
            if (flags.parent != null && flags.owner != null && flags.parent != flags.owner) {
                throw new IllegalArgumentException("Multiple parents supplied with flags.parent and flags.owner, ${flags.parent} and ${flags.owner}")
            }
            if (parent != null) {
                flags.parent = parent;
            }
            if (flags.owner != null) {
                LOG.warn("Use of deprecated \"flags.owner\" instead of \"flags.parent\" for entity {}", this);
                flags.parent = flags.owner;
                flags.remove('owner');
            }

            // TODO Don't let `this` reference escape during construction
            entityType = new EntityDynamicType(this);
            
            _legacyConstruction = !InternalEntityFactory.FactoryConstructionTracker.isConstructing();
            
            if (_legacyConstruction) {
                LOG.warn("Deprecated use of old-style entity construction for "+getClass().getName()+"; instead use EntityManager().createEntity(spec)");
                def checkWeGetThis = configure(flags);
                assert this == checkWeGetThis : "$this configure method does not return itself; returns $checkWeGetThis instead"
            }
            
            inConstruction = false;
            
        } finally { this.@skipInvokeMethodEffectorInterception.set(false) }
    }

    public int hashCode() {
        return id.hashCode();
    }
    
    public boolean equals(Object o) {
        return o != null && (o.is(this) || o.is(selfProxy));
    }
    
    protected boolean isLegacyConstruction() {
        return _legacyConstruction;
    }
    
    public String getId() {
        return id;
    }
    
    public void setProxy(Entity proxy) {
        if (selfProxy != null) throw new IllegalStateException("Proxy is already set; cannot reset proxy for "+toString());
        this.@selfProxy = checkNotNull(proxy, "proxy");
    }
    
    public Entity getProxy() {
        return this.@selfProxy;
    }
    
    /**
     * Returns the proxy, or if not available (because using legacy code) then returns the real entity.
     * This method will be deleted in a future release; it will be kept while deprecated legacy code
     * still exists that creates entities without setting the proxy.
     */
    @Beta
    public Entity getProxyIfAvailable() {
        return getProxy() ?: this;
    }
    
    /** @deprecated since 0.4.0 now handled by EntityMangementSupport */
    public void setBeingManaged() {
        // no-op
    }
    
    /**
     * FIXME Temporary workaround for use-case:
     *  - the load balancing policy test calls app.managementContext.unmanage(itemToStop)
     *  - concurrently, the policy calls an effector on that item: item.move()
     *  - The code in AbstractManagementContext.invokeEffectorMethodSync calls manageIfNecessary.
     *    This detects that the item is not managed, and sets it as managed again. The item is automatically
     *    added back into the dynamic group, and the policy receives an erroneous MEMBER_ADDED event.
     * 
     * @deprecated since 0.4.0 now handled by EntityMangementSupport
     */
    public boolean hasEverBeenManaged() {
        return getManagementSupport().wasDeployed();
    }
    
    /** sets fields from flags; can be overridden if needed, subclasses should
     * set custom fields before _invoking_ this super
     * (and they nearly always should invoke the super)
     * <p>
     * note that it is usually preferred to use the SetFromFlag annotation on relevant fields
     * so they get set automatically by this method and overriding it is unnecessary
     *
     * @return this entity, for fluent style initialization
     */
    public AbstractEntity configure(Map flags=[:]) {
        assertNotYetOwned()
        // TODO use a config bag instead
//        ConfigBag bag = new ConfigBag().putAll(flags);
        
        // FIXME Need to set parent with proxy, rather than `this`
        Entity suppliedParent = flags.remove('parent') ?: null
        if (suppliedParent) {
            suppliedParent.addChild(getProxyIfAvailable())
        }
        
        Map<ConfigKey,Object> suppliedOwnConfig = flags.remove('config')
        if (suppliedOwnConfig != null) {
            for (Map.Entry<ConfigKey, Object> entry : suppliedOwnConfig.entrySet()) {
                setConfigEvenIfOwned(entry.getKey(), entry.getValue());
            }
        }

        displayName = flags.remove('displayName') ?: displayName;

        // allow config keys, and fields, to be set from these flags if they have a SetFromFlag annotation
        // TODO use the setDefaultVals variant
        flags = FlagUtils.setFieldsFromFlags(flags, this);
        flags = FlagUtils.setAllConfigKeys(flags, this);
        
        if (displayName==null)
            displayName = flags.name ? flags.remove('name') : getClass().getSimpleName()+":"+id.substring(0, 4)
        
        // all config keys specified in map should be set as config
        for (Iterator fi = flags.iterator(); fi.hasNext(); ) {
            Map.Entry entry = fi.next();
            Object k = entry.key;
            if (k in HasConfigKey) k = ((HasConfigKey)k).getConfigKey();
            if (k in ConfigKey) {
                setConfigEvenIfOwned(k, entry.value);
                fi.remove();
            }
        }
        
        if (!flags.isEmpty()) {
            LOG.warn "Unsupported flags when configuring {}; ignoring: {}", this, flags
        }

        return this;
    }
    
    /**
     * Sets a config key value, and returns this Entity instance for use in fluent-API style coding.
     */
    public <T> AbstractEntity configure(ConfigKey<T> key, T value) {
        setConfig(key, value);
        return this;
    }
    public <T> AbstractEntity configure(ConfigKey<T> key, String value) {
        setConfig(key, value);
        return this;
    }
    public <T> AbstractEntity configure(HasConfigKey<T> key, T value) {
        setConfig(key, value);
        return this;
    }
    public <T> AbstractEntity configure(HasConfigKey<T> key, String value) {
        setConfig(key, value);
        return this;
    }

    public void setManagementContext(ManagementContext managementContext) {
        getManagementSupport().setManagementContext(managementContext);
        entityType.setName(getEntityTypeName());
    }

    /**
     * Gets the entity type name, to be returned by {@code getEntityType().getName()}.
     * To be called by brooklyn internals only.
     * Can be overridden to customize the name.
     */
    protected String getEntityTypeName() {
        try {
            Class<?> typeClazz = getManagementSupport().getManagementContext(true).getEntityManager().getEntityTypeRegistry().getEntityTypeOf(getClass());
            String typeName = typeClazz.getCanonicalName();
            if (typeName == null) typeName = typeClazz.getName();
            return typeName;
        } catch (IllegalArgumentException e) {
            String typeName = getClass().getCanonicalName();
            if (typeName == null) typeName = getClass().getName();
            LOG.warn("Entity type interface not found for entity "+this+"; instead using "+typeName);
            return typeName;
        }
    }
    
    /**
     * Called by framework (in new-style entities) after configuring, setting parent, etc,
     * but before a reference to this entity is shared with other entities.
     * 
     * To preserve backwards compatibility for if the entity is constructed directly, one
     * can add to the start method the code below, but that means it will be called after
     * references to this entity have been shared with other entities.
     * <pre>
     * {@code
     * if (isLegacyConstruction()) {
     *     postConstruct();
     * }
     * }
     * </pre>
     */
    public void postConstruct() {
    }
    
    /**
     * Adds this as a child of the given entity; registers with application if necessary.
     */
    @Override
    public AbstractEntity setParent(Entity entity) {
        if (parent != null) {
            // If we are changing to the same parent...
            if (parent == entity) return
            // If we have a parent but changing to orphaned...
            if (entity==null) { clearParent(); return; }
            
            // We have a parent and are changing to another parent...
            throw new UnsupportedOperationException("Cannot change parent of $this from $parent to $entity (parent change not supported)")
        }
        // If we have previously had a parent and are trying to change to another one...
        if (previouslyOwned && entity != null)
            throw new UnsupportedOperationException("Cannot set a parent of $this because it has previously had a parent")
        // We don't have a parent, never have and are changing to having a parent...

        //make sure there is no loop
        if (this.equals(entity)) throw new IllegalStateException("entity $this cannot own itself")
        //this may be expensive, but preferable to throw before setting the parent!
        if (Entities.isDescendant(this, entity))
            throw new IllegalStateException("loop detected trying to set parent of $this as $entity, which is already a descendent")
        
        parent = entity
        //previously tested entity!=null but that should be guaranteed?
        entity.addChild(getProxyIfAvailable())
        configsInternal.setInheritedConfig(((EntityInternal)entity).getAllConfig());
        previouslyOwned = true
        
        getApplication()
        
        return this;
    }

    @Override
    @Deprecated // see setParent(Entity)
    public AbstractEntity setOwner(Entity entity) {
        return setParent(entity);
    }
    
    @Override
    public void clearParent() {
        if (parent == null) return
        Entity oldParent = parent
        parent = null
        oldParent?.removeChild(getProxyIfAvailable())
    }
    
    @Override
    @Deprecated // see clearParent
    public void clearOwner() {
        clearParent();
    }

    /**
     * Adds the given entity as a member of this group <em>and</em> this group as one of the groups of the child;
     * returns argument passed in, for convenience.
     */
    @Override
    public Entity addChild(Entity child) {
    // see comment in interface:
//    public <T extends Entity> T addChild(T child) {
        synchronized (children) {
            if (child == null) throw new NullPointerException("child must not be null (for entity "+this+")")
            if (Entities.isAncestor(this, child)) throw new IllegalStateException("loop detected trying to add child $child to $this; it is already an ancestor")
            child.setParent(getProxyIfAvailable())
            children.add(child)
            
            getManagementSupport().getEntityChangeListener().onChildrenChanged();
        }
        return child
    }
    
    @Override
    @Deprecated // see addChild(Entity)
    public Entity addOwnedChild(Entity child) {
        return addChild(child);
    }

    @Override
    public boolean removeChild(Entity child) {
        synchronized (children) {
            boolean changed = children.remove(child)
            child.clearParent()
            
            if (changed) {
                getManagementSupport().getEntityChangeListener().onChildrenChanged();
            }
            return changed
        }
    }

    @Override
    @Deprecated // see removeChild(Entity)
    public boolean removeOwnedChild(Entity child) {
        return removeChild(child);
    }
    
    /**
     * Adds this as a member of the given group, registers with application if necessary
     */
    @Override
    public void addGroup(Group e) {
        groups.add e
        getApplication()
    }

    @Override
    public Entity getParent() {
        return this.@parent;
    }

    // TODO synchronization: need to synchronize on children, or have children be a synchronized collection
    @Override
    public Collection<Entity> getChildren() {
        return children.get();
    }
    
    public EntityCollectionReference getChildrenReference() {
        return this.@children;
    }
    
    @Override
    @Deprecated
    public Entity getOwner() {
        return getParent();
    }

    @Override
    @Deprecated
    public Collection<Entity> getOwnedChildren() {
        return getChildren();
    }
    
    @Deprecated
    public EntityCollectionReference getOwnedChildrenReference() {
        return getChildrenReference();
    }
    
    @Override
    public Collection<Group> getGroups() { groups.get() }

    /**
     * Returns the application, looking it up if not yet known (registering if necessary)
     */
    @Override
    public Application getApplication() {
        if (this.@application!=null) return this.@application;
        def app = getParent()?.getApplication()
        if (app) {
            setApplication(app)
        }
        app
    }

    /** @deprecated since 0.4.0 should not be needed / leaked outwith brooklyn internals / mgmt support? */
    protected synchronized void setApplication(Application app) {
        if (application) {
            if (this.@application.id != app.id) {
                throw new IllegalStateException("Cannot change application of entity (attempted for $this from ${this.application} to ${app})")
            }
        }
        this.application = app;
    }

    @Override
    public String getApplicationId() {
        getApplication()?.id
    }

    /** @deprecated since 0.4.0 should not be needed / leaked outwith brooklyn internals? */
    @Override
    @Deprecated
    public synchronized ManagementContext getManagementContext() {
        return getManagementSupport().getManagementContext(false);
    }

    protected EntityManager getEntityManager() {
        return getManagementSupport().getManagementContext(true).getEntityManager();
    }
    
    @Override
    public EntityType getEntityType() {
        return entityType.getSnapshot();
    }

    /** returns the dynamic type corresponding to the type of this entity instance */
    public EntityDynamicType getMutableEntityType() {
        return entityType;
    }
    
    @Override
    public Collection<Location> getLocations() {
        locations
    }

    @Override
    public void addLocations(Collection<? extends Location> newLocations) {
        synchronized (locations) {
            Set newLocationsWithDuplicatesRemoved = new LinkedHashSet();
            newLocationsWithDuplicatesRemoved.addAll(newLocations);
            newLocationsWithDuplicatesRemoved.removeAll(locations);
            locations.addAll(newLocationsWithDuplicatesRemoved);
        }
        getManagementSupport().getEntityChangeListener().onLocationsChanged();
    }

    @Override
    public void removeLocations(Collection<? extends Location> newLocations) {
        locations.removeAll(newLocations);
        
        getManagementSupport().getEntityChangeListener().onLocationsChanged();
    }

    public Location firstLocation() {
        return Iterables.get(locations, 0)
    }
    
    /**
     * Should be invoked at end-of-life to clean up the item.
     */
    @Override
    public void destroy() {
        // TODO we need some way of deleting stale items
    }

    @Override
    public <T> T getAttribute(AttributeSensor<T> attribute) {
        return attributesInternal.getValue(attribute);
    }

    public <T> T getAttributeByNameParts(List<String> nameParts) {
        return attributesInternal.getValue(nameParts)
    }
    
    @Override
    public <T> T setAttribute(AttributeSensor<T> attribute, T val) {
        T result = attributesInternal.update(attribute, val);
        if (result == null) {
            // could be this is a new sensor
            entityType.addSensorIfAbsent(attribute);
        }
        
        getManagementSupport().getEntityChangeListener().onAttributeChanged(attribute);
        return result;
    }

    @Override
    public <T> T setAttributeWithoutPublishing(AttributeSensor<T> attribute, T val) {
        T result = attributesInternal.updateWithoutPublishing(attribute, val);
        if (result == null) {
            // could be this is a new sensor
            entityType.addSensorIfAbsentWithoutPublishing(attribute);
        }
        
        getManagementSupport().getEntityChangeListener().onAttributeChanged(attribute);
        return result;
    }

    @Override
    public void removeAttribute(AttributeSensor<?> attribute) {
        attributesInternal.remove(attribute);
        entityType.removeSensor(attribute);
    }

    /** sets the value of the given attribute sensor from the config key value herein,
     * if the config key resolves to a non-null value as a sensor
     * <p>
     * returns old value */
    public <T> T setAttribute(AttributeSensorAndConfigKey<?,T> configuredSensor) {
        T v = getAttribute(configuredSensor);
        if (v!=null) return v;
        v = configuredSensor.getAsSensorValue(this);
        if (v!=null) return setAttribute(configuredSensor, v)
        return null;
    }

    @Override
    public <T> T getConfig(ConfigKey<T> key) {
        return configsInternal.getConfig(key);
    }
    
    @Override
    public <T> T getConfig(HasConfigKey<T> key) {
        return configsInternal.getConfig(key);
    }
    
    @Override
    public <T> T getConfig(HasConfigKey<T> key, T defaultValue) {
        return configsInternal.getConfig(key, defaultValue);
    }
    
    //don't use groovy defaults for defaultValue as that doesn't implement the contract; we need the above
    @Override
    public <T> T getConfig(ConfigKey<T> key, T defaultValue) {
        return configsInternal.getConfig(key, defaultValue);
    }

    // TODO assertNotYetManaged would be a better name?
    protected void assertNotYetOwned() {
        if (!inConstruction && getManagementSupport().isDeployed()) {
            LOG.warn("configuration being made to $this after deployment; may not be supported in future versions");
        }
        //throw new IllegalStateException("Cannot set configuration $key on active entity $this")
    }

    @Override
    public <T> T setConfig(ConfigKey<T> key, T val) {
        assertNotYetOwned()
        configsInternal.setConfig(key, val);
    }

    public <T> T setConfig(ConfigKey<T> key, Task<T> val) {
        assertNotYetOwned()
        configsInternal.setConfig(key, val);
    }

    public <T> T setConfig(ConfigKey<T> key, DeferredSupplier val) {
        assertNotYetOwned()
        configsInternal.setConfig(key, val);
    }

    @Override
    public <T> T setConfig(HasConfigKey<T> key, T val) {
        setConfig(key.configKey, val)
    }

    public <T> T setConfig(HasConfigKey<T> key, Task<T> val) {
        setConfig(key.configKey, val)
    }

    public <T> T setConfig(HasConfigKey<T> key, DeferredSupplier val) {
        setConfig(key.configKey, val)
    }

    public <T> T setConfigEvenIfOwned(ConfigKey<T> key, T val) {
        configsInternal.setConfig(key, val);
    }

    public <T> T setConfigEvenIfOwned(HasConfigKey<T> key, T val) {
        setConfigEvenIfOwned(key.getConfigKey(), val);
    }

    protected void setConfigIfValNonNull(ConfigKey key, Object val) {
        if (val != null) setConfig(key, val)
    }
    
    protected void setConfigIfValNonNull(HasConfigKey key, Object val) {
        if (val != null) setConfig(key, val)
    }

    public void refreshInheritedConfig() {
        if (getParent() != null) {
            configsInternal.setInheritedConfig(((EntityInternal)getParent()).getAllConfig())
        } else {
            configsInternal.clearInheritedConfig();
        }

        refreshInheritedConfigOfChildren();
    }

    void refreshInheritedConfigOfChildren() {
        children.get().each {
            ((EntityInternal)it).refreshInheritedConfig()
        }
    }

    public EntityConfigMap getConfigMap() {
        return configsInternal;
    }
    
    public Map<ConfigKey<?>,Object> getAllConfig() {
        return configsInternal.getAllConfig();
    }

    public Map<AttributeSensor, Object> getAllAttributes() {
        Map<AttributeSensor, Object> result = Maps.newLinkedHashMap();
        Map<String, Object> attribs = attributesInternal.asMap();
        for (Map.Entry<?,Object> entry : attribs.entrySet()) {
            AttributeSensor attribKey = (AttributeSensor) entityType.getSensor(entry.getKey());
            if (attribKey == null) {
                LOG.warn("When retrieving all attributes of {}, ignoring attribute {} because no matching AttributeSensor found", this, entry.getKey());
            } else {
                result.put(attribKey, entry.getValue());
            }
        }
        return result;
    }

    /** @see EntityLocal#subscribe */
    public <T> SubscriptionHandle subscribe(Entity producer, Sensor<T> sensor, SensorEventListener<? super T> listener) {
        return subscriptionTracker.subscribe(producer, sensor, listener)
    }

    /** @see EntityLocal#subscribeToChildren */
    public <T> SubscriptionHandle subscribeToChildren(Entity parent, Sensor<T> sensor, SensorEventListener<? super T> listener) {
        return subscriptionTracker.subscribeToChildren(parent, sensor, listener)
    }

    /** @see EntityLocal#subscribeToMembers */
    public <T> SubscriptionHandle subscribeToMembers(Group group, Sensor<T> sensor, SensorEventListener<? super T> listener) {
        return subscriptionTracker.subscribeToMembers(group, sensor, listener)
    }

    /**
     * Unsubscribes the given producer.
     *
     * @see SubscriptionContext#unsubscribe(SubscriptionHandle)
     */
    @Override
    public boolean unsubscribe(Entity producer) {
        return subscriptionTracker.unsubscribe(producer)
    }

    /**
     * Unsubscribes the given handle.
     *
     * @see SubscriptionContext#unsubscribe(SubscriptionHandle)
     */
    @Override
    public boolean unsubscribe(Entity producer, SubscriptionHandle handle) {
        return subscriptionTracker.unsubscribe(producer, handle)
    }

    public synchronized SubscriptionContext getSubscriptionContext() {
        return getManagementSupport().getSubscriptionContext();
    }

    protected synchronized SubscriptionTracker getSubscriptionTracker() {
        if (_subscriptionTracker!=null) return _subscriptionTracker;
        _subscriptionTracker = new SubscriptionTracker(getSubscriptionContext());
    }
    
    public synchronized ExecutionContext getExecutionContext() {
        return getManagementSupport().getExecutionContext();
    }

    /** Default String representation is simplified name of class, together with selected fields. */
    @Override
    public String toString() {
        Collection<String> fields = toStringFieldsToInclude();
        if (fields.equals(ImmutableSet.of('id', 'displayName'))) {
            return toStringHelper().toString();
        } else {
            StringBuilder result = []
            result << getClass().getSimpleName()
            if (!result) result << getClass().getName()
            result << "[" << fields.collect({
                def v = this.hasProperty(it) ? this[it] : null  /* TODO would like to use attributes, config: this.properties[it] */
                v ? "$it=$v" : null
            }).findAll({it!=null}).join(",") << "]"
        }
    }

    /**
     * Override this to add to the toString(), e.g. {@code return super.toStringHelper().add("port", port);}
     *
     * Cannot be used in combination with overriding the deprecated toStringFieldsToInclude.
     */
    protected ToStringHelper toStringHelper() {
        return Objects.toStringHelper(this).omitNullValues().add("id", getId()).add("name", getDisplayName());
    }
    
    /**
     * override this, adding to the collection, to supply fields whose value, if not null, should be included in the toString
     *
     * @deprecated In 0.5.0, instead override toStringHelper instead
     */
    @Deprecated
    public Collection<String> toStringFieldsToInclude() {
        return ['id', 'displayName'] as Set
    }

    
    // -------- POLICIES --------------------

    @Override
    public Collection<Policy> getPolicies() {
        return policies.asImmutable()
    }

    @Override
    public void addPolicy(Policy policy) {
        policies.add(policy)
        ((AbstractPolicy)policy).setEntity(this)
        
        getManagementSupport().getEntityChangeListener().onPoliciesChanged();
    }

    @Override
    boolean removePolicy(Policy policy) {
        ((AbstractPolicy)policy).destroy()
        boolean changed = policies.remove(policy)
        
        if (changed) {
            getManagementSupport().getEntityChangeListener().onPoliciesChanged();
        }
        return changed;
    }
    
    @Override
    boolean removeAllPolicies() {
        boolean changed = false;
        for (Policy policy : policies) {
            removePolicy(policy);
            changed = true;
        }
        
        if (changed) {
            getManagementSupport().getEntityChangeListener().onPoliciesChanged();
        }
    }
    
    @Override
    public Collection<Enricher> getEnrichers() {
        return enrichers.asImmutable()
    }

    @Override
    public void addEnricher(Enricher enricher) {
        enrichers.add(enricher)
        ((AbstractEnricher)enricher).setEntity(this)
    }

    @Override
    boolean removeEnricher(Enricher enricher) {
        ((AbstractEnricher)enricher).destroy()
        return enrichers.remove(enricher)
    }

    @Override
    boolean removeAllEnrichers() {
        for (AbstractEnricher enricher : enrichers) {
            removeEnricher(enricher);
        }
    }
    
    // -------- SENSORS --------------------

    @Override
    public <T> void emit(Sensor<T> sensor, T val) {
        if (sensor instanceof AttributeSensor) {
            LOG.warn("Strongly discouraged use of emit with attribute sensor $sensor $val; use setAttribute instead!",
                new Throwable("location of discouraged attribute $sensor emit"))
        }
        if (val instanceof SensorEvent) {
            LOG.warn("Strongly discouraged use of emit with sensor event as value $sensor $val; value should be unpacked!",
                new Throwable("location of discouraged event $sensor emit"))
        }
        if (LOG.isDebugEnabled()) LOG.debug "Emitting sensor notification {} value {} on {}", sensor.name, val, this
        emitInternal(sensor, val);
    }
    
    @Override
    public <T> void emitInternal(Sensor<T> sensor, T val) {
        subscriptionContext?.publish(sensor.newEvent(getProxyIfAvailable(), val))
    }

    // -------- EFFECTORS --------------

    /** Flag needed internally to prevent invokeMethod from recursing on itself. */
    private ThreadLocal<Boolean> skipInvokeMethodEffectorInterception = new ThreadLocal() { protected Object initialValue() { Boolean.FALSE } }

    /**
     * Called by groovy for all method invocations; pass-through for everything but effectors;
     * effectors get wrapped in a new task (parented by current task if there is one).
     */
    public Object invokeMethod(String name, Object args) {
        if (!this.@skipInvokeMethodEffectorInterception.get()) {
            this.@skipInvokeMethodEffectorInterception.set(true);
            
            try {
                //args should be an array, warn if we got here wrongly (extra defensive as args accepts it, but it shouldn't happen here)
                if (args==null)
                    LOG.warn("$this.$name invoked with incorrect args signature (null)", new Throwable("source of incorrect invocation of $this.$name"))
                else if (!args.class.isArray())
                    LOG.warn("$this.$name invoked with incorrect args signature (non-array ${args.class}): "+args, new Throwable("source of incorrect invocation of $this.$name"))

                Effector eff = entityType.getEffector(name);
                if (eff) {
                    return EffectorUtils.invokeEffector(this, eff, args)
                }
            } finally {
                this.@skipInvokeMethodEffectorInterception.set(false);
            }
        }
        if (metaClass==null)
            throw new IllegalStateException("no meta class for "+this+", invoking "+name);
        metaClass.invokeMethod(this, name, args);
    }

    /** Convenience for finding named effector in {@link #getEffectors()} {@link Map}. */
    public <T> Effector<T> getEffector(String effectorName) {
        return entityType.getEffector(effectorName);
    }

    /** Invoke an {@link Effector} directly. */
    public <T> Task<T> invoke(Map parameters=[:], Effector<T> eff) {
        invoke(eff, parameters);
    }

    /**
     * TODO Calling the above groovy method from java gives compilation error due to use of generics
     * This method will be removed once that is resolved in groovy (or when this is converted to pure java).
     */
    public Task<?> invokeFromJava(Map parameters=[:], Effector eff) {
        invoke(eff, parameters);
    }

    /**
     * Additional form supplied for when the parameter map needs to be made explicit.
     *
     * @see #invoke(Effector)
     */
    public <T> Task<T> invoke(Effector<T> eff, Map<String,?> parameters) {
        return EffectorUtils.invokeEffectorAsync(this, eff, parameters);
    }

    /**
     * Invoked by {@link EntityManagementSupport} when this entity is becoming managed (i.e. it has a working
     * management context, but before the entity is visible to other entities).
     */
    public void onManagementStarting() {
        if (isLegacyConstruction()) entityType.setName(getEntityTypeName());
    }
    
    /**
     * Invoked by {@link EntityManagementSupport} when this entity is fully managed and visible to other entities
     * through the management context.
     */
    public void onManagementStarted() {}
    
    /**
     * Invoked by {@link ManagementContext} when this entity becomes managed at a particular management node,
     * including the initial management started and subsequent management node master-change for this entity.
     * @deprecated since 0.4.0 override EntityManagementSupport.onManagementStarting if customization needed
     */
    public void onManagementBecomingMaster() {}
    
    /**
     * Invoked by {@link ManagementContext} when this entity becomes mastered at a particular management node,
     * including the final management end and subsequent management node master-change for this entity.
     * @deprecated since 0.4.0 override EntityManagementSupport.onManagementStopping if customization needed
     */
    public void onManagementNoLongerMaster() {}

    /** For use by management plane, to invalidate all fields (e.g. when an entity is changing to being proxied) */
    public void invalidateReferences() {
        // TODO move this to EntityMangementSupport,
        // when hierarchy fields can also be moved there
        this.@parent = null;
        this.@application = null;
        this.@children.invalidate();
        this.@groups.invalidate();
    }
    
    // TODO observed deadlocks -- we should synch on something private instead of on the class
    // Non-final to allow for mocking
    public synchronized EntityManagementSupport getManagementSupport() {
        if (managementSupport) return managementSupport;
        managementSupport = createManagementSupport();
        return managementSupport;
    }
    protected EntityManagementSupport createManagementSupport() {
        return new EntityManagementSupport(this);
    }

    @Override
    public RebindSupport<EntityMemento> getRebindSupport() {
        return new BasicEntityRebindSupport(this);
    }

    protected void finalize() throws Throwable {
        super.finalize();
        if (!getManagementSupport().wasDeployed())
            LOG.warn("Entity "+this+" was never deployed -- explicit call to manage(Entity) required.");
    }
}
