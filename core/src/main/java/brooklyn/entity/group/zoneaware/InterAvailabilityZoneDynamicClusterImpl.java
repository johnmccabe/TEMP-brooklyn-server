package brooklyn.entity.group.zoneaware;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.Entity;
import brooklyn.entity.Group;
import brooklyn.entity.basic.BasicGroup;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.Lifecycle;
import brooklyn.entity.group.DynamicClusterImpl;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.entity.trait.Startable;
import brooklyn.location.Location;
import brooklyn.location.cloud.AvailabilityZoneExtension;
import brooklyn.management.Task;
import brooklyn.policy.Policy;
import brooklyn.util.collections.MutableList;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.text.StringPredicates;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

/**
 * A cluster of entities that can dynamically increase or decrease the number of entities.
 */
public class InterAvailabilityZoneDynamicClusterImpl extends DynamicClusterImpl implements InterAvailabilityZoneDynamicCluster {
    
    // FIXME Move out of brooklyn-locations-jclouds module; where to?
    
    private static final Logger LOG = LoggerFactory.getLogger(InterAvailabilityZoneDynamicClusterImpl.class);

    public InterAvailabilityZoneDynamicClusterImpl() {
    }
    
    @Override
    public void init() {
        super.init();
        setAttribute(SERVICE_UP, false);
    }
    
    @Override
    public void setPlacementStrategy(NodePlacementStrategy val) {
        setConfig(PLACEMENT_STRATEGY, checkNotNull(val, "placementStrategy"));
    }
    
    protected NodePlacementStrategy getPlacementStrategy() {
        return checkNotNull(getConfig(PLACEMENT_STRATEGY), "placementStrategy config");
    }
    
    @Override
    public void setZoneFailureDetector(ZoneFailureDetector val) {
        setConfig(ZONE_FAILURE_DETECTOR, checkNotNull(val, "zoneFailureDetector"));
    }
    
    protected ZoneFailureDetector getZoneFailureDetector() {
        return checkNotNull(getConfig(ZONE_FAILURE_DETECTOR), "zoneFailureDetector config");
    }
    
    @Override
    public void start(Collection<? extends Location> locs) {
        checkNotNull(locs, "Null location supplied to start %s", this);
        checkArgument(locs.size() == 1, "Wrong number of locations supplied to start %s: %s", this, locs);
        addLocations(locs);
        
        setAttribute(SUB_LOCATIONS, findSubLocations(Iterables.getOnlyElement(locs)));
        
        setAttribute(SERVICE_STATE, Lifecycle.STARTING);
        
        if (isQuarantineEnabled()) {
            Group quarantineGroup = addChild(EntitySpec.create(BasicGroup.class).displayName("quarantine"));
            Entities.manage(quarantineGroup);
            setAttribute(QUARANTINE_GROUP, quarantineGroup);
        }
        
        int initialSize = getConfig(INITIAL_SIZE).intValue();
        int initialQuorumSize = getInitialQuorumSize();
        
        resize(initialSize);
        
        int currentSize = getCurrentSize().intValue();
        if (currentSize < initialQuorumSize) {
            throw new IllegalStateException("On start of cluster "+this+", failed to get to initial size of "+initialSize+"; size is "+getCurrentSize()+
                    (initialQuorumSize != initialSize ? " (initial quorum size is "+initialQuorumSize+")" : ""));
        } else if (currentSize < initialSize) {
            LOG.warn("On start of cluster {}, size {} reached initial minimum quorum size of {} but did not reach desired size {}; continuing", 
                    new Object[] {this, currentSize, initialQuorumSize, initialSize});
        }
        
        for (Policy it : getPolicies()) { it.resume(); }
        setAttribute(SERVICE_STATE, Lifecycle.RUNNING);
        setAttribute(SERVICE_UP, calculateServiceUp());
    }

    protected List<Location> findSubLocations(Location loc) {
        Collection<String> zoneNames = getConfig(AVAILABILITY_ZONE_NAMES);
        Integer numZones = getConfig(NUM_AVAILABILITY_ZONES);
        
        if (loc.hasExtension(AvailabilityZoneExtension.class)) {
            AvailabilityZoneExtension zoneExtension = loc.getExtension(AvailabilityZoneExtension.class);
            List<Location> subLocations;
            
            if (zoneNames == null || zoneNames.isEmpty()) {
                checkArgument(numZones > 0, "numZones must be greater than zero: %s", numZones);
                subLocations = zoneExtension.getSubLocations(numZones);
                
                if (numZones > subLocations.size()) {
                    throw new IllegalStateException("Number of required zones ("+numZones+") not satisfied in "+loc+
                            "; only "+subLocations.size()+" available: "+subLocations);
                }
            } else {
                // TODO check that these are valid region / availabilityZones?
                subLocations = zoneExtension.getSubLocationsByName(StringPredicates.equalToAny(zoneNames), zoneNames.size());

                if (zoneNames.size() > subLocations.size()) {
                    throw new IllegalStateException("Number of required zones ("+zoneNames.size()+" - "+zoneNames+") not satisfied in "+loc+
                            "; only "+subLocations.size()+" available: "+subLocations);
                }
            }
            
            return subLocations;
            
        } else {
            throw new IllegalStateException("Availability zones (extension "+AvailabilityZoneExtension.class+") not supported for location "+loc);
        }
    }

    @Override
    public void stop() {
        setAttribute(SERVICE_STATE, Lifecycle.STOPPING);
        setAttribute(SERVICE_UP, calculateServiceUp());
        for (Policy it : getPolicies()) { it.suspend(); }
        resize(0);
        setAttribute(SERVICE_STATE, Lifecycle.STOPPED);
        setAttribute(SERVICE_UP, calculateServiceUp());
    }

    @Override
    public void restart() {
        throw new UnsupportedOperationException();
    }
    
    @Override
    public Integer resize(Integer desiredSize) {
        synchronized (mutex) {
            int currentSize = getCurrentSize();
            int delta = desiredSize - currentSize;
            if (delta != 0) {
                LOG.info("Resize {} from {} to {}", new Object[] {this, currentSize, desiredSize});
            } else {
                if (LOG.isDebugEnabled()) LOG.debug("Resize no-op {} from {} to {}", new Object[] {this, currentSize, desiredSize});
            }
    
            if (delta > 0) {
                grow(delta);
            } else if (delta < 0) {
                shrink(delta);
            }
        }
        return getCurrentSize();
    }

    @Override
    public String replaceMember(String memberId) {
        Entity member = getEntityManager().getEntity(memberId);
        LOG.info("In {}, replacing member {} ({})", new Object[] {this, memberId, member});

        if (member == null) {
            throw new NoSuchElementException("In "+this+", entity "+memberId+" cannot be resolved, so not replacing");
        }

        synchronized (mutex) {
            if (!getMembers().contains(member)) {
                throw new NoSuchElementException("In "+this+", entity "+member+" is not a member so not replacing");
            }
            
            Location memberLoc = checkNotNull(Iterables.getOnlyElement(member.getLocations()), "member's location (%s)", member);
            Collection<Entity> addedEntities = grow(memberLoc, 1);
            if (addedEntities.size() < 1) {
                String msg = String.format("In %s, failed to grow, to replace %s; not removing", this, member);
                throw new IllegalStateException(msg);
            }
            
            stopAndRemoveNode(member);
            
            return Iterables.get(addedEntities, 0).getId();
        }
    }

    protected Multimap<Location, Entity> getMembersByLocation() {
        Multimap<Location, Entity> result = LinkedHashMultimap.create();
        for (Entity member : getMembers()) {
            Collection<Location> memberLocs = member.getLocations();
            Location memberLoc = Iterables.getFirst(memberLocs, null);
            if (memberLoc != null) {
                result.put(memberLoc, member);
            }
        }
        return result;
    }
    
    protected List<Location> getNonFailedSubLocations() {
        List<Location> result = Lists.newArrayList();
        Set<Location> failed = Sets.newLinkedHashSet();
        List<Location> subLocations = getAttribute(SUB_LOCATIONS);
        Set<Location> oldFailedSubLocations = getAttribute(FAILED_SUB_LOCATIONS);
        if (oldFailedSubLocations == null) oldFailedSubLocations = ImmutableSet.<Location>of();
        
        for (Location subLocation : subLocations) {
            if (getZoneFailureDetector().hasFailed(subLocation)) {
                failed.add(subLocation);
            } else {
                result.add(subLocation);
            }
        }
        
        Set<Location> newlyFailed = Sets.difference(failed, oldFailedSubLocations);
        Set<Location> newlyRecovered = Sets.difference(oldFailedSubLocations, failed);
        setAttribute(FAILED_SUB_LOCATIONS, failed);
        if (newlyFailed.size() > 0) {
            LOG.warn("Detected probably zone failures for {}: {}", this, newlyFailed);
        }
        if (newlyRecovered.size() > 0) {
            LOG.warn("Detected probably zone recoveries for {}: {}", this, newlyRecovered);
        }
        
        return result;
    }
    
    /**
     * Increases the cluster size by the given number. Returns successfully added nodes.
     * Called when synchronized on mutex, so overriders beware!
     */
    @Override
    protected Collection<Entity> grow(int delta) {
        List<Location> subLocations = getNonFailedSubLocations();
        Multimap<Location, Entity> membersByLocation = getMembersByLocation();
        List<Location> chosenLocations = getPlacementStrategy().locationsForAdditions(membersByLocation, subLocations, delta);
        if (chosenLocations.size() != delta) {
            throw new IllegalStateException("Node placement strategy chose "+Iterables.size(chosenLocations)+", when expected delta "+delta+" in "+this);
        }
        
        List<Entity> addedEntities = Lists.newArrayList();
        Map<Entity, Location> addedEntityLocations = Maps.newLinkedHashMap();
        Map<Entity, Task<?>> tasks = Maps.newLinkedHashMap();
        for (Location chosenLocation : chosenLocations) {
            Entity entity = addNode(chosenLocation);
            addedEntities.add(entity);
            addedEntityLocations.put(entity, chosenLocation);
            Map<String,?> args = ImmutableMap.of("locations", ImmutableList.of(chosenLocation));
            tasks.put(entity, entity.invoke(Startable.START, args));
        }
        Map<Entity, Throwable> errors = waitForTasksOnEntityStart(tasks);
        
        for (Map.Entry<Entity, Location> entry : addedEntityLocations.entrySet()) {
            Entity entity = entry.getKey();
            Location loc = entry.getValue();
            Throwable err = errors.get(entity);
            if (err == null) {
                getZoneFailureDetector().onStartupSuccess(loc, entity);
            } else {
                getZoneFailureDetector().onStartupFailure(loc, entity, err);
            }
        }
        
        if (!errors.isEmpty()) {
            if (isQuarantineEnabled()) {
                quarantineFailedNodes(errors.keySet());
            } else {
                cleanupFailedNodes(errors.keySet());
            }
        }
        
        return MutableList.<Entity>builder().addAll(addedEntities).removeAll(errors.keySet()).build();
    }
    
    /**
     * Increases the cluster size by the given number. Returns successfully added nodes.
     * Called when synchronized on mutex, so overriders beware!
     */
    protected Collection<Entity> grow(Location subLocation, int delta) {
        // TODO remove duplication from #grow(int)
        Collection<Entity> addedEntities = Lists.newArrayList();
        Map<Entity, Task<?>> tasks = Maps.newLinkedHashMap();
        for (int i = 0; i < delta; i++) {
            Entity entity = addNode(subLocation);
            addedEntities.add(entity);
            Map<String,?> args = ImmutableMap.of("locations", ImmutableList.of(subLocation));
            tasks.put(entity, entity.invoke(Startable.START, args));
        }
        Map<Entity, Throwable> errors = waitForTasksOnEntityStart(tasks);
        
        if (!errors.isEmpty()) {
            if (isQuarantineEnabled()) {
                quarantineFailedNodes(errors.keySet());
            } else {
                cleanupFailedNodes(errors.keySet());
            }
        }
        
        return MutableList.<Entity>builder().addAll(addedEntities).removeAll(errors.keySet()).build();
    }
    
    @Override
    protected void shrink(int delta) {
        Collection<Entity> removedEntities = Lists.newArrayList();
        
        for (int i = 0; i < (delta*-1); i++) { removedEntities.add(pickAndRemoveMember()); }

        // FIXME symmetry in order of added as child, managed, started, and added to group
        // FIXME assume stoppable; use logic of grow?
        Task<?> invoke = Entities.invokeEffector(this, removedEntities, Startable.STOP, Collections.<String,Object>emptyMap());
        try {
            invoke.get();
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        } finally {
            for (Entity removedEntity : removedEntities) {
                discardNode(removedEntity);
            }
        }
    }
    
    @Override
    protected Entity pickAndRemoveMember() {
        // TODO inefficient impl
        Preconditions.checkState(getMembers().size() > 0, "Attempt to remove a node when members is empty, from cluster "+this);
        if (LOG.isDebugEnabled()) LOG.debug("Removing a node from {}", this);
        
        Multimap<Location, Entity> membersByLocation = getMembersByLocation();
        List<Entity> entities = getPlacementStrategy().entitiesToRemove(membersByLocation, 1);
        Preconditions.checkNotNull(entities, "No entity chosen for removal from %s (null returned)", getId());
        Preconditions.checkState(entities.size() == 1, "Incorrect num entity chosen for removal from %s (%s when expected %s)", getId(), entities.size(), 1);
        Preconditions.checkState(entities.get(0) instanceof Startable, "Chosen entity for removal not stoppable: cluster=%s; choice=%s", this, entities);
        
        Entity entity = entities.get(0);
        removeMember(entity);
        return entity;
    }
}
