package io.brooklyn.camp.brooklyn.spi.dsl.methods;

import io.brooklyn.camp.brooklyn.BrooklynCampConstants;
import io.brooklyn.camp.brooklyn.spi.dsl.BrooklynDslDeferredSupplier;

import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Callable;

import brooklyn.entity.Entity;
import brooklyn.entity.basic.EntityPredicates;
import brooklyn.event.AttributeSensor;
import brooklyn.event.Sensor;
import brooklyn.event.basic.DependentConfiguration;
import brooklyn.event.basic.Sensors;
import brooklyn.management.Task;
import brooklyn.management.internal.EntityManagerInternal;
import brooklyn.util.task.TaskBuilder;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

public class DslComponent extends BrooklynDslDeferredSupplier<Entity> {

	private final String componentId;
	private final Scope scope;

	public DslComponent(String componentId) {
		this(Scope.GLOBAL, componentId);
	}
	
	public DslComponent(Scope scope, String componentId) {
	    Preconditions.checkNotNull(scope, "scope");
	    this.componentId = componentId;
	    this.scope = scope;
	}

    @Override
    public Task<Entity> newTask() {
        return TaskBuilder.<Entity>builder().name("component("+componentId+")").body(new Callable<Entity>() {
            @Override
            public Entity call() throws Exception {
                Iterable<Entity> entitiesToSearch = null;
                switch (scope) {
                    case THIS:
                        return entity();
                    case PARENT:
                        return entity().getParent();
                    case GLOBAL:
                        entitiesToSearch = ((EntityManagerInternal)entity().getManagementContext().getEntityManager())
                            .getAllEntitiesInApplication( entity().getApplication() );
                        break;
                    case DESCENDANT:
                        entitiesToSearch = Sets.newLinkedHashSet();
                        addDescendants(entity(), (Set<Entity>)entitiesToSearch);
                        break;
                    case SIBLING:
                        entitiesToSearch = entity().getParent().getChildren();
                        break;
                    case CHILD:
                        entitiesToSearch = entity().getChildren();
                        break;
                    default:
                        throw new IllegalStateException("Unexpected scope "+scope);
                }
                
                Optional<Entity> result = Iterables.tryFind(entitiesToSearch, EntityPredicates.configEqualTo(BrooklynCampConstants.PLAN_ID, componentId));
                
                if (result.isPresent())
                    return result.get();
                
                // TODO may want to block and repeat on new entities joining?
                throw new NoSuchElementException("No entity matching id " + componentId);
            }
        }).build();
    }
    
    private void addDescendants(Entity entity, Set<Entity> entities) {
        entities.add(entity);
        for (Entity child : entity.getChildren()) {
            addDescendants(child, entities);
        }
    }
    
	public BrooklynDslDeferredSupplier<?> attributeWhenReady(final String sensorName) {
		return new BrooklynDslDeferredSupplier<Object>() {
			@SuppressWarnings("unchecked")
            @Override
			public Task<Object> newTask() {
				Entity targetEntity = DslComponent.this.get();
				Sensor<?> targetSensor = targetEntity.getEntityType().getSensor(sensorName);
				if (!(targetSensor instanceof AttributeSensor<?>)) {
					targetSensor = Sensors.newSensor(Object.class, sensorName);
				}
				return (Task<Object>) DependentConfiguration.attributeWhenReady(targetEntity, (AttributeSensor<?>)targetSensor);
			}
		};
	}
	
	public static enum Scope {
	    GLOBAL ("global"),
	    CHILD ("child"),
	    PARENT ("parent"),
	    SIBLING ("sibling"),
	    DESCENDANT ("descendant"),
	    THIS ("this");
	    
	    public static final Set<Scope> VALUES = ImmutableSet.of(GLOBAL, CHILD, PARENT, SIBLING, DESCENDANT, THIS);
	    
	    private final String name;
	    
	    private Scope(String name) {
	        this.name = name;
	    }
	    
	    public static Scope fromString(String name) {
	        for (Scope scope : VALUES)
	            if (scope.name.toLowerCase().equals(name.toLowerCase()))
	                return scope;
	        throw new IllegalArgumentException(name + " is not a valid scope");
	    }
	    
	    public static boolean isValid(String name) {
	        for (Scope scope : VALUES)
	            if (scope.name.toLowerCase().equals(name.toLowerCase()))
	                return true;
	        return false;
	    }
	}

}