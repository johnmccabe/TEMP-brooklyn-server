package brooklyn.test.entity;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.Entity;
import brooklyn.entity.Group;
import brooklyn.entity.basic.AbstractApplication;
import brooklyn.event.Sensor;
import brooklyn.event.SensorEventListener;
import brooklyn.management.SubscriptionHandle;
import brooklyn.management.internal.LocalManagementContext;
import brooklyn.util.MutableMap;

/**
 * Mock application for testing.
 */
public class TestApplication extends AbstractApplication {
	protected static final Logger LOG = LoggerFactory.getLogger(TestApplication.class);

    public TestApplication() {
        this(MutableMap.of());
    }
    public TestApplication(Map properties) {
        super(properties);
    }

    public <T> SubscriptionHandle subscribeToMembers(Group parent, Sensor<T> sensor, SensorEventListener<? super T> listener) {
        return getSubscriptionContext().subscribeToMembers(parent, sensor, listener);
    }

    @Override
    public String toString() {
        String id = getId();
        return "Application["+id.substring(Math.max(0, id.length()-8))+"]";
    }

    /** convenience for wiring in management during testing */
    public void startManagement() {
        new LocalManagementContext().manage(this);
    }
    /** convenience for wiring in management during testing */
    public <T extends Entity> T manage(T entity) {
        getManagementSupport().getManagementContext(false).manage(entity);
        return entity;
    }
}
