package brooklyn.policy.followthesun;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.location.Location;
import brooklyn.location.basic.AbstractLocation;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

public class DefaultFollowTheSunModel<LocationType, ContainerType, ItemType> implements FollowTheSunModel<LocationType, ContainerType, ItemType> {
    
    private static final Logger LOG = LoggerFactory.getLogger(DefaultFollowTheSunModel.class);
    
    // Concurrent maps cannot have null value; use this to represent when no container is supplied for an item 
    private static final String NULL = "null-val";
    private static final Location NULL_LOCATION = new AbstractLocation(newHashMap("name","null-location")) {};
    
    private final String name;
    private final Set<ContainerType> containers = Collections.newSetFromMap(new ConcurrentHashMap<ContainerType,Boolean>());
    private final Map<ItemType, ContainerType> itemToContainer = new ConcurrentHashMap<ItemType, ContainerType>();
    private final Map<ContainerType, LocationType> containerToLocation = new ConcurrentHashMap<ContainerType, LocationType>();
    private final Map<ItemType, LocationType> itemToLocation = new ConcurrentHashMap<ItemType, LocationType>();
    public Map<ItemType, Map<? extends ItemType, Double>> itemUsage = new ConcurrentHashMap<ItemType, Map<? extends ItemType,Double>>();

    public DefaultFollowTheSunModel(String name) {
        this.name = name;
    }

    @Override
    public Set<ItemType> getItems() {
        return itemToContainer.keySet();
    }
    
    @Override
    public ContainerType getItemContainer(ItemType item) {
        ContainerType result = itemToContainer.get(item);
        return (isNull(result) ? null : result);
    }
    
    @Override
    public LocationType getItemLocation(ItemType item) {
        LocationType result = itemToLocation.get(item);
        return (isNull(result) ? null : result);
    }
    
    @Override
    public LocationType getContainerLocation(ContainerType container) {
        LocationType result = containerToLocation.get(container);
        return (isNull(result) ? null : result);
    }
    
    // Provider methods.
    
    @Override public String getName() {
        return name;
    }
    
    // TODO: delete?
    @Override public String getName(ItemType item) {
        return item.toString();
    }
    
    @Override public boolean isItemMoveable(ItemType item) {
        return true; // TODO?
    }
    
    @Override public boolean isItemAllowedIn(ItemType item, LocationType location) {
        return true; // TODO?
    }
    
    @Override public boolean hasActiveMigration(ItemType item) {
        return false; // TODO?
    }
    
    @Override
    // FIXME Too expensive to compute; store in a different data structure?
    public Map<ItemType, Map<LocationType, Double>> getDirectSendsToItemByLocation() {
        Map<ItemType, Map<LocationType, Double>> result = new LinkedHashMap<ItemType, Map<LocationType,Double>>(getNumItems());
        
        for (Map.Entry<ItemType, Map<? extends ItemType, Double>> entry : itemUsage.entrySet()) {
            ItemType targetItem = entry.getKey();
            Map<? extends ItemType, Double> sources = entry.getValue();
            if (sources.isEmpty()) continue; // no-one talking to us
            
            Map<LocationType, Double> targetUsageByLocation = new LinkedHashMap<LocationType, Double>();
            result.put(targetItem, targetUsageByLocation);

            for (Map.Entry<? extends ItemType, Double> entry2 : sources.entrySet()) {
                ItemType sourceItem = entry2.getKey();
                LocationType sourceLocation = getItemLocation(sourceItem);
                double usageVal = (entry.getValue() != null) ? entry2.getValue() : 0d;
                if (sourceItem.equals(targetItem)) continue; // ignore msgs to self
                
                Double usageValTotal = targetUsageByLocation.get(sourceLocation);
                double newUsageValTotal = (usageValTotal != null ? usageValTotal : 0d) + usageVal;
                targetUsageByLocation.put(sourceLocation, newUsageValTotal);
            }
        }
        
        return result;
    }
    
    @Override
    public Set<ContainerType> getAvailableContainersFor(ItemType item, LocationType location) {
        return getContainersInLocation(location);
    }


    // Mutators.
    
    @Override
    public void onItemMoved(ItemType item, ContainerType newContainer) {
        // idempotent, as may be called multiple times
        LocationType newLocation = (newContainer != null) ? containerToLocation.get(newContainer) : null;
        ContainerType newContainerNonNull = toNonNullContainer(newContainer);
        LocationType newLocationNonNull = toNonNullLocation(newLocation);
        ContainerType oldContainer = itemToContainer.put(item, newContainerNonNull);
        LocationType oldLocation = itemToLocation.put(item, newLocationNonNull);
    }
    
    @Override
    public void onContainerAdded(ContainerType container, LocationType location) {
        LocationType locationNonNull = toNonNullLocation(location);
        containers.add(container);
        containerToLocation.put(container, locationNonNull);
        for (ItemType item : getItemsOnContainer(container)) {
            itemToLocation.put(item, locationNonNull);
        }
    }
    
    @Override
    public void onContainerRemoved(ContainerType container) {
        containers.remove(container);
        containerToLocation.remove(container);
    }
    
    public void onContainerLocationUpdated(ContainerType container, LocationType location) {
        if (!containers.contains(container)) {
            // unknown container; probably just stopped? 
            // If this overtook onContainerAdded, then assume we'll lookup the location and get it right in onContainerAdded
            LOG.debug("Ignoring setting of location for unknown container {}, to {}", container, location);
            return;
        }
        LocationType locationNonNull = toNonNullLocation(location);
        containerToLocation.put(container, locationNonNull);
        for (ItemType item : getItemsOnContainer(container)) {
            itemToLocation.put(item, locationNonNull);
        }
    }

    @Override
    public void onItemAdded(ItemType item, ContainerType container) {
        // idempotent, as may be called multiple times
        LocationType location = (container != null) ? containerToLocation.get(container) : null;
        ContainerType containerNonNull = toNonNullContainer(container);
        LocationType locationNonNull = toNonNullLocation(location);
        ContainerType oldContainer = itemToContainer.put(item, containerNonNull);
        LocationType oldLocation = itemToLocation.put(item, locationNonNull);
    }
    
    @Override
    public void onItemRemoved(ItemType item) {
        itemToContainer.remove(item);
        itemToLocation.remove(item);
        itemUsage.remove(item);
    }
    
    @Override
    public void onItemUsageUpdated(ItemType item, Map<? extends ItemType, Double> newValue) {
        itemUsage.put(item, newValue);
    }
    
    
    // Mutators that change the real world
    
    @Override public void moveItem(ItemType item, ContainerType newNode) {
        // TODO no-op; should this be abstract?
    }
    
    
    // Additional methods for tests.

    /**
     * Warning: this can be an expensive (time and memory) operation if there are a lot of items/containers. 
     */
    @VisibleForTesting
    public String itemDistributionToString() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        dumpItemDistribution(new PrintStream(baos));
        return new String(baos.toByteArray());
    }

    @VisibleForTesting
    public void dumpItemDistribution() {
        dumpItemDistribution(System.out);
    }
    
    @VisibleForTesting
    public void dumpItemDistribution(PrintStream out) {
        Map<ItemType, Map<LocationType, Double>> directSendsToItemByLocation = getDirectSendsToItemByLocation();
        
        out.println("Follow-The-Sun dump: ");
        for (LocationType location: getLocations()) {
            out.println("\t"+"Location "+location);
            for (ContainerType container : getContainersInLocation(location)) {
                out.println("\t\t"+"Container "+container);
                for (ItemType item : getItemsOnContainer(container)) {
                    Map<LocationType, Double> inboundUsage = directSendsToItemByLocation.get(item);
                    Map<? extends ItemType, Double> outboundUsage = itemUsage.get(item);
                    out.println("\t\t\t"+"Item "+item);
                    out.println("\t\t\t\t"+"Inbound: "+inboundUsage);
                    out.println("\t\t\t\t"+"Outbound: "+outboundUsage);
                }
            }
        }
        out.flush();
    }
    
    private Set<LocationType> getLocations() {
        return ImmutableSet.copyOf(containerToLocation.values());
    }
    
    private Set<ContainerType> getContainersInLocation(LocationType location) {
        Set<ContainerType> result = new LinkedHashSet<ContainerType>();
        for (Map.Entry<ContainerType, LocationType> entry : containerToLocation.entrySet()) {
            if (location.equals(entry.getValue())) {
                result.add(entry.getKey());
            }
        }
        return result;
    }
    
    private Set<ItemType> getItemsOnContainer(ContainerType container) {
        Set<ItemType> result = new LinkedHashSet<ItemType>();
        for (Map.Entry<ItemType, ContainerType> entry : itemToContainer.entrySet()) {
            if (container.equals(entry.getValue())) {
                result.add(entry.getKey());
            }
        }
        return result;
    }
    
    private int getNumItems() {
        return itemToContainer.size();
    }
    
    @SuppressWarnings("unchecked")
    private ContainerType nullContainer() {
        return (ContainerType) NULL; // relies on erasure
    }
    
    @SuppressWarnings("unchecked")
    private LocationType nullLocation() {
        return (LocationType) NULL_LOCATION; // relies on erasure
    }
    
    private ContainerType toNonNullContainer(ContainerType val) {
        return (val != null) ? val : nullContainer();
    }
    
    private LocationType toNonNullLocation(LocationType val) {
        return (val != null) ? val : nullLocation();
    }
    
    private boolean isNull(Object val) {
        return val == NULL || val == NULL_LOCATION;
    }
    
    // TODO Move to utils; or stop AbstractLocation from removing things from the map!
    public static <K,V> Map<K,V> newHashMap(K k, V v) {
        Map<K,V> result = Maps.newLinkedHashMap();
        result.put(k, v);
        return result;
    }
}
