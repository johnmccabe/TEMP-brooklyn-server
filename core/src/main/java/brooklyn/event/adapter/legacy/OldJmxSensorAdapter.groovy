package brooklyn.event.adapter.legacy

import java.util.concurrent.atomic.AtomicBoolean

import javax.management.JMX
import javax.management.MBeanServerConnection
import javax.management.Notification
import javax.management.NotificationListener
import javax.management.ObjectInstance
import javax.management.ObjectName
import javax.management.openmbean.CompositeData
import javax.management.openmbean.TabularData
import javax.management.openmbean.TabularDataSupport
import javax.management.remote.JMXConnector
import javax.management.remote.JMXConnectorFactory
import javax.management.remote.JMXServiceURL

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import brooklyn.entity.basic.Attributes
import brooklyn.entity.basic.EntityLocal
import brooklyn.event.basic.BasicNotificationSensor

import com.google.common.base.Preconditions


/**
 * This class adapts JMX {@link ObjectName} data to {@link brooklyn.event.Sensor} data
 * for a particular {@link brooklyn.entity.Entity}, updating the {@link Activity} as required.
 * 
 * The adapter normally polls the JMX server every second to update sensors, which could involve aggregation of data
 * or simply reading values and setting them in the attribute map of the activity model.
 */
@Deprecated
public class OldJmxSensorAdapter {
    private static final Logger log = LoggerFactory.getLogger(OldJmxSensorAdapter.class);

    public static final String JMX_URL_FORMAT = "service:jmx:rmi:///jndi/rmi://%s:%d/%s"
    public static final String RMI_JMX_URL_FORMAT = "service:jmx:rmi://%s:%d/jndi/rmi://%s:%d/%s"

    private static final Map<String,String> CLASSES = [
        "Integer" : Integer.TYPE.name,
        "Long" : Long.TYPE.name,
        "Boolean" : Boolean.TYPE.name,
        "Byte" : Byte.TYPE.name,
        "Character" : Character.TYPE.name,
        "Double" : Double.TYPE.name,
        "Float" : Float.TYPE.name,
        "GStringImpl" : String.class.getName(),
        "LinkedHashMap" : Map.class.getName(),
        "TreeMap" : Map.class.getName(),
        "HashMap" : Map.class.getName(),
        "ConcurrentHashMap" : Map.class.getName(),
        "TabularDataSupport" : TabularData.class.getName(),
        "CompositeDataSupport" : CompositeData.class.getName(),
    ]

    final EntityLocal entity
    final String host
    final Integer rmiRegistryPort
    final Integer rmiServerPort
    final String context
    final String url

    JMXConnector jmxc
    MBeanServerConnection mbsc

    public OldJmxSensorAdapter(EntityLocal entity, long timeout = -1) {
        this.entity = entity

        host = entity.getAttribute(Attributes.HOSTNAME);
        rmiRegistryPort = entity.getAttribute(Attributes.JMX_PORT);
        rmiServerPort = entity.getAttribute(Attributes.RMI_PORT);
        context = entity.getAttribute(Attributes.JMX_CONTEXT);

        if (rmiServerPort) {
            url = String.format(RMI_JMX_URL_FORMAT, host, rmiServerPort, host, rmiRegistryPort, context)
        } else {
            url = String.format(JMX_URL_FORMAT, host, rmiRegistryPort, context)
        }

        if (!connect(timeout)) throw new IllegalStateException("Could not connect to JMX service on ${host}:${rmiRegistryPort}")
    }

    public <T> ValueProvider<T> newAttributeProvider(String objectName, String attribute) {
        try {
            return new JmxAttributeProvider(this, new ObjectName(objectName), attribute)
        } catch (Exception e) {
            log.warn "error creating JMX object '"+objectName+"', $attribute."
            throw e;
        }
    }

    public <T> ValueProvider<T> newOperationProvider(String objectName, String method, Object...arguments) {
        return new JmxOperationProvider(this, new ObjectName(objectName), method, arguments)
    }

    public ValueProvider<HashMap> newTabularDataProvider(String objectName, String attribute) {
        return new JmxTabularDataProvider(this, new ObjectName(objectName), attribute)
    }

    public JmxAttributeNotifier newAttributeNotifier(String objectName, EntityLocal entity, BasicNotificationSensor sensor) {
        return new JmxAttributeNotifier(this, new ObjectName(objectName), entity, sensor)
    }

    public boolean isConnected() {
        return (jmxc && mbsc);
    }

    /** attempts to connect immediately */
    public void connect() throws IOException {
        if (jmxc) jmxc.close()
        JMXServiceURL url = new JMXServiceURL(url)
        Hashtable env = new Hashtable();
        String user = entity.getAttribute(Attributes.JMX_USER);
        String password = entity.getAttribute(Attributes.JMX_PASSWORD);
        if (user && password) {
            String[] creds = [ user, password ]
            env.put(JMXConnector.CREDENTIALS, creds);
        }
        jmxc = JMXConnectorFactory.connect(url, env);
        mbsc = jmxc.getMBeanServerConnection();
    }

    /** continuously attempts to connect (blocking), for at least the indicated amount of time; or indefinitely if -1 */
    public boolean connect(long timeout) {
        if (log.isDebugEnabled()) log.debug "Connecting to JMX URL: {} ({})", url, ((timeout == -1) ? "indefinitely" : "${timeout}ms timeout")
        long start = System.currentTimeMillis()
        long end = start + timeout
        if (timeout == -1) end = Long.MAX_VALUE
        Throwable lastError;
        while (start <= end) {
            start = System.currentTimeMillis()
            if (log.isDebugEnabled()) log.debug "trying connection to {}:{} at {}", host, rmiRegistryPort, start
            try {
                connect()
                return true
            } catch (IOException e) {
                if (log.isDebugEnabled()) log.debug "failed connection (io) to {}:{} ({})", host, rmiRegistryPort, e.message
                lastError = e;
            } catch (SecurityException e) {
                if (lastError==null) {
                    log.warn "failed connection (security) to {}:{}, will retry ({})", host, rmiRegistryPort, e.message
                    //maybe just throw? a security exception is likely definitive, retry probably won't help
                    //(but maybe it will?)
                } else {
                    if (log.isDebugEnabled()) log.debug "failed connection (security) to {}:{} ({})", host, rmiRegistryPort, e.message
                }
                lastError = e;
            }
        }
        log.warn("unable to connect to JMX url: ${url}", lastError);
        false
    }

    public void disconnect() {
        if (jmxc) {
            try {
                jmxc.close()
            } catch (Exception e) {
                log.warn("Caught exception disconnecting from JMX at {}:{}, {}", host, rmiRegistryPort, e.message)
            } finally {
                jmxc = null
                mbsc = null
            }
        }
    }

    public void checkConnected() {
        if (!isConnected()) throw new IllegalStateException("Not connected to JMX for entity $entity")
    }

    public ObjectInstance findMBean(ObjectName objectName) {
        Set<ObjectInstance> beans = mbsc.queryMBeans(objectName, null)
        if (beans.isEmpty() || beans.size() > 1) {
            log.warn "JMX object name query returned {} values for {}", beans.size(), objectName.canonicalName
            return null
        }
        ObjectInstance bean = beans.find { true }
        return bean
    }

    private Object getAttributeInternal(ObjectName objectName, String attribute) throws Exception {
        ObjectInstance bean = findMBean objectName
        if (bean != null) {
            try {
                def result = mbsc.getAttribute(bean.objectName, attribute)
                if (log.isTraceEnabled()) log.trace "got value {} for jmx attribute {}.{}", result, objectName.canonicalName, attribute
                return result
            } catch (Exception e) {
                log.warn "error getting $attribute from ${bean.objectName} with $mbsc: $e (rethrowing)"
                throw e
            }
        } else {
            return null
        }
    }
    
    /**
     * Returns a specific attribute for a JMX {@link ObjectName}.
     */
    public Object getAttribute(ObjectName objectName, String attribute) {
        checkConnected()
        try {
            return getAttributeInternal(objectName, attribute);
        } catch (IOException e) {
            //allow 1 retry after a reconnection, in case jmx connection has been dropped
            //(better would be to put that logic in the jmxc but this is a quick way to try)
            if (e.toString().contains("The client has been closed.")) {
                if (tryReconnect("detected client close event")) {
                    return getAttributeInternal(objectName, attribute);
                }
            }
            throw e;
        }
    }

    long lastReconnect = -1;
    AtomicBoolean reconnectSuccess = new AtomicBoolean(true);

    public boolean tryReconnect(String msg) {
        if (!reconnectSuccess.get() && lastReconnect > System.currentTimeMillis()-10000)
            //reconnect failed within past 10s, don't retry
            return false;
            
        synchronized (reconnectSuccess) {
            if (System.currentTimeMillis()-lastReconnect < 3000) {
                //there was a reconnect attempt within the last 3s, use its result
                return reconnectSuccess.get();
            }
            lastReconnect = System.currentTimeMillis();
            log.info "reconnecting "+this+" ("+entity+"): "+msg
            try {
                disconnect();
                connect();
                log.info "reconnecting "+this+" ("+entity+"): success"
                reconnectSuccess.set(true);
            } catch (Exception e) {
                log.info "reconnecting "+this+" ("+entity+"): failure, "+e
                reconnectSuccess.set(false);
            }
        }
    }

    /** @see #operation(ObjectName, String, Object...) */
    public Object operation(String objectName, String method, Object...arguments) {
        return operation(new ObjectName(objectName), method, arguments)
    }

    /**
     * Executes an operation on a JMX {@link ObjectName}.
     */
    public Object operation(ObjectName objectName, String method, Object...arguments) {
        checkConnected()

        ObjectInstance bean = findMBean objectName
        String[] signature = new String[arguments.length]
        arguments.eachWithIndex { arg, int index ->
            Class clazz = arg.getClass()
            signature[index] = (CLASSES.containsKey(clazz.simpleName) ? CLASSES.get(clazz.simpleName) : clazz.name)
        }
        def result = mbsc.invoke(objectName, method, arguments, signature)
        if (log.isTraceEnabled()) log.trace "got result {} for jmx operation {}.{}", result, objectName.canonicalName, method
        return result
    }

    public void addNotification(String objectName, NotificationListener listener) {
        addNotification(new ObjectName(objectName), listener)
    }

    public void addNotification(ObjectName objectName, NotificationListener listener) {
        ObjectInstance bean = findMBean objectName
        mbsc.addNotificationListener(objectName, listener, null, null)
    }

    public <M> M getProxyObject(String objectName, Class<M> mbeanInterface) {
        return getProxyObject(new ObjectName(objectName), mbeanInterface)
    }

    public <M> M getProxyObject(ObjectName objectName, Class<M> mbeanInterface) {
        return JMX.newMBeanProxy(mbsc, objectName, mbeanInterface, false)
    }
}

/**
 * Provides JMX attribute values to a sensor.
 */
public class JmxAttributeProvider<T> implements ValueProvider<T> {
    private final OldJmxSensorAdapter adapter
    private final ObjectName objectName
    private final String attribute

    public JmxAttributeProvider(OldJmxSensorAdapter adapter, ObjectName objectName, String attribute) {
        this.adapter = Preconditions.checkNotNull(adapter, "adapter")
        this.objectName = Preconditions.checkNotNull(objectName, "object name")
        this.attribute = Preconditions.checkNotNull(attribute, "attribute")
    }

    public T compute() {
        return adapter.getAttribute(objectName, attribute)
    }
}

/**
 * Provides JMX operation results to a sensor.
 */
public class JmxOperationProvider<T> implements ValueProvider<T> {
    private final OldJmxSensorAdapter adapter
    private final ObjectName objectName
    private final String method
    private final Object[] arguments

    public JmxOperationProvider(OldJmxSensorAdapter adapter, ObjectName objectName, String method, Object...arguments) {
        this.adapter = Preconditions.checkNotNull(adapter, "adapter")
        this.objectName = Preconditions.checkNotNull(objectName, "object name")
        this.method = Preconditions.checkNotNull(method, "method")
        this.arguments = arguments
    }

    public T compute() {
        return adapter.operation(objectName, method, arguments)
    }
}

public class JmxTabularDataProvider implements ValueProvider<Map<String, Object>> {

    private static final Logger log = LoggerFactory.getLogger(JmxTabularDataProvider.class);

    private final OldJmxSensorAdapter adapter
    private final ObjectName objectName
    private final String attribute

    public JmxTabularDataProvider(OldJmxSensorAdapter adapter, ObjectName objectName, String attribute) {
        this.adapter = Preconditions.checkNotNull(adapter, "adapter")
        this.objectName = Preconditions.checkNotNull(objectName, "object name")
        this.attribute = Preconditions.checkNotNull(attribute, "attribute")
    }

    public Map<String, Object> compute() {
        HashMap<String, Object> out = []
        Object attr = adapter.getAttribute(objectName, attribute)
        TabularDataSupport table
        try {
            table = (TabularDataSupport) attr;
        } catch (ClassCastException e) {
            log.error "($objectName, '$attribute') gave instance of ${attr.getClass()}, expected ${TabularDataSupport.class}"
            throw e
        }
        for (Object entry : table.values()) {
            CompositeData data = (CompositeData) entry //.getValue()
            data.getCompositeType().keySet().each { String key ->
                def old = out.put(key, data.get(key))
                if (old) {
                    log.warn "JmxTabularDataProvider has overwritten key {}", key
                }
            }
        }
        return out
    }
}

/**
 * Provides JMX attribute values to a sensor.
 */
public class JmxAttributeNotifier implements NotificationListener {
    private static final Logger log = LoggerFactory.getLogger(JmxAttributeNotifier.class);

    private final OldJmxSensorAdapter adapter
    private final ObjectName objectName
    private final EntityLocal entity
    private final BasicNotificationSensor sensor

    public JmxAttributeNotifier(OldJmxSensorAdapter adapter, ObjectName objectName, EntityLocal entity, BasicNotificationSensor sensor) {
        this.adapter = Preconditions.checkNotNull(adapter, "adapter")
        this.objectName = Preconditions.checkNotNull(objectName, "object name")
        this.entity = Preconditions.checkNotNull(entity, "entity")
        this.sensor = Preconditions.checkNotNull(sensor, "sensor")

        adapter.addNotification(objectName, this)
    }

    public void handleNotification(Notification notification, Object handback) {
        if (log.isDebugEnabled()) log.debug "Got notification type {}: {} (sequence {})", notification.type, notification.message, notification.sequenceNumber
        if (notification.type == sensor.name) {
            entity.emit(sensor, notification.userData)
        }
    }
}
