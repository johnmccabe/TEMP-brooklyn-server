package brooklyn.mementos;

import java.io.IOException;
import java.util.Collection;

public interface BrooklynMementoPersister {

    BrooklynMemento loadMemento() throws IOException;
    
    void checkpoint(BrooklynMemento memento);
    
    void delta(Delta delta);

    void stop();

    public interface Delta {
        Collection<LocationMemento> locations();
        Collection<EntityMemento> entities();
        Collection<PolicyMemento> policies();
        Collection<String> removedLocationIds();
        Collection<String> removedEntityIds();
        Collection<String> removedPolicyIds();
    }
}
