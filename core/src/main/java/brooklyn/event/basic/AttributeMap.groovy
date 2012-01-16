package brooklyn.event.basic

import java.util.concurrent.ConcurrentHashMap

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import brooklyn.entity.basic.EntityLocal
import brooklyn.event.AttributeSensor
import brooklyn.event.Sensor

import com.google.common.base.Preconditions

/**
 * A {@link Map} of {@link Entity} attribute values.
 */
public class AttributeMap implements Serializable {
    static final Logger log = LoggerFactory.getLogger(AttributeMap.class)
 
    EntityLocal entity;
    
    /**
     * The values are stored as nested maps, with the key being the constituent parts of the sensor.
     */
    // Note that we synchronize on the top-level map, to handle concurrent updates and and gets (ENGR-2111)
    Map values = [:];
    
    public AttributeMap(EntityLocal entity) {
        this.entity = entity;
    }
    
    public Map asMap() { values }
        
    /** returns oldValue */
    public <T> T update(Collection<String> path, T newValue) {
        Map map
        String key
        synchronized (values) {
            Object val = values
            path.each { 
                if (!(val in Map)) {
                    if (val!=null)
                        log.debug "wrong type of value encountered when setting $path; expected map at $key but found $val; overwriting"
                    val = [:]
                    map.put(key, val)
                }
                map = val
                key = it
                val = map.get(it)
            }
            log.trace "putting at $path=$newValue for $entity"
            def oldValue = map.put(key, newValue)
            return oldValue
        }
    }
    
    public <T> void update(Sensor<T> sensor, T newValue) {
        Preconditions.checkArgument(sensor in AttributeSensor, "AttributeMap can only update an attribute sensor's value, not %s", sensor)
        def oldValue = update(sensor.getNameParts(), newValue)
        log.trace "sensor {} set to {}", sensor.name, newValue
        entity.emitInternal sensor, newValue
        oldValue
    }
    
    public Object getValue(Collection<String> path) {
        synchronized (values) {
            return getValueRecurse(values, path)
        }
    }
    
    public <T> T getValue(Sensor<T> sensor) {
        synchronized (values) {
            return getValueRecurse(values, sensor.getNameParts())
        }
    }

    private static Object getValueRecurse(Map node, Collection<String> path) {
        if (node==null) return null
        Preconditions.checkArgument(!path.isEmpty(), "field name is empty")
        
        def key = path[0]
        Preconditions.checkState(key != null, "head of path is null")
        Preconditions.checkState(key.length() > 0, "head of path is empty")
        
        def child = node.get(key)
        Preconditions.checkState(child != null || path.size() > 0, "node $key is not present")
        
        if (path.size() > 1)
            return getValueRecurse(child, path[1..-1])
        else
            return child
    }
    
    //ENGR-1458  interesting to use property change. if it works great.
    //if there are any issues with it consider instead just making attributesInternal private,
    //and forcing all changes to attributesInternal to go through update(AttributeSensor,...)
    //and do the publishing there...  (please leave this comment here for several months until we know... it's Jun 2011 right now)
//    protected final PropertiesSensorAdapter propertiesAdapter = new PropertiesSensorAdapter(this, attributes)
    //if wee need this, fold the capabilities into this class.
}
