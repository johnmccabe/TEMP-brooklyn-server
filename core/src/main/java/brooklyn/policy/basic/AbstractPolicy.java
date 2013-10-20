package brooklyn.policy.basic;

import static brooklyn.util.GroovyJavaMethods.truth;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.config.ConfigKey;
import brooklyn.entity.proxying.InternalPolicyFactory;
import brooklyn.entity.rebind.BasicPolicyRebindSupport;
import brooklyn.entity.rebind.RebindSupport;
import brooklyn.entity.trait.Configurable;
import brooklyn.management.ExecutionContext;
import brooklyn.management.ManagementContext;
import brooklyn.mementos.PolicyMemento;
import brooklyn.policy.Policy;
import brooklyn.policy.PolicyType;
import brooklyn.util.config.ConfigBag;
import brooklyn.util.flags.FlagUtils;
import brooklyn.util.flags.TypeCoercions;

import com.google.common.annotations.Beta;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;

/**
 * Base {@link Policy} implementation; all policies should extend this or its children
 */
public abstract class AbstractPolicy extends AbstractEntityAdjunct implements Policy, Configurable {
    private static final Logger log = LoggerFactory.getLogger(AbstractPolicy.class);

    private volatile ManagementContext managementContext;
    protected String policyStatus;
    protected Map leftoverProperties = Maps.newLinkedHashMap();
    protected AtomicBoolean suspended = new AtomicBoolean(false);

    private boolean _legacyConstruction;
    private boolean inConstruction;

    protected transient ExecutionContext execution;

    /**
     * The config values of this entity. Updating this map should be done
     * via getConfig/setConfig.
     */
    protected final PolicyConfigMap configsInternal = new PolicyConfigMap(this);

    private final PolicyType policyType = new PolicyTypeImpl(this);
    
    public AbstractPolicy() {
        this(Collections.emptyMap());
    }
    
    public AbstractPolicy(Map flags) {
        inConstruction = true;
        _legacyConstruction = !InternalPolicyFactory.FactoryConstructionTracker.isConstructing();
        if (!_legacyConstruction && flags!=null && !flags.isEmpty()) {
            log.debug("Using direct policy construction for "+getClass().getName()+" because properties were specified ("+flags+")");
            _legacyConstruction = true;
        }
        
        if (_legacyConstruction) {
            log.debug("Using direct policy construction for "+getClass().getName()+"; calling configure(Map) immediately");
            
            configure(flags);
            
            boolean deferConstructionChecks = (flags.containsKey("deferConstructionChecks") && TypeCoercions.coerce(flags.get("deferConstructionChecks"), Boolean.class));
            if (!deferConstructionChecks) {
                FlagUtils.checkRequiredFields(this);
            }
        }
        
        inConstruction = false;
    }

    /** will set fields from flags, and put the remaining ones into the 'leftovers' map.
     * can be subclassed for custom initialization but note the following. 
     * <p>
     * if you require fields to be initialized you must do that in this method. You must
     * *not* rely on field initializers because they may not run until *after* this method
     * (this method is invoked by the constructor in this class, so initializers
     * in subclasses will not have run when this overridden method is invoked.) */ 
    protected void configure() {
        configure(Collections.emptyMap());
    }
    
    @SuppressWarnings("unchecked")
    public void configure(Map flags) {
        // TODO only set on first time through
        boolean isFirstTime = true;
        
        // allow config keys, and fields, to be set from these flags if they have a SetFromFlag annotation
        // or if the value is a config key
        for (Iterator<Map.Entry> iter = flags.entrySet().iterator(); iter.hasNext();) {
            Map.Entry entry = iter.next();
            if (entry.getKey() instanceof ConfigKey) {
                ConfigKey key = (ConfigKey)entry.getKey();
                if (getPolicyType().getConfigKeys().contains(key)) {
                    setConfig(key, entry.getValue());
                } else {
                    log.warn("Unknown configuration key {} for policy {}; ignoring", key, this);
                    iter.remove();
                }
            }
        }

        // TODO use ConfigBag
        ConfigBag bag = new ConfigBag().putAll(flags);
        FlagUtils.setFieldsFromFlags(this, bag, isFirstTime);
        FlagUtils.setAllConfigKeys(this, bag);
        leftoverProperties.putAll(bag.getUnusedConfig());

        //replace properties _contents_ with leftovers so subclasses see leftovers only
        flags.clear();
        flags.putAll(leftoverProperties);
        leftoverProperties = flags;
        
        if (!truth(name) && flags.containsKey("displayName")) {
            //TODO inconsistent with entity and location, where name is legacy and displayName is encouraged!
            //'displayName' is a legacy way to refer to a policy's name
            Preconditions.checkArgument(flags.get("displayName") instanceof CharSequence, "'displayName' property should be a string");
            setName(flags.remove("displayName").toString());
        }
    }
    
    protected boolean isLegacyConstruction() {
        return _legacyConstruction;
    }
    
    public void setManagementContext(ManagementContext managementContext) {
        this.managementContext = managementContext;
    }
    
    protected ManagementContext getManagementContext() {
        return managementContext;
    }

    /**
     * Called by framework (in new-style policies where PolicySpec was used) after configuring, setting parent, etc,
     * but before a reference to this policy is shared.
     * 
     * To preserve backwards compatibility for if the policy is constructed directly, one
     * can call the code below, but that means it will be called after references to this 
     * policy have been shared with other entities.
     * <pre>
     * {@code
     * if (isLegacyConstruction()) {
     *     init();
     * }
     * }
     * </pre>
     */
    public void init() {
        // no-op
    }
    
    public <T> T getConfig(ConfigKey<T> key) {
        return configsInternal.getConfig(key);
    }
    
    public <T> T setConfig(ConfigKey<T> key, T val) {
        if (entity != null && isRunning()) {
            doReconfigureConfig(key, val);
        }
        return (T) configsInternal.setConfig(key, val);
    }
    
    public Map<ConfigKey<?>, Object> getAllConfig() {
        return configsInternal.getAllConfig();
    }
    
    // TODO make immutable
    /** for inspection only */
    @Beta
    public PolicyConfigMap getConfigMap() {
        return configsInternal;
    }
    
    protected <T> void doReconfigureConfig(ConfigKey<T> key, T val) {
        throw new UnsupportedOperationException("reconfiguring "+key+" unsupported for "+this);
    }
    
    @Override
    public PolicyType getPolicyType() {
        return policyType;
    }

    public void suspend() {
        suspended.set(true);
    }

    public void resume() {
        suspended.set(false);
    }

    public boolean isSuspended() {
        return suspended.get();
    }

    @Override
    public void destroy(){
        suspend();
        super.destroy();
    }

    @Override
    public boolean isRunning() {
        return !isSuspended() && !isDestroyed();
    }

    @Override
    public RebindSupport<PolicyMemento> getRebindSupport() {
        return new BasicPolicyRebindSupport(this);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(getClass())
                .add("name", name)
                .add("running", isRunning())
                .toString();
    }
}
