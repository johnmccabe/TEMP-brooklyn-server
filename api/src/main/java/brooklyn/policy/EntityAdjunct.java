package brooklyn.policy;

import brooklyn.entity.trait.Identifiable;

/**
 * EntityAdjuncts are supplementary logic that can be attached to Entities, providing sensor enrichment
 * or enabling policy
 */
public interface EntityAdjunct extends Identifiable {
    /**
     * A unique id for this adjunct
     */
    @Override
    String getId();

    /**
     * Get the name assigned to this adjunct
     *
     * @return the name assigned to the adjunct
     */
    String getName();
    
    /**
     * Whether the adjunct is destroyed
     */
    boolean isDestroyed();
    
    /**
     * Whether the adjunct is available
     */
    boolean isRunning();
}
