package brooklyn.entity.proxying;

import static com.google.common.base.Preconditions.checkNotNull;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.config.ConfigKey;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.AbstractEntity;
import brooklyn.entity.basic.EntityInternal;
import brooklyn.entity.basic.EntityLocal;
import brooklyn.management.ManagementContext;
import brooklyn.management.internal.LocalEntityManager;
import brooklyn.management.internal.ManagementContextInternal;
import brooklyn.policy.Enricher;
import brooklyn.policy.EnricherSpec;
import brooklyn.policy.Policy;
import brooklyn.policy.PolicySpec;
import brooklyn.policy.basic.AbstractPolicy;
import brooklyn.util.collections.MutableMap;
import brooklyn.util.collections.MutableSet;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.flags.FlagUtils;
import brooklyn.util.javalang.Reflections;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

/**
 * Creates entities (and proxies) of required types, given the 
 * 
 * This is an internal class for use by core-brooklyn. End-users are strongly discouraged from
 * using this class directly.
 * 
 * @author aled
 */
public class InternalEntityFactory {

    private static final Logger log = LoggerFactory.getLogger(InternalEntityFactory.class);
    
    private final ManagementContextInternal managementContext;
    private final EntityTypeRegistry entityTypeRegistry;
    private final InternalPolicyFactory policyFactory;
    
    /**
     * For tracking if AbstractEntity constructor has been called by framework, or in legacy way (i.e. directly).
     * 
     * To be deleted once we delete support for constructing entities directly (and expecting configure() to be
     * called inside the constructor, etc).
     * 
     * @author aled
     */
    public static class FactoryConstructionTracker {
        private static ThreadLocal<Boolean> constructing = new ThreadLocal<Boolean>();
        
        public static boolean isConstructing() {
            return (constructing.get() == Boolean.TRUE);
        }
        
        static void reset() {
            constructing.set(Boolean.FALSE);
        }
        
        static void setConstructing() {
            constructing.set(Boolean.TRUE);
        }
    }

    /**
     * Returns true if this is a "new-style" entity (i.e. where not expected to call the constructor to instantiate it).
     * That means it is an entity with a no-arg constructor, and where there is a mapped for an entity type interface.
     * @param managementContext
     * @param clazz
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static boolean isNewStyleEntity(ManagementContext managementContext, Class<?> clazz) {
        try {
            return isNewStyleEntity(clazz) && managementContext.getEntityManager().getEntityTypeRegistry().getEntityTypeOf((Class)clazz) != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
    
    public static boolean isNewStyleEntity(Class<?> clazz) {
        if (!Entity.class.isAssignableFrom(clazz)) {
            throw new IllegalArgumentException("Class "+clazz+" is not an entity");
        }
        
        try {
            clazz.getConstructor(new Class[0]);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
    
    public InternalEntityFactory(ManagementContextInternal managementContext, EntityTypeRegistry entityTypeRegistry, InternalPolicyFactory policyFactory) {
        this.managementContext = checkNotNull(managementContext, "managementContext");
        this.entityTypeRegistry = checkNotNull(entityTypeRegistry, "entityTypeRegistry");
        this.policyFactory = checkNotNull(policyFactory, "policyFactory");
    }

    @SuppressWarnings("unchecked")
    public <T extends Entity> T createEntityProxy(EntitySpec<T> spec, T entity) {
        // TODO Don't want the proxy to have to implement EntityLocal, but required by how 
        // AbstractEntity.parent is used (e.g. parent.getAllConfig)
        ClassLoader classloader = (spec.getImplementation() != null ? spec.getImplementation() : spec.getType()).getClassLoader();
        MutableSet.Builder<Class<?>> builder = MutableSet.<Class<?>>builder()
                .add(EntityProxy.class, Entity.class, EntityLocal.class, EntityInternal.class);
        if (spec.getType().isInterface()) {
            builder.add(spec.getType());
        } else {
            log.warn("EntitySpec declared in terms of concrete type "+spec.getType()+"; should be supplied in terms of interface");
        }
        builder.addAll(spec.getAdditionalInterfaces());
        Set<Class<?>> interfaces = builder.build();
        
        return (T) java.lang.reflect.Proxy.newProxyInstance(
                classloader,
                interfaces.toArray(new Class[interfaces.size()]),
                new EntityProxyImpl(entity));
    }

    public <T extends Entity> T createEntity(EntitySpec<T> spec) {
        if (spec.getFlags().containsKey("parent") || spec.getFlags().containsKey("owner")) {
            throw new IllegalArgumentException("Spec's flags must not contain parent or owner; use spec.parent() instead for "+spec);
        }
        if (spec.getFlags().containsKey("id")) {
            throw new IllegalArgumentException("Spec's flags must not contain id; use spec.id() instead for "+spec);
        }
        if (spec.getId() != null && ((LocalEntityManager)managementContext.getEntityManager()).isKnownEntityId(spec.getId())) {
            throw new IllegalArgumentException("Entity with id "+spec.getId()+" already exists; cannot create new entity with this explicit id from spec "+spec);
        }
        
        try {
            Class<? extends T> clazz = getImplementedBy(spec);
            
            T entity = constructEntity(clazz, spec);
            
            // TODO Could move setManagementContext call into constructEntity; would that break rebind?
            if (spec.getId() != null) {
                FlagUtils.setFieldsFromFlags(ImmutableMap.of("id", spec.getId()), entity);
            }
            ((AbstractEntity)entity).setManagementContext(managementContext);
            managementContext.prePreManage(entity);

            initEntity(entity, spec);
            return entity;
            
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }
    
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public <T extends Entity> T initEntity(final T entity, final EntitySpec<T> spec) {
        try {
            if (spec.getDisplayName()!=null)
                ((AbstractEntity)entity).setDisplayName(spec.getDisplayName());
            
            if (((AbstractEntity)entity).getProxy() == null) ((AbstractEntity)entity).setProxy(createEntityProxy(spec, entity));
            ((AbstractEntity)entity).configure(MutableMap.copyOf(spec.getFlags()));
            
            for (Map.Entry<ConfigKey<?>, Object> entry : spec.getConfig().entrySet()) {
                ((EntityLocal)entity).setConfig((ConfigKey)entry.getKey(), entry.getValue());
            }
            ((AbstractEntity)entity).init();
            for (EntityInitializer initializer: spec.getInitializers())
                initializer.apply((EntityInternal)entity);
            
            ((EntityInternal)entity).getExecutionContext().submit(MutableMap.of(), new Runnable() {
                @Override
                public void run() {
                    for (Enricher enricher : spec.getEnrichers()) {
                        entity.addEnricher(enricher);
                    }
                    
                    for (EnricherSpec<?> enricherSpec : spec.getEnricherSpecs()) {
                        entity.addEnricher(policyFactory.createEnricher(enricherSpec));
                    }
                    
                    for (Policy policy : spec.getPolicies()) {
                        entity.addPolicy((AbstractPolicy)policy);
                    }
                    
                    for (PolicySpec<?> policySpec : spec.getPolicySpecs()) {
                        entity.addPolicy(policyFactory.createPolicy(policySpec));
                    }
                    
                    ((AbstractEntity)entity).addLocations(spec.getLocations());
                }
            }).get();
            
            Entity parent = spec.getParent();
            if (parent != null) {
                parent = (parent instanceof AbstractEntity) ? ((AbstractEntity)parent).getProxyIfAvailable() : parent;
                entity.setParent(parent);
            }
            return entity;
            
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }
    
    /**
     * Constructs an entity (if new-style, calls no-arg constructor; if old-style, uses spec to pass in config).
     */
    public <T extends Entity> T constructEntity(Class<? extends T> clazz, EntitySpec<T> spec) {
        try {
            FactoryConstructionTracker.setConstructing();
            try {
                if (isNewStyleEntity(clazz)) {
                    return clazz.newInstance();
                } else {
                    return constructOldStyle(clazz, MutableMap.copyOf(spec.getFlags()));
                }
            } finally {
                FactoryConstructionTracker.reset();
            }
        } catch (Exception e) {
            throw Exceptions.propagate(e);
         }
     }

    /**
     * Constructs a new-style entity (fails if no no-arg constructor).
     */
    public <T extends Entity> T constructEntity(Class<T> clazz) {
        try {
            FactoryConstructionTracker.setConstructing();
            try {
                if (isNewStyleEntity(clazz)) {
                    return clazz.newInstance();
                } else {
                    throw new IllegalStateException("Entity class "+clazz+" must have a no-arg constructor");
                }
            } finally {
                FactoryConstructionTracker.reset();
            }
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }
    
    private <T extends Entity> T constructOldStyle(Class<? extends T> clazz, Map<String,?> flags) throws InstantiationException, IllegalAccessException, InvocationTargetException {
        if (flags.containsKey("parent") || flags.containsKey("owner")) {
            throw new IllegalArgumentException("Spec's flags must not contain parent or owner; use spec.parent() instead for "+clazz);
        }
        Optional<? extends T> v = Reflections.invokeConstructorWithArgs(clazz, new Object[] {MutableMap.copyOf(flags)}, true);
        if (v.isPresent()) {
            return v.get();
        } else {
            throw new IllegalStateException("No valid constructor defined for "+clazz+" (expected no-arg or single java.util.Map argument)");
        }
    }
    
    private <T extends Entity> Class<? extends T> getImplementedBy(EntitySpec<T> spec) {
        if (spec.getImplementation() != null) {
            return spec.getImplementation();
        } else {
            return entityTypeRegistry.getImplementedBy(spec.getType());
        }
    }
}
