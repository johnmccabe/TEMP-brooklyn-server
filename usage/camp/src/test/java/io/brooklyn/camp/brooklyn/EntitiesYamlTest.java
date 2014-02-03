package io.brooklyn.camp.brooklyn;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.testng.collections.Lists;

import brooklyn.config.ConfigKey;
import brooklyn.entity.Application;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.EntityInternal;
import brooklyn.event.AttributeSensor;
import brooklyn.location.Location;
import brooklyn.management.internal.EntityManagerInternal;
import brooklyn.test.entity.TestEntity;
import brooklyn.util.collections.MutableMap;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

@Test
public class EntitiesYamlTest extends AbstractYamlTest {
    private static final Logger log = LoggerFactory.getLogger(EnrichersYamlTest.class);
    
    @SuppressWarnings("unchecked")
    @Test
    public void testSingleEntity() throws Exception {
        Entity app = createAndStartApplication("test-entity-basic-template.yaml", ImmutableMap.of("brooklynConfig",
                new StringBuilder()
                    .append("test.confName: Test Entity Name\n")
                    .append("    test.confMapPlain:\n")
                    .append("      foo: bar\n")
                    .append("      baz: qux\n")
                    .append("    test.confListPlain:\n")
                    .append("      - dogs\n")
                    .append("      - cats\n")
                    .append("      - badgers\n")
                    .append("    test.confSetThing: !!set\n")
                    .append("      ? square\n")
                    .append("      ? circle\n")
                    .append("      ? triangle\n")
                    .append("    test.confObject: 5")
                    .toString()));
        waitForApplicationTasks(app);
        
        Assert.assertEquals(app.getDisplayName(), "test-entity-basic-template");

        log.info("App started:");
        Entities.dumpInfo(app);
        Entity entity = app.getChildren().iterator().next();
        Assert.assertNotNull(entity, "Expected app to have child entity");
        Assert.assertTrue(entity instanceof TestEntity, "Expected TestEntity, found " + entity.getClass());
        TestEntity testEntity = (TestEntity) entity;
        Assert.assertEquals(testEntity.getConfig(TestEntity.CONF_NAME), "Test Entity Name");
        List<String> list = testEntity.getConfig(TestEntity.CONF_LIST_PLAIN);
        Assert.assertEquals(list, ImmutableList.of("dogs", "cats", "badgers"));
        Map<String, String> map = testEntity.getConfig(TestEntity.CONF_MAP_PLAIN);
        Assert.assertEquals(map, ImmutableMap.of("foo", "bar", "baz", "qux"));
        Set<String> set = (Set<String>)testEntity.getConfig(TestEntity.CONF_SET_THING);
        Assert.assertEquals(set, ImmutableSet.of("square", "circle", "triangle"));
        Object object = testEntity.getConfig(TestEntity.CONF_OBJECT);
        Assert.assertEquals(object, 5);
    }
    
    @SuppressWarnings("unchecked")
    @Test
    public void testEmptyConfig() throws Exception {
        Entity app = createAndStartApplication("test-entity-basic-template.yaml", ImmutableMap.of("brooklynConfig",
                new StringBuilder()
                    .append("test.confName: \"\"\n")
                    .append("    test.confListPlain: !!seq []\n")
                    .append("    test.confMapPlain: !!map {}\n")
                    .append("    test.confSetPlain: !!set {}\n")
                    .append("    test.confObject: \"\"")
                    .toString()));
        waitForApplicationTasks(app);
        
        Assert.assertEquals(app.getDisplayName(), "test-entity-basic-template");

        log.info("App started:");
        Entities.dumpInfo(app);
        
        Entity entity = app.getChildren().iterator().next();
        Assert.assertNotNull(entity, "Expected app to have child entity");
        Assert.assertTrue(entity instanceof TestEntity, "Expected TestEntity, found " + entity.getClass());
        TestEntity testEntity = (TestEntity) entity;
        Assert.assertEquals(testEntity.getConfig(TestEntity.CONF_NAME), "");
        List<String> list = testEntity.getConfig(TestEntity.CONF_LIST_PLAIN);
        Assert.assertEquals(list, ImmutableList.of());
        Map<String, String> map = testEntity.getConfig(TestEntity.CONF_MAP_PLAIN);
        Assert.assertEquals(map, ImmutableMap.of());
        // TODO: CONF_SET_PLAIN is being set to an empty ArrayList - may be a snakeyaml issue?
        //        Set<String> plainSet = (Set<String>)testEntity.getConfig(TestEntity.CONF_SET_PLAIN);
        //        Assert.assertEquals(plainSet, ImmutableSet.of());
        Object object = testEntity.getConfig(TestEntity.CONF_OBJECT);
        Assert.assertEquals(object, "");
    }
    
    @SuppressWarnings("unchecked")
    @Test(groups="WIP")
    public void testEmptyStructuredConfig() throws Exception {
        Entity app = createAndStartApplication("test-entity-basic-template.yaml", ImmutableMap.of("brooklynConfig",
                new StringBuilder()
                    .append("test.confName: \"\"\n")
                    .append("    test.confListThing: !!seq []\n")
                    .append("    test.confSetThing: !!set {}\n")
                    .append("    test.confMapThing: !!map {}\n")
                    .toString()));
        waitForApplicationTasks(app);
        
        Assert.assertEquals(app.getDisplayName(), "test-entity-basic-template");

        log.info("App started:");
        Entities.dumpInfo(app);
        
        Entity entity = app.getChildren().iterator().next();
        Assert.assertNotNull(entity, "Expected app to have child entity");
        Assert.assertTrue(entity instanceof TestEntity, "Expected TestEntity, found " + entity.getClass());
        TestEntity testEntity = (TestEntity) entity;
        List<String> thingList = (List<String>)testEntity.getConfig(TestEntity.CONF_LIST_THING);
        Set<String> thingSet = (Set<String>)testEntity.getConfig(TestEntity.CONF_SET_THING);
        Map<String, String> thingMap = (Map<String, String>)testEntity.getConfig(TestEntity.CONF_MAP_THING);
        Assert.assertEquals(thingList, Lists.newArrayList());
        Assert.assertEquals(thingSet, ImmutableSet.of());
        Assert.assertEquals(thingMap, ImmutableMap.of());
    }
    
    @Test
    public void testSensor() throws Exception {
        Entity app = createAndStartApplication("test-entity-basic-template.yaml", ImmutableMap.of("brooklynConfig",
                new StringBuilder()
                    .append("test.confObject: $brooklyn:sensor(\"brooklyn.test.entity.TestEntity\", \"test.sequence\")\n")));
        waitForApplicationTasks(app);
        
        Assert.assertEquals(app.getDisplayName(), "test-entity-basic-template");
        
        log.info("App started:");
        Entities.dumpInfo(app);
        
        Entity entity = app.getChildren().iterator().next();
        Assert.assertNotNull(entity, "Expected app to have child entity");
        Assert.assertTrue(entity instanceof TestEntity, "Expected TestEntity, found " + entity.getClass());
        TestEntity testEntity = (TestEntity) entity;
        Object object = testEntity.getConfig(TestEntity.CONF_OBJECT);
        Assert.assertNotNull(object);
        Assert.assertTrue(object instanceof AttributeSensor, "attributeSensor="+object);
        Assert.assertEquals(object, TestEntity.SEQUENCE);
    }
    
    @Test
    public void testComponent() throws Exception {
        Entity app = createAndStartApplication("test-entity-basic-template.yaml", ImmutableMap.of("brooklynConfig",
                new StringBuilder()
                    .append("test.confName: first entity\n")
                    .toString(),
                "additionalConfig", 
                new StringBuilder()
                    .append("  id: te1\n")
                    .append("- serviceType: brooklyn.test.entity.TestEntity\n")
                    .append("  name: second entity\n")
                    .append("  brooklyn.config:\n")
                    .append("    test.confObject: $brooklyn:component(\"te1\")\n")
                    .toString()));
        waitForApplicationTasks(app);
        Entity firstEntity = null;
        Entity secondEntity = null;
        Assert.assertEquals(app.getChildren().size(), 2);
        for (Entity entity : app.getChildren()) {
            if (entity.getDisplayName().equals("testentity"))
                firstEntity = entity;
            else if (entity.getDisplayName().equals("second entity"))
                secondEntity = entity;
        }
        final Entity[] entities = {firstEntity, secondEntity};
        Assert.assertNotNull(entities[0], "Expected app to contain child named 'testentity'");
        Assert.assertNotNull(entities[1], "Expected app to contain child named 'second entity'");
        Object object = ((EntityInternal)app).getExecutionContext().submit(MutableMap.of(), new Callable<Object>() {
            public Object call() {
                return entities[1].getConfig(TestEntity.CONF_OBJECT);
            }}).get();
        Assert.assertNotNull(object);
        Assert.assertEquals(object, firstEntity, "Expected second entity's test.confObject to contain first entity");
    }
    
    @Test
    public void testGrandchildEntities() throws Exception {
        Entity app = createAndStartApplication("test-entity-basic-template.yaml", ImmutableMap.of("brooklynConfig",
                new StringBuilder()
                    .append("test.confName: first entity\n")
                    .toString(),
                "additionalConfig", 
                new StringBuilder()
                    .append("  brooklyn.children:\n")
                    .append("  - serviceType: brooklyn.test.entity.TestEntity\n")
                    .append("    name: Child Entity\n")
                    .append("    brooklyn.config:\n")
                    .append("      test.confName: Name of the first Child\n")
                    .append("    brooklyn.children:\n")
                    .append("    - serviceType: brooklyn.test.entity.TestEntity\n")
                    .append("      name: Grandchild Entity\n")
                    .append("      brooklyn.config:\n")
                    .append("        test.confName: Name of the Grandchild\n")
                    .append("  - serviceType: brooklyn.test.entity.TestEntity\n")
                    .append("    name: Second Child\n")
                    .append("    brooklyn.config:\n")
                    .append("      test.confName: Name of the second Child")
                    .toString()));
        waitForApplicationTasks(app);
        Assert.assertEquals(app.getChildren().size(), 1);
        Entity firstEntity = app.getChildren().iterator().next();
        Assert.assertEquals(firstEntity.getConfig(TestEntity.CONF_NAME), "first entity");
        Assert.assertEquals(firstEntity.getChildren().size(), 2);
        Entity firstChild = null;
        Entity secondChild = null;
        for (Entity entity : firstEntity.getChildren()) {
            if (entity.getConfig(TestEntity.CONF_NAME).equals("Name of the first Child"))
                firstChild = entity;
            if (entity.getConfig(TestEntity.CONF_NAME).equals("Name of the second Child"))
                secondChild = entity;
        }
        Assert.assertNotNull(firstChild, "Expected a child of 'first entity' with the name 'Name of the first Child'");
        Assert.assertNotNull(secondChild, "Expected a child of 'first entity' with the name 'Name of the second Child'");
        Assert.assertEquals(firstChild.getChildren().size(), 1);
        Entity grandchild = firstChild.getChildren().iterator().next();
        Assert.assertEquals(grandchild.getConfig(TestEntity.CONF_NAME), "Name of the Grandchild");
        Assert.assertEquals(secondChild.getChildren().size(), 0);
    }

    @Test
    public void testWithInitConfig() throws Exception {
        Entity app = createAndStartApplication("test-entity-with-init-config.yaml");
        waitForApplicationTasks(app);
        Assert.assertEquals(app.getDisplayName(), "test-entity-with-init-config");
        TestEntityWithInitConfig testWithConfigInit = null;
        TestEntity testEntity = null;
        Assert.assertEquals(app.getChildren().size(), 2);
        for (Entity entity : app.getChildren()) {
            if (entity instanceof TestEntity)
                testEntity = (TestEntity) entity;
            if (entity instanceof TestEntityWithInitConfig)
                testWithConfigInit = (TestEntityWithInitConfig) entity;
        }
        Assert.assertNotNull(testEntity, "Expected app to contain TestEntity child");
        Assert.assertNotNull(testWithConfigInit, "Expected app to contain TestEntityWithInitConfig child");
        Assert.assertEquals(testWithConfigInit.getEntityCachedOnInit(), testEntity);
        log.info("App started:");
        Entities.dumpInfo(app);
    }
    
    @Test
    public void testMultipleReferences() throws Exception {
        final Entity app = createAndStartApplication("test-referencing-entities.yaml");
        waitForApplicationTasks(app);
        Assert.assertEquals(app.getDisplayName(), "test-referencing-entities");
        
        Entity entity1 = null, entity2 = null, child1 = null, child2 = null, grandchild1 = null, grandchild2 = null;
        
        Assert.assertEquals(app.getChildren().size(), 2);
        for (Entity child : app.getChildren()) {
            if (child.getDisplayName().equals("entity 1"))
                entity1 = child;
            if (child.getDisplayName().equals("entity 2"))
                entity2 = child;
        }
        Assert.assertNotNull(entity1);
        Assert.assertNotNull(entity2);
        
        Assert.assertEquals(entity1.getChildren().size(), 2);
        for (Entity child : entity1.getChildren()) {
            if (child.getDisplayName().equals("child 1"))
                child1 = child;
            if (child.getDisplayName().equals("child 2"))
                child2 = child;
        }
        Assert.assertNotNull(child1);
        Assert.assertNotNull(child2);
        
        Assert.assertEquals(child1.getChildren().size(), 2);
        for (Entity child : child1.getChildren()) {
            if (child.getDisplayName().equals("grandchild 1"))
               grandchild1 = child;
            if (child.getDisplayName().equals("grandchild 2"))
                grandchild2 = child;
        }
        Assert.assertNotNull(grandchild1);
        Assert.assertNotNull(grandchild2);
        
        Map<ConfigKey<Entity>, Entity> keyToEntity = new ImmutableMap.Builder<ConfigKey<Entity>, Entity>()
                .put(ReferencingYamlTestEntity.TEST_REFERENCE_APP, app)
                .put(ReferencingYamlTestEntity.TEST_REFERENCE_ENTITY1, entity1)
                .put(ReferencingYamlTestEntity.TEST_REFERENCE_ENTITY2, entity2)
                .put(ReferencingYamlTestEntity.TEST_REFERENCE_CHILD1, child1)
                .put(ReferencingYamlTestEntity.TEST_REFERENCE_CHILD2, child2)
                .put(ReferencingYamlTestEntity.TEST_REFERENCE_GRANDCHILD1, grandchild1)
                .put(ReferencingYamlTestEntity.TEST_REFERENCE_GRANDCHILD2, grandchild2)
                .build();
        
        Iterable<Entity> entitiesInApp = ((EntityInternal)app).getExecutionContext().submit(MutableMap.of(), new Callable<Iterable<Entity>>() {
            @Override
            public Iterable<Entity> call() throws Exception {
                return ((EntityManagerInternal)((EntityInternal)app).getManagementContext().getEntityManager()).getAllEntitiesInApplication((Application)app);
            }
        }).get();
        
        for (Entity entityInApp : entitiesInApp)
            checkReferences(entityInApp, keyToEntity);
    }
    
    private void checkReferences(final Entity entity, Map<ConfigKey<Entity>, Entity> keyToEntity) throws Exception {
        for (final ConfigKey<Entity> key : keyToEntity.keySet()) {
            Entity fromConfig = ((EntityInternal)entity).getExecutionContext().submit(MutableMap.of(), new Callable<Entity>() {
                @Override
                public Entity call() throws Exception {
                    return (Entity) entity.getConfig(key);
                }
            }).get();
            Assert.assertEquals(fromConfig, keyToEntity.get(key));
        }
    }
        
    public void testWithAppLocation() throws Exception {
        Entity app = createAndStartApplication("test-entity-basic-template.yaml", ImmutableMap.of("brooklynConfig", 
                "test.confName: first entity\n",
                "additionalConfig", 
                "location: localhost:(name=yaml name)\n"));
        waitForApplicationTasks(app);
        Assert.assertEquals(app.getLocations().size(), 1);
        Location location = app.getLocations().iterator().next();
        Assert.assertNotNull(location);
        Assert.assertEquals(location.getDisplayName(), "yaml name");
        Assert.assertEquals(app.getChildren().size(), 1);
        Entity entity = app.getChildren().iterator().next();
        Assert.assertNotNull(entity);
        Assert.assertEquals(entity.getLocations().size(), 0);
    }
    
    @Test
    public void testWithEntityLocation() throws Exception {
        Entity app = createAndStartApplication("test-entity-basic-template.yaml", ImmutableMap.of("brooklynConfig", 
                "test.confName: first entity\n",
                "additionalConfig", 
                "  location: localhost:(name=yaml name)\n"));
        waitForApplicationTasks(app);
        Assert.assertEquals(app.getLocations().size(), 0);
        Assert.assertEquals(app.getChildren().size(), 1);
        Entity entity = app.getChildren().iterator().next();
        Assert.assertEquals(entity.getLocations().size(), 1);
        Location location = entity.getLocations().iterator().next();
        Assert.assertNotNull(location);
        Assert.assertEquals(location.getDisplayName(), "yaml name");
        Assert.assertNotNull(entity);
    }

    @Test
    public void testWith2AppLocations() throws Exception {
        Entity app = createAndStartApplication("test-entity-basic-template.yaml", ImmutableMap.of("brooklynConfig", 
                "test.confName: first entity\n",
                "additionalConfig",
                new StringBuilder()
                    .append("locations: \n")
                    .append("- localhost:(name=localhost name)\n")
                    .append("- byon:(hosts=\"1.1.1.1\", name=byon name)\n")
                    .toString()));
        waitForApplicationTasks(app);
        
        Assert.assertEquals(app.getLocations().size(), 2);
        Location localhostLocation = null, byonLocation = null; 
        for (Location location : app.getLocations()) {
            if (location.getDisplayName().equals("localhost name"))
                localhostLocation = location;
            else if (location.getDisplayName().equals("byon name"))
                byonLocation = location;
        }
        Assert.assertNotNull(localhostLocation);
        Assert.assertNotNull(byonLocation);
        Assert.assertEquals(app.getChildren().size(), 1);
        Entity entity = app.getChildren().iterator().next();
        Assert.assertNotNull(entity);
        Assert.assertEquals(entity.getLocations().size(), 0);
    }
    
    @Test
    public void testWith2EntityLocations() throws Exception {
        Entity app = createAndStartApplication("test-entity-basic-template.yaml", ImmutableMap.of("brooklynConfig", 
                "test.confName: first entity\n",
                "additionalConfig",
                new StringBuilder()
                    .append("  locations: \n")
                    .append("  - localhost:(name=localhost name)\n")
                    .append("  - byon:(hosts=\"1.1.1.1\", name=byon name)\n")
                    .toString()));
        waitForApplicationTasks(app);
        Assert.assertEquals(app.getLocations().size(), 0);
        Assert.assertEquals(app.getChildren().size(), 1);
        Entity entity = app.getChildren().iterator().next();
        Assert.assertEquals(entity.getLocations().size(), 2);
        Location localhostLocation = null, byonLocation = null; 
        for (Location location : entity.getLocations()) {
            if (location.getDisplayName().equals("localhost name"))
                localhostLocation = location;
            else if (location.getDisplayName().equals("byon name"))
                byonLocation = location;
        }
        Assert.assertNotNull(localhostLocation);
        Assert.assertNotNull(byonLocation);
    }
    
    @Test
    public void testWithAppAndEntityLocations() throws Exception {
        Entity app = createAndStartApplication("test-entity-basic-template.yaml", ImmutableMap.of("brooklynConfig", 
                "test.confName: first entity\n",
                "additionalConfig",
                new StringBuilder()
                    .append("  location: localhost:(name=localhost name)\n")
                    .append("location: byon:(hosts=\"1.1.1.1\", name=byon name)\n")
                    .toString()));
        waitForApplicationTasks(app);
        Assert.assertEquals(app.getLocations().size(), 1);
        Assert.assertEquals(app.getChildren().size(), 1);
        Entity entity = app.getChildren().iterator().next();
        Assert.assertEquals(entity.getLocations().size(), 1);
        Location appLocation = app.getLocations().iterator().next();
        Assert.assertEquals(appLocation.getDisplayName(), "byon name");
        Location entityLocation = entity.getLocations().iterator().next();
        Assert.assertEquals(entityLocation.getDisplayName(), "localhost name");
    }
    
    protected Logger getLogger() {
        return log;
    }

}
