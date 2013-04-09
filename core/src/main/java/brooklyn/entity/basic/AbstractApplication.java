package brooklyn.entity.basic;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.config.BrooklynProperties;
import brooklyn.entity.Application;
import brooklyn.entity.Entity;
import brooklyn.entity.trait.StartableMethods;
import brooklyn.location.Location;
import brooklyn.management.ManagementContext;
import brooklyn.management.internal.AbstractManagementContext;
import brooklyn.util.ResourceUtils;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.flags.SetFromFlag;

/**
 * Users can extend this to define the entities in their application, and the relationships between
 * those entities. Users should override the {@link #init()} method, and in there should create 
 * their entities.
 */
public abstract class AbstractApplication extends AbstractEntity implements StartableApplication {
    public static final Logger log = LoggerFactory.getLogger(AbstractApplication.class);
    
    @SetFromFlag("mgmt")
    private volatile ManagementContext mgmt;
    
    private boolean deployed = false;

    BrooklynProperties brooklynProperties = null;

    private volatile Application application;
    
    public AbstractApplication(){
        this(new LinkedHashMap());
        log.debug("Using the AbstractApplication no arg constructor will rely on the properties defined in ~/.brooklyn/brooklyn.properties, " +
                       "potentially bypassing explicitly loaded properties");
    }

    /** Usual constructor, takes a set of properties;
     * also (experimental) permits defining a brooklynProperties source */
    public AbstractApplication(Map properties) {
        super(properties);

        if (properties.containsKey("mgmt")) {
            mgmt = (ManagementContext) properties.remove("mgmt");
        }

        // TODO decide whether this is the best way to inject properties like this
        Object propsSource=null;
        if (properties.containsKey("brooklynProperties")) {
            propsSource = properties.remove("brooklynProperties");
        } else if (properties.containsKey("brooklyn.properties")) {
            propsSource = properties.remove("brooklyn.properties");
        } 
        if (propsSource instanceof String) {
            Properties p = new Properties();
            try {
                p.load(new ResourceUtils(this).getResourceFromUrl((String)propsSource));
            } catch (IOException e) {
                throw new IllegalArgumentException("Invalid brooklyn properties source "+propsSource+": "+e, e);
            }
            propsSource = p;
        }
        if (propsSource instanceof BrooklynProperties) {
            brooklynProperties = (BrooklynProperties) propsSource;
        } else if (propsSource instanceof Map) {
            brooklynProperties = BrooklynProperties.Factory.newEmpty().addFromMap((Map)propsSource);
        } else {
            if (propsSource!=null) 
                throw new IllegalArgumentException("Invalid brooklyn properties source "+propsSource);
            brooklynProperties = BrooklynProperties.Factory.newDefault();
        }

        setAttribute(SERVICE_UP, false);
        setAttribute(Attributes.SERVICE_STATE, Lifecycle.CREATED);
    }

    /** 
     * Constructor for when application is nested inside another application
     * 
     * @deprecated Nesting applications is not currently supported
     */
    @Deprecated
    public AbstractApplication(Map properties, Entity parent) {
        super(properties, parent);
    }

    @Override
    public void init() {
        log.warn("Deprecated: AbstractApplication.init() will be declared abstract in a future release; please override for code instantiating child entities");
    }

    @Override
    public Application getApplication() {
        if (application!=null) {
            if (application.getId().equals(getId()))
                return (Application) getProxyIfAvailable();
            return application;
        }
        if (getParent()==null) return (Application)getProxyIfAvailable();
        return getParent().getApplication();
    }
    
    @Override
    protected synchronized void setApplication(Application app) {
        if (app.getId().equals(getId())) {
            application = getProxy()!=null ? (Application)getProxy() : app;
        } else {
            application = app;

            // Alex, Mar 2013: added some checks; 
            // i *think* these conditions should not happen, 
            // and so should throw but don't want to break things (yet)
            if (getParent()==null) {
                log.warn("Setting application of "+this+" to "+app+", but "+this+" is not parented");
            } else if (getParent().getApplicationId().equals(app.getParent())) {
                log.warn("Setting application of "+this+" to "+app+", but parent "+getParent()+" has different app "+getParent().getApplication());
            }
        }
        super.setApplication(app);
    }
    
    public AbstractApplication setParent(Entity parent) {
        super.setParent(parent);
        return this;
    }
    
    /**
     * Default start will start all Startable children (child.start(Collection<? extends Location>)),
     * calling preStart(locations) first and postStart(locations) afterwards.
     */
    public void start(Collection<? extends Location> locations) {
        this.addLocations(locations);

        setAttribute(Attributes.SERVICE_STATE, Lifecycle.STARTING);
        try {
            preStart(locations);
            StartableMethods.start(this, locations);
            postStart(locations);
        } catch (Exception e) {
            setAttribute(Attributes.SERVICE_STATE, Lifecycle.ON_FIRE);
            log.warn("Error starting application " + this + " (rethrowing): "+e);
            throw Exceptions.propagate(e);
        }

        setAttribute(SERVICE_UP, true);
        setAttribute(Attributes.SERVICE_STATE, Lifecycle.RUNNING);
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
        setAttribute(Attributes.SERVICE_STATE, Lifecycle.STOPPING);
        try {
            StartableMethods.stop(this);
        } catch (Exception e) {
            setAttribute(Attributes.SERVICE_STATE, Lifecycle.ON_FIRE);
            log.warn("Error stopping application " + this + " (rethrowing): "+e);
            throw Exceptions.propagate(e);
        }
        setAttribute(Attributes.SERVICE_STATE, Lifecycle.STOPPED);

        synchronized (this) {
            deployed = false;
            //TODO review mgmt destroy lifecycle
            //  we don't necessarily want to forget all about the app on stop, 
            //since operator may be interested in things recently stopped;
            //but that could be handled by the impl at management
            //(keeping recently unmanaged things)  
            //  however unmanaging must be done last, _after_ we stop children and set attributes 
            getEntityManager().unmanage(this);
        }

        log.info("Stopped application " + this);
    }

    public void restart() {
        throw new UnsupportedOperationException();
    }
}
