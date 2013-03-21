package brooklyn.entity;

import java.io.Serializable;
import java.util.Collection;
import java.util.Map;

import brooklyn.config.ConfigKey;
import brooklyn.entity.rebind.Rebindable;
import brooklyn.event.AttributeSensor;
import brooklyn.location.Location;
import brooklyn.management.Task;
import brooklyn.mementos.EntityMemento;
import brooklyn.policy.Enricher;
import brooklyn.policy.Policy;

/**
 * The basic interface for a Brooklyn entity.
 * 
 * Implementors of entities are strongly encouraged to extend {@link brooklyn.entity.basic.AbstractEntity}.
 * 
 * To instantiate an entity, see {@code managementContext.getEntityManager().createEntity(entitySpec)}.
 * 
 * Entities may not be {@link Serializable} in subsequent releases!
 * 
 * @see brooklyn.entity.basic.AbstractEntity
 */
public interface Entity extends Serializable, Rebindable<EntityMemento> {
    /**
     * The unique identifier for this entity.
     */
    String getId();
    
    /**
     * A display name; recommended to be a concise single-line description.
     */
    String getDisplayName();
    
    /**
     * Information about the type of this entity; analogous to Java's object.getClass.
     */
    EntityType getEntityType();
    
    /**
     * @return the {@link Application} this entity is registered with, or null if not registered.
     */
    Application getApplication();

    /**
     * @return the id of the {@link Application} this entity is registered with, or null if not registered.
     */
    String getApplicationId();

    /**
     * The parent of this entity, null if no parent.
     *
     * The parent is normally the entity responsible for creating/destroying/managing this entity.
     *
     * @see #setParent(Entity)
     * @see #clearParent
     */
    Entity getParent();
    
    /** 
     * Return the entities that are children of (i.e. "owned by") this entity
     */
    Collection<Entity> getChildren();
    
    /**
     * Sets the parent (i.e. "owner") of this entity. Returns this entity, for convenience.
     *
     * @see #getParent
     * @see #clearParent
     */
    Entity setParent(Entity parent);
    
    /**
     * Clears the parent (i.e. "owner") of this entity. Also cleans up any references within its parent entity.
     *
     * @see #getParent
     * @see #setParent
     */
    void clearParent();
    
    /** 
     * Add a child {@link Entity}, and set this entity as its parent,
     * returning the added child.
     * 
     * TODO Signature will change to {@code <T extends Entity> T addChild(T child)}, but
     * that currently breaks groovy AbstractEntity subclasses sometimes so deferring that
     * until (hopefully) the next release.
     */
    Entity addChild(Entity child);
    
    /** 
     * Removes the specified child {@link Entity}; its parent will be set to null.
     * 
     * @return True if the given entity was contained in the set of children
     */
    boolean removeChild(Entity child);
    
    /**
     * @deprecated since 0.5; see getParent()
     */
    @Deprecated
    Entity getOwner();

    /** 
     * @deprecated since 0.5; see getChildren()
     */
    @Deprecated
    Collection<Entity> getOwnedChildren();
    
    /**
     * @deprecated since 0.5; see setOwner(Entity)
     */
    @Deprecated
    Entity setOwner(Entity group);
    
    /**
     * @deprecated since 0.5; see clearParent()
     */
    @Deprecated
    void clearOwner();
    
    /** 
     * @deprecated since 0.5; see addChild(Entity)
     */
    @Deprecated
    Entity addOwnedChild(Entity child);
    
    /** 
     * @deprecated since 0.5; see removeChild(Entity)
     */
    @Deprecated
    boolean removeOwnedChild(Entity child);
    
    /**
     * @return an immutable thread-safe view of the policies.
     */
    Collection<Policy> getPolicies();
    
    /**
     * @return an immutable thread-safe view of the enrichers.
     */
    Collection<Enricher> getEnrichers();
    
    /**
     * The {@link Collection} of {@link Group}s that this entity is a member of.
     *
     * Groupings can be used to allow easy management/monitoring of a group of entities.
     */
    Collection<Group> getGroups();

    /**
     * Add this entity as a member of the given {@link Group}.
     */
    void addGroup(Group group);

    /**
     * Return all the {@link Location}s this entity is deployed to.
     */
    Collection<Location> getLocations();

    /**
     * Gets the value of the given attribute on this entity, or null if has not been set.
     *
     * Attributes can be things like workrate and status information, as well as
     * configuration (e.g. url/jmxHost/jmxPort), etc.
     */
    <T> T getAttribute(AttributeSensor<T> sensor);

    /**
     * Gets the given configuration value for this entity, which may be inherited from
     * its parent.
     */
    <T> T getConfig(ConfigKey<T> key);
    
    /**
     * Invokes the given effector, with the given parameters to that effector.
     */
    <T> Task<T> invoke(Effector<T> eff, Map<String,?> parameters);
    
    /**
     * Adds the given policy to this entity. Also calls policy.setEntity if available.
     */
    void addPolicy(Policy policy);
    
    /**
     * Removes the given policy from this entity. 
     * @return True if the policy existed at this entity; false otherwise
     */
    boolean removePolicy(Policy policy);
    
    /**
     * Adds the given enricher to this entity. Also calls enricher.setEntity if available.
     */
    void addEnricher(Enricher enricher);
    
    /**
     * Removes the given enricher from this entity. 
     * @return True if the policy enricher at this entity; false otherwise
     */
    boolean removeEnricher(Enricher enricher);
}
