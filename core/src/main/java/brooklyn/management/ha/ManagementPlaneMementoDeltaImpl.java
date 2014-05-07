package brooklyn.management.ha;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import java.util.Collection;

import brooklyn.management.ha.ManagementPlaneMementoPersister.Delta;

import com.google.common.annotations.Beta;
import com.google.common.collect.Sets;

/**
 * @since 0.7.0
 * 
 * @author aled
 */
@Beta
public class ManagementPlaneMementoDeltaImpl implements Delta {
    
    // TODO Nicer name for this class is needed
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private Collection<ManagerMemento> nodes = Sets.newLinkedHashSet();
        private Collection <String> removedNodeIds = Sets.newLinkedHashSet();
        private MasterChange masterChange = MasterChange.NO_CHANGE;
        private String master;
        
        public Builder node(ManagerMemento node) {
            nodes.add(checkNotNull(node, "node")); return this;
        }
        public Builder removedNodeId(String id) {
            removedNodeIds.add(checkNotNull(id, "id")); return this;
        }
        public Builder setMaster(String nodeId) {
            masterChange = MasterChange.SET_MASTER;
            master = checkNotNull(nodeId, "masterId");
            return this;
        }
        public Builder clearMaster(String nodeId) {
            masterChange = MasterChange.CLEAR_MASTER;
            return this;
        }
        public Delta build() {
            return new ManagementPlaneMementoDeltaImpl(this);
        }
    }
    
    private final Collection<ManagerMemento> nodes;
    private final Collection <String> removedNodeIds;
    private final MasterChange masterChange;
    private String masterId;
    
    ManagementPlaneMementoDeltaImpl(Builder builder) {
        nodes = builder.nodes;
        removedNodeIds = builder.removedNodeIds;
        masterChange = builder.masterChange;
        masterId = builder.master;
        checkState((masterChange == MasterChange.SET_MASTER) ? (masterId != null) : (masterId == null), 
                "invalid combination: change=%s; masterId=%s", masterChange, masterId);
    }
    
    @Override
    public Collection<ManagerMemento> getNodes() {
        return nodes;
    }

    @Override
    public Collection<String> getRemovedNodeIds() {
        return removedNodeIds;
    }

    @Override
    public MasterChange getMasterChange() {
        return masterChange;
    }

    @Override
    public String getNewMasterOrNull() {
        return masterId;
    }
}
