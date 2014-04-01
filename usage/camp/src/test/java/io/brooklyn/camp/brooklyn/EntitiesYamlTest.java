package io.brooklyn.camp.brooklyn;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.io.StringReader;
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
import brooklyn.entity.basic.Attributes;
import brooklyn.entity.basic.BasicEntity;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.EntityInternal;
import brooklyn.entity.basic.Lifecycle;
import brooklyn.entity.basic.SameServerEntity;
import brooklyn.entity.group.DynamicCluster;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.event.AttributeSensor;
import brooklyn.location.Location;
import brooklyn.management.internal.EntityManagerInternal;
import brooklyn.test.entity.TestEntity;
import brooklyn.util.collections.MutableMap;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

@Test
public class EntitiesYamlTest extends AbstractYamlTest {
    private static final Logger log = LoggerFactory.getLogger(EnrichersYamlTest.class);

    @SuppressWarnings("unchecked")
    @Test
    public void testSingleEntity() throws Exception {
        Entity app = createAndStartApplication("test-entity-basic-template.yaml", 
            "  brooklyn.config:",
            "    test.confName: Test Entity Name",
            "    test.confMapPlain:",
            "      foo: bar",
            "      baz: qux",
            "    test.confListPlain:",
            "      - dogs",
            "      - cats",
            "      - badgers",
            "    test.confSetThing: !!set",
            "      ? square",
            "      ? circle",
            "      ? triangle",
            "    test.confObject: 5");
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

    @Test
    public void testConfigFromBrooklynConfigFlag() throws Exception {
        Entity app = createAndStartApplication("test-entity-basic-template.yaml",
            "  brooklyn.config:",
            "    confName: Foo Bar");
        waitForApplicationTasks(app);

        log.info("App started:");
        Entities.dumpInfo(app);

        Entity entity = app.getChildren().iterator().next();
        Assert.assertNotNull(entity, "Expected app to have child entity");
        Assert.assertTrue(entity instanceof TestEntity, "Expected TestEntity, found " + entity.getClass());
        TestEntity testEntity = (TestEntity) entity;
        Assert.assertEquals(testEntity.getConfig(TestEntity.CONF_NAME), "Foo Bar");
    }

    @Test
    public void testConfigFromTopLevelFlag() throws Exception {
        Entity app = createAndStartApplication("test-entity-basic-template.yaml", 
            "  confName: Foo Bar");
        waitForApplicationTasks(app);

        log.info("App started:");
        Entities.dumpInfo(app);

        Entity entity = app.getChildren().iterator().next();
        Assert.assertNotNull(entity, "Expected app to have child entity");
        Assert.assertTrue(entity instanceof TestEntity, "Expected TestEntity, found " + entity.getClass());
        TestEntity testEntity = (TestEntity) entity;
        Assert.assertEquals(testEntity.getConfig(TestEntity.CONF_NAME), "Foo Bar");
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testEmptyConfig() throws Exception {
        Entity app = createAndStartApplication("test-entity-basic-template.yaml",
            "  brooklyn.config:",
            "    test.confName: \"\"",
            "    test.confListPlain: !!seq []",
            "    test.confMapPlain: !!map {}",
            "    test.confSetPlain: !!set {}",
            "    test.confObject: \"\"");
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
    public void testEmptyStructuredConfig() throws Exception {
        Entity app = createAndStartApplication("test-entity-basic-template.yaml",
            "  brooklyn.config:",
            "    test.confName: \"\"",
            "    test.confListThing: !!seq []",
            "    test.confSetThing: !!set {}",
            "    test.confMapThing: !!map {}");
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
        Entity app = createAndStartApplication("test-entity-basic-template.yaml", 
            "  brooklyn.config:",
            "    test.confObject: $brooklyn:sensor(\"brooklyn.test.entity.TestEntity\", \"test.sequence\")");
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
        Entity app = createAndStartApplication("test-entity-basic-template.yaml",
            "  brooklyn.config:",
            "    test.confName: first entity",
            "  id: te1",
            "- serviceType: brooklyn.test.entity.TestEntity",
            "  name: second entity",
            "  brooklyn.config:",
            "    test.confObject: $brooklyn:component(\"te1\")");
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
        Entity app = createAndStartApplication("test-entity-basic-template.yaml", 
            "  brooklyn.config:",
            "    test.confName: first entity",
            "  brooklyn.children:",
            "  - serviceType: brooklyn.test.entity.TestEntity",
            "    name: Child Entity",
            "    brooklyn.config:",
            "      test.confName: Name of the first Child",
            "    brooklyn.children:",
            "    - serviceType: brooklyn.test.entity.TestEntity",
            "      name: Grandchild Entity",
            "      brooklyn.config:",
            "        test.confName: Name of the Grandchild",
            "  - serviceType: brooklyn.test.entity.TestEntity",
            "    name: Second Child",
            "    brooklyn.config:",
            "      test.confName: Name of the second Child");
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
        Entity app = createAndStartApplication("test-entity-basic-template.yaml",  
            "location: localhost:(name=yaml name)");
        waitForApplicationTasks(app);
        Assert.assertEquals(app.getLocations().size(), 1);
        Location location = app.getLocations().iterator().next();
        Assert.assertNotNull(location);
        Assert.assertEquals(location.getDisplayName(), "yaml name");
        Assert.assertEquals(app.getChildren().size(), 1);
        Entity entity = app.getChildren().iterator().next();
        Assert.assertNotNull(entity);
        Assert.assertEquals(entity.getLocations().size(), 1);
    }

    @Test
    public void testWithEntityLocation() throws Exception {
        Entity app = createAndStartApplication("test-entity-basic-template.yaml",  
            "  location: localhost:(name=yaml name)\n");
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
        Entity app = createAndStartApplication("test-entity-basic-template.yaml",  
            "locations:",
            "- localhost:(name=localhost name)",
            "- byon:(hosts=\"1.1.1.1\", name=byon name)");
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
        Assert.assertEquals(entity.getLocations().size(), 2);
    }

    @Test
    public void testWith2EntityLocations() throws Exception {
        Entity app = createAndStartApplication("test-entity-basic-template.yaml",  
            "  locations:",
            "  - localhost:(name=localhost name)",
            "  - byon:(hosts=\"1.1.1.1\", name=byon name)");
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
        Entity app = createAndStartApplication("test-entity-basic-template.yaml",  
            "  location: localhost:(name=localhost name)",
            "location: byon:(hosts=\"1.1.1.1\", name=byon name)");
        waitForApplicationTasks(app);
        Assert.assertEquals(app.getLocations().size(), 1);
        Assert.assertEquals(app.getChildren().size(), 1);
        Entity entity = app.getChildren().iterator().next();
        Assert.assertEquals(entity.getLocations().size(), 2);
        Location appLocation = app.getLocations().iterator().next();
        Assert.assertEquals(appLocation.getDisplayName(), "byon name");
        Location entityLocation = entity.getLocations().iterator().next();
        Assert.assertEquals(entityLocation.getDisplayName(), "localhost name");
    }

    @Test
    public void testCreateClusterWithMemberSpec() throws Exception {
        Entity app = createAndStartApplication("test-cluster-with-member-spec.yaml");
        waitForApplicationTasks(app);
        assertEquals(app.getChildren().size(), 1);

        Entity clusterEntity = Iterables.getOnlyElement(app.getChildren());
        assertTrue(clusterEntity instanceof DynamicCluster, "cluster="+clusterEntity);

        DynamicCluster cluster = DynamicCluster.class.cast(clusterEntity);
        assertEquals(cluster.getMembers().size(), 2, "members="+cluster.getMembers());

        for (Entity member : cluster.getMembers()) {
            assertTrue(member instanceof TestEntity, "member="+member);
            assertEquals(member.getConfig(TestEntity.CONF_NAME), "yamlTest");
        }
    }

    @Test
    public void testEntitySpecConfig() throws Exception {
        String yaml =
                "services:\n"+
                "- serviceType: brooklyn.test.entity.TestEntity\n"+
                "  brooklyn.config:\n"+
                "   test.childSpec:\n"+
                "     $brooklyn:entitySpec:\n"+
                "       type: brooklyn.test.entity.TestEntity\n"+
                "       brooklyn.config:\n"+
                "         test.confName: inchildspec\n";
        
        Application app = (Application) createStartWaitAndLogApplication(new StringReader(yaml));
        TestEntity entity = (TestEntity) Iterables.getOnlyElement(app.getChildren());
        
        TestEntity child = (TestEntity) entity.createAndManageChildFromConfig();
        assertEquals(child.getConfig(TestEntity.CONF_NAME), "inchildspec");
    }
    
    @Test
    public void testNestedEntitySpecConfigs() throws Exception {
        String yaml =
                "services:\n"+
                "- serviceType: brooklyn.test.entity.TestEntity\n"+
                "  brooklyn.config:\n"+
                "   test.childSpec:\n"+
                "     $brooklyn:entitySpec:\n"+
                "       type: brooklyn.test.entity.TestEntity\n"+
                "       brooklyn.config:\n"+
                "         test.confName: inchildspec\n"+
                "         test.childSpec:\n"+
                "           $brooklyn:entitySpec:\n"+
                "             type: brooklyn.test.entity.TestEntity\n"+
                "             brooklyn.config:\n"+
                "               test.confName: ingrandchildspec\n";
        
        Application app = (Application) createStartWaitAndLogApplication(new StringReader(yaml));
        TestEntity entity = (TestEntity) Iterables.getOnlyElement(app.getChildren());
        
        TestEntity child = (TestEntity) entity.createAndManageChildFromConfig();
        assertEquals(child.getConfig(TestEntity.CONF_NAME), "inchildspec");
        
        TestEntity grandchild = (TestEntity) child.createAndManageChildFromConfig();
        assertEquals(grandchild.getConfig(TestEntity.CONF_NAME), "ingrandchildspec");
    }
    
    @Test
    public void testEntitySpecInUnmatchedConfig() throws Exception {
        String yaml =
                "services:\n"+
                "- serviceType: brooklyn.test.entity.TestEntity\n"+
                "  brooklyn.config:\n"+
                "   key.does.not.match:\n"+
                "     $brooklyn:entitySpec:\n"+
                "       type: brooklyn.test.entity.TestEntity\n"+
                "       brooklyn.config:\n"+
                "         test.confName: inchildspec\n";
        
        Application app = (Application) createStartWaitAndLogApplication(new StringReader(yaml));
        TestEntity entity = (TestEntity) Iterables.getOnlyElement(app.getChildren());
        EntitySpec<?> entitySpec = (EntitySpec<?>) entity.getAllConfigBag().getStringKey("key.does.not.match");
        assertEquals(entitySpec.getType(), TestEntity.class);
        assertEquals(entitySpec.getConfig(), ImmutableMap.of(TestEntity.CONF_NAME, "inchildspec"));
    }

    @Test
    public void testAppWithSameServerEntityStarts() throws Exception {
        Entity app = createAndStartApplication("same-server-entity-test.yaml");
        waitForApplicationTasks(app);
        assertNotNull(app);
        assertEquals(app.getAttribute(Attributes.SERVICE_STATE), Lifecycle.RUNNING, "service state");
        assertTrue(app.getAttribute(Attributes.SERVICE_UP), "service up");

        assertEquals(app.getChildren().size(), 1);
        Entity entity = Iterables.getOnlyElement(app.getChildren());
        assertTrue(entity instanceof SameServerEntity, "entity="+entity);

        SameServerEntity sse = (SameServerEntity) entity;
        assertEquals(sse.getChildren().size(), 2);
        for (Entity child : sse.getChildren()) {
            assertTrue(child instanceof BasicEntity, "child="+child);
        }
    }
    
    @Test
    public void testEntityImplExposesAllInterfacesIncludingStartable() throws Exception {
        String yaml =
                "services:\n"+
                "- serviceType: brooklyn.test.entity.TestEntityImpl\n";
        
        Application app = (Application) createStartWaitAndLogApplication(new StringReader(yaml));
        TestEntity entity = (TestEntity) Iterables.getOnlyElement(app.getChildren());
        assertTrue(entity.getCallHistory().contains("start"), "history="+entity.getCallHistory());
    }

    @Override
    protected Logger getLogger() {
        return log;
    }

}
