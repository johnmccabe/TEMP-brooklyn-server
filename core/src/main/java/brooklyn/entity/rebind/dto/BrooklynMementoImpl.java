package brooklyn.entity.rebind.dto;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import brooklyn.BrooklynVersion;
import brooklyn.mementos.BrooklynMemento;
import brooklyn.mementos.EntityMemento;
import brooklyn.mementos.LocationMemento;
import brooklyn.mementos.PolicyMemento;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

public class BrooklynMementoImpl implements BrooklynMemento, Serializable {

    private static final long serialVersionUID = -5848083830410137654L;
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        protected String brooklynVersion = BrooklynVersion.get();
        protected final List<String> applicationIds = Lists.newArrayList();
        protected final List<String> topLevelLocationIds = Lists.newArrayList();
        protected final Map<String, EntityMemento> entities = Maps.newLinkedHashMap();
        protected final Map<String, LocationMemento> locations = Maps.newLinkedHashMap();
        protected final Map<String, PolicyMemento> policies = Maps.newLinkedHashMap();
        
        public Builder brooklynVersion(String val) {
            brooklynVersion = val; return this;
        }
        public Builder applicationId(String val) {
            applicationIds.add(val); return this;
        }
        public Builder applicationIds(List<String> vals) {
            applicationIds.addAll(vals); return this;
        }
        public Builder topLevelLocationIds(List<String> vals) {
            topLevelLocationIds.addAll(vals); return this;
        }
        public Builder entities(Map<String, EntityMemento> vals) {
            entities.putAll(vals); return this;
        }
        public Builder locations(Map<String, LocationMemento> vals) {
            locations.putAll(vals); return this;
        }
        public Builder policy(PolicyMemento val) {
            policies.put(val.getId(), val); return this;
        }
        public Builder entity(EntityMemento val) {
            entities.put(val.getId(), val); return this;
        }
        public Builder location(LocationMemento val) {
            locations.put(val.getId(), val); return this;
        }
        public Builder policies(Map<String, PolicyMemento> vals) {
            policies.putAll(vals); return this;
        }
        public BrooklynMemento build() {
            return new BrooklynMementoImpl(this);
        }
    }

    private String brooklynVersion;
    private List<String> applicationIds;
    private List<String> topLevelLocationIds;
    private Map<String, EntityMemento> entities;
    private Map<String, LocationMemento> locations;
    private Map<String, PolicyMemento> policies;
    
    private BrooklynMementoImpl(Builder builder) {
        brooklynVersion = builder.brooklynVersion;
        applicationIds = builder.applicationIds;
        topLevelLocationIds = builder.topLevelLocationIds;
        entities = builder.entities;
        locations = builder.locations;
        policies = builder.policies;
    }

    @Override
    public EntityMemento getEntityMemento(String id) {
        return entities.get(id);
    }

    @Override
    public LocationMemento getLocationMemento(String id) {
        return locations.get(id);
    }
    
    @Override
    public PolicyMemento getPolicyMemento(String id) {
        return policies.get(id);
    }

    @Override
    public Collection<String> getApplicationIds() {
        return ImmutableList.copyOf(applicationIds);
    }

    @Override
    public Collection<String> getEntityIds() {
        return Collections.unmodifiableSet(entities.keySet());
    }
    
    @Override
    public Collection<String> getLocationIds() {
        return Collections.unmodifiableSet(locations.keySet());
    }
    
    @Override
    public Collection<String> getPolicyIds() {
        return Collections.unmodifiableSet(policies.keySet());
    }
    
    @Override
    public Collection<String> getTopLevelLocationIds() {
        return Collections.unmodifiableList(topLevelLocationIds);
    }

    @Override
    public Map<String, EntityMemento> getEntityMementos() {
        return Collections.unmodifiableMap(entities);
    }
    @Override
    public Map<String, LocationMemento> getLocationMementos() {
        return Collections.unmodifiableMap(locations);
    }

    @Override
    public Map<String, PolicyMemento> getPolicyMementos() {
        return Collections.unmodifiableMap(policies);
    }
}
