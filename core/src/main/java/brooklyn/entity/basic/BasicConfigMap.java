package brooklyn.entity.basic;

import static brooklyn.util.GroovyJavaMethods.elvis;
import groovy.lang.Closure;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.ConfigKey;
import brooklyn.entity.ConfigKey.HasConfigKey;
import brooklyn.event.basic.StructuredConfigKey;
import brooklyn.management.ExecutionContext;
import brooklyn.util.flags.TypeCoercions;
import brooklyn.util.internal.ConfigKeySelfExtracting;
import brooklyn.util.text.WildcardGlobs;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;

@SuppressWarnings("deprecation")
public class BasicConfigMap implements brooklyn.entity.ConfigMap, ConfigMap {

    private static final Logger LOG = LoggerFactory.getLogger(BasicConfigMap.class);

    /** entity against which config resolution / task execution will occur */
    private final AbstractEntity entity;

    /*
     * TODO An alternative implementation approach would be to have:
     *   setOwner(Entity o, Map<ConfigKey,Object> inheritedConfig=[:])
     * The idea is that the owner could in theory decide explicitly what in its config
     * would be shared.
     * I (Aled) am undecided as to whether that would be better...
     * 
     * (Alex) i lean toward the config key getting to make the decision
     */
    /**
     * Map of configuration information that is defined at start-up time for the entity. These
     * configuration parameters are shared and made accessible to the "owned children" of this
     * entity.
     */
    private final Map<ConfigKey<?>,Object> ownConfig = Collections.synchronizedMap(new LinkedHashMap<ConfigKey<?>, Object>());
    private final Map<ConfigKey<?>,Object> inheritedConfig = Collections.synchronizedMap(new LinkedHashMap<ConfigKey<?>, Object>());

    public BasicConfigMap(AbstractEntity entity) {
        this.entity = Preconditions.checkNotNull(entity, "entity must be specified");
    }

    public <T> T getConfig(ConfigKey<T> key) {
        return getConfig(key, null);
    }
    
    public <T> T getConfig(HasConfigKey<T> key) {
        return getConfig(key.getConfigKey(), null);
    }
    
    public <T> T getConfig(HasConfigKey<T> key, T defaultValue) {
        return getConfig(key.getConfigKey(), defaultValue);
    }
    
    @SuppressWarnings("unchecked")
    public <T> T getConfig(ConfigKey<T> key, T defaultValue) {
        // FIXME What about inherited task in config?!
        //              alex says: think that should work, no?
        // FIXME What if someone calls getConfig on a task, before setting parent app?
        //              alex says: not supported (throw exception, or return the task)
        
        // In case this entity class has overridden the given key (e.g. to set default), then retrieve this entity's key
        // TODO If ask for a config value that's not in our configKeys, should we really continue with rest of method and return key.getDefaultValue?
        //      e.g. SshBasedJavaAppSetup calls setAttribute(JMX_USER), which calls getConfig(JMX_USER)
        //           but that example doesn't have a default...
        ConfigKey<T> ownKey = entity!=null ? (ConfigKey<T>)elvis(entity.getEntityType().getConfigKey(key.getName()), key) : key;
        
        ExecutionContext exec = entity.getExecutionContext();
        
        // Don't use groovy truth: if the set value is e.g. 0, then would ignore set value and return default!
        if (ownKey instanceof ConfigKeySelfExtracting) {
            if (((ConfigKeySelfExtracting<T>)ownKey).isSet(ownConfig)) {
                return ((ConfigKeySelfExtracting<T>)ownKey).extractValue(ownConfig, exec);
            } else if (((ConfigKeySelfExtracting<T>)ownKey).isSet(inheritedConfig)) {
                return ((ConfigKeySelfExtracting<T>)ownKey).extractValue(inheritedConfig, exec);
            }
        } else {
            LOG.warn("Config key {} of {} is not a ConfigKeySelfExtracting; cannot retrieve value; returning default", ownKey, this);
        }
        return TypeCoercions.coerce((defaultValue != null) ? defaultValue : ownKey.getDefaultValue(), key.getType());
    }
    
    @Override
    public Object getRawConfig(ConfigKey<?> key) {
        if (ownConfig.containsKey(key)) return ownConfig.get(key);
        if (inheritedConfig.containsKey(key)) return inheritedConfig.get(key);
        return null;
    }
    
    public Map<ConfigKey<?>,Object> getAllConfig() {
        Map<ConfigKey<?>,Object> result = new LinkedHashMap<ConfigKey<?>,Object>(inheritedConfig.size()+ownConfig.size());
        result.putAll(inheritedConfig);
        result.putAll(ownConfig);
        return Collections.unmodifiableMap(result);
    }

    public Object setConfig(ConfigKey<?> key, Object v) {
        Object val;
        if ((v instanceof Future) || (v instanceof Closure)) {
            //no coercion for these (yet)
            val = v;
        } else {
            try {
                val = TypeCoercions.coerce(v, key.getType());
            } catch (Exception e) {
                throw new IllegalArgumentException("Cannot coerce or set "+v+" to "+key, e);
            }
        }
        Object oldVal;
        if (key instanceof StructuredConfigKey) {
            oldVal = ((StructuredConfigKey)key).applyValueToMap(val, ownConfig);
        } else {
            oldVal = ownConfig.put(key, val);
        }
        entity.refreshInheritedConfigOfChildren();
        return oldVal;
    }
    
    public void setInheritedConfig(Map<ConfigKey<?>, ? extends Object> vals) {
        inheritedConfig.putAll(vals);
    }
    
    public void clearInheritedConfig() {
        inheritedConfig.clear();
    }

    @Override
    public BasicConfigMap submapMatchingGlob(final String glob) {
        return submapMatchingConfigKeys(new Predicate<ConfigKey<?>>() {
            @Override
            public boolean apply(@Nullable ConfigKey<?> input) {
                return WildcardGlobs.isGlobMatched(glob, input.getName());
            }
        });
    }

    @Override
    public BasicConfigMap submapMatchingRegex(String regex) {
        final Pattern p = Pattern.compile(regex);
        return submapMatchingConfigKeys(new Predicate<ConfigKey<?>>() {
            @Override
            public boolean apply(@Nullable ConfigKey<?> input) {
                return p.matcher(input.getName()).matches();
            }
        });
    }
    
    @Override
    public BasicConfigMap submapMatching(final Predicate<String> filter) {
        return submapMatchingConfigKeys(new Predicate<ConfigKey<?>>() {
            @Override
            public boolean apply(@Nullable ConfigKey<?> input) {
                return filter.apply(input.getName());
            }
        });
    }

    @Override
    public BasicConfigMap submapMatchingConfigKeys(Predicate<ConfigKey<?>> filter) {
        BasicConfigMap m = new BasicConfigMap(entity);
        for (Map.Entry<ConfigKey<?>,Object> entry: inheritedConfig.entrySet())
            if (filter.apply(entry.getKey()))
                m.inheritedConfig.put(entry.getKey(), entry.getValue());
        for (Map.Entry<ConfigKey<?>,Object> entry: ownConfig.entrySet())
            if (filter.apply(entry.getKey()))
                m.ownConfig.put(entry.getKey(), entry.getValue());
        return m;
    }

    @Override
    public String toString() {
        return super.toString()+"[own="+Entities.sanitize(ownConfig)+"; inherited="+Entities.sanitize(inheritedConfig)+"]";
    }
}
