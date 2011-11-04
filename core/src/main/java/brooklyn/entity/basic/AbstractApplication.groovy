package brooklyn.entity.basic

import java.util.concurrent.ExecutionException

import brooklyn.entity.Application
import brooklyn.entity.Entity
import brooklyn.entity.trait.Changeable
import brooklyn.entity.trait.Startable
import brooklyn.location.Location
import brooklyn.management.Task
import brooklyn.management.internal.AbstractManagementContext
import brooklyn.management.internal.LocalManagementContext

public abstract class AbstractApplication extends AbstractEntity implements Startable, Application {
    private volatile AbstractManagementContext mgmt = null;
    private boolean deployed = false
    
    public AbstractApplication(Map properties=[:]) {
        super(properties)
        this.@application = new SelfEntityReference(this);
        
        if (properties.mgmt) {
            mgmt = (AbstractManagementContext) properties.remove("mgmt")
        }

        setAttribute(SERVICE_UP, false)
    }
    
    /**
     * Default start will start all Startable children
     */
    public void start(Collection<Location> locations) {
        this.locations.addAll(locations)
        
        List<Entity> startable = ownedChildren.findAll { it in Startable }
        if (startable && !startable.isEmpty() && locations && !locations.isEmpty()) {
	        Task start = invokeEffectorList(startable, Startable.START, [ locations:locations ])
	        try {
	            start.get()
	        } catch (ExecutionException ee) {
	            throw ee.cause
	        }
        }

        setAttribute(SERVICE_UP, true)
        deployed = true
    }

    /**
     * Default stop will stop all Startable children
     */
    public void stop() {
        List<Entity> startable = ownedChildren.findAll { it in Startable }
        if (startable && !startable.isEmpty()) {
            Task task = invokeEffectorList(startable, Startable.STOP)
            try {
                task.get()
            } catch (ExecutionException ee) {
                throw ee.cause
            }
        }

        setAttribute(SERVICE_UP, false)
        deployed = false
        
        //TODO review mgmt destroy lifecycle
        //  we don't necessarily want to forget all about the app on stop, 
        //since operator may be interested in things recently stopped;
        //but that could be handled by the impl at management
        //(keeping recently unmanaged things)  
        //  however unmanaging must be done last, _after_ we stop children and set attributes 
        getManagementContext().unmanage(this)
    }

    public void restart() {
        throw new UnsupportedOperationException()
    }

    @Override
    public synchronized AbstractManagementContext getManagementContext() {
        if (mgmt) return mgmt

        //TODO how does user override?  expect he annotates a field in this class, then look up that field?
        //(do that here)

        mgmt = new LocalManagementContext()
        if (deployed) {
            mgmt.manage(this)
        }
        return mgmt
    }
 
    public boolean isDeployed() {
        // TODO How to tell if we're deployed? What if sub-class overrides start 
        return deployed
    }
}
