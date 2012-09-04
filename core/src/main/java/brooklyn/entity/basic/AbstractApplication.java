package brooklyn.entity.basic;

import brooklyn.entity.Application;
import brooklyn.entity.basic.EntityReferences.SelfEntityReference;
import brooklyn.entity.trait.Startable;
import brooklyn.entity.trait.StartableMethods;
import brooklyn.location.Location;
import brooklyn.management.internal.AbstractManagementContext;
import brooklyn.management.internal.LocalManagementContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public abstract class AbstractApplication extends AbstractEntity implements Startable, Application {
    public static final Logger log = LoggerFactory.getLogger(AbstractApplication.class);
    private volatile AbstractManagementContext mgmt = null;
    private boolean deployed = false;

    public AbstractApplication(){
        this(new LinkedHashMap());
    }

    public AbstractApplication(Map properties) {
        super(properties);
        this.application = new SelfEntityReference(this);

        if (properties.containsKey("mgmt")) {
            mgmt = (AbstractManagementContext) properties.remove("mgmt");
        }

        setAttribute(SERVICE_UP, false);
    }

    /**
     * Default start will start all Startable children (child.start(Collection<? extends Location>)),
     * calling preStart(locations) first and postStart(locations) afterwards.
     */
    public void start(Collection<? extends Location> locations) {
        this.getLocations().addAll(locations);

        preStart(locations);
        StartableMethods.start(this, locations);
        postStart(locations);

        setAttribute(SERVICE_UP, true);
        deployed = true;

        log.info("Started application " + this);
    }

    /**
     * Default is no-op. Subclasses can override.
     * */
    public void preStart(Collection<? extends Location> locations) {
        //no-op
    }

    /**
     * Default is no-op. Subclasses can override.
     * */
    public void postStart(Collection<? extends Location> locations) {
        //no-op
    }

    /**
     * Default stop will stop all Startable children
     */
    public void stop() {
        log.info("Stopping application " + this);

        setAttribute(SERVICE_UP, false);
        StartableMethods.stop(this);

        synchronized (this) {
            deployed = false;
            //TODO review mgmt destroy lifecycle
            //  we don't necessarily want to forget all about the app on stop, 
            //since operator may be interested in things recently stopped;
            //but that could be handled by the impl at management
            //(keeping recently unmanaged things)  
            //  however unmanaging must be done last, _after_ we stop children and set attributes 
            getManagementContext().unmanage(this);
        }

        log.info("Stopped application " + this);
    }

    public void restart() {
        throw new UnsupportedOperationException();
    }

    public boolean hasManagementContext() {
        return mgmt!=null;
    }
    
    public synchronized void setManagementContext(AbstractManagementContext mgmt) {
        if (mgmt!=null && mgmt.equals(this.mgmt))
            return;
        if (hasManagementContext() && mgmt!=null)
            throw new IllegalStateException("Cannot set management context on "+this+" as it already has a management context");
        if (isDeployed())
            throw new IllegalStateException("Cannot set management context on "+this+" as it is already deployed");
        
        this.mgmt = mgmt;
        if (isDeployed()) {
            mgmt.manage(this);            
        }
    }
    
    @Override
    public synchronized AbstractManagementContext getManagementContext() {
        if (hasManagementContext())
            return mgmt;

        setManagementContext(new LocalManagementContext());
        return mgmt;
    }

    public boolean isDeployed() {
        return deployed;
    }
}
