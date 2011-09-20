package brooklyn.entity.basic;

import java.util.Map;

import brooklyn.enricher.basic.AbstractEnricher;
import brooklyn.entity.ConfigKey;
import brooklyn.entity.Entity;
import brooklyn.event.AttributeSensor;
import brooklyn.event.Sensor;
import brooklyn.event.SensorEvent;
import brooklyn.event.SensorEventListener;
import brooklyn.management.ManagementContext;
import brooklyn.management.SubscriptionHandle;
import brooklyn.policy.basic.AbstractPolicy;

public interface EntityLocal extends Entity {
    
    // FIXME Rename to something other than EntityLocal.
    // Separate out what is specific to "local jvm", and what is here for an SPI rather than API.

    /**
     * Sets the {@link Sensor} data for the given attribute to the specified value.
     * 
     * This can be used to "enrich" the entity, such as adding aggregated information, 
     * rolling averages, etc.
     * 
     * @return the old value for the attribute (possibly <code>null</code>)
     */
    <T> T setAttribute(AttributeSensor<T> sensor, T val);
    
    // ??? = policy which detects a group is too hot and want the entity to fire a TOO_HOT event
    
    /**
     * Must be called before the entity is started.
     */
    <T> T setConfig(ConfigKey<T> key, T val);
    
    /**
     * Emits a {@link SensorEvent} event on behalf of this entity (as though produced by this entity).
     */
    <T> void emit(Sensor<T> sensor, T value);
    
    /**
     * Allow us to subscribe to data from a {@link Sensor} on another entity.
     * 
     * @return a subscription id which can be used to unsubscribe
     *
     * @see SubscriptionManger#subscribe(Map, Entity, Sensor, SensorEventListener)
     */
    // FIXME remove from interface?
    <T> SubscriptionHandle subscribe(Entity producer, Sensor<T> sensor, SensorEventListener<T> listener);
 
    /** @see SubscriptionManger#subscribeToChildren(Map, Entity, Sensor, SensorEventListener) */
    // FIXME remove from interface?
    <T> SubscriptionHandle subscribeToChildren(Entity parent, Sensor<T> sensor, SensorEventListener<T> listener);
 
    /**
     * Adds the given policy to this entity. Also calls policy.setEntity if available.
     */
    void addPolicy(AbstractPolicy policy);
    
    /**
     * Removes the given policy from this entity. 
     * @return True if the policy existed at this entity; false otherwise
     */
    boolean removePolicy(AbstractPolicy policy);
    
    /**
     * Adds the given enricher to this entity. Also calls enricher.setEntity if available.
     */
    void addEnricher(AbstractEnricher enricher);
    
    /**
     * Removes the given enricher from this entity. 
     * @return True if the policy enricher at this entity; false otherwise
     */
    boolean removeEnricher(AbstractEnricher enricher);
    
    ManagementContext getManagementContext();
}
