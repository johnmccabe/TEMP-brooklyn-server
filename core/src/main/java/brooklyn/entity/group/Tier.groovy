package brooklyn.entity.group

import java.util.Map

import brooklyn.entity.Entity
import brooklyn.entity.Group
import brooklyn.entity.basic.AbstractGroup
import brooklyn.entity.trait.Startable

public abstract class Tier extends AbstractGroup {
    public Tier(Map properties=[:], Entity owner=null) {
        super(properties, owner)
    }
}

public abstract class TierFromTemplate extends Tier implements Startable {
    Entity template

    public TierFromTemplate(Map properties=[:], Entity owner=null, Entity template=null) {
        super(properties, owner);
        if (template) this.template = template
    }
}