package brooklyn.entity.group;

import static brooklyn.entity.basic.EntityPredicates.displayNameEqualTo;
import static brooklyn.entity.basic.EntityPredicates.isChildOf;
import static brooklyn.entity.group.DynamicMultiGroup.BUCKET_FUNCTION;
import static brooklyn.entity.group.DynamicMultiGroup.ENTITY_FILTER;
import static brooklyn.entity.group.DynamicMultiGroupImpl.bucketFromAttribute;
import static com.google.common.collect.Iterables.find;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.entity.Group;
import brooklyn.entity.basic.ApplicationBuilder;
import brooklyn.entity.basic.BasicGroup;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.event.AttributeSensor;
import brooklyn.event.SensorEvent;
import brooklyn.event.SensorEventListener;
import brooklyn.event.basic.Sensors;
import brooklyn.location.basic.SimulatedLocation;
import brooklyn.test.Asserts;
import brooklyn.test.entity.TestApplication;
import brooklyn.test.entity.TestEntity;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;


public class DynamicMultiGroupTest {

    private static final AttributeSensor<String> SENSOR = Sensors.newSensor(String.class, "multigroup.test");

    private TestApplication app;


    @BeforeMethod(alwaysRun = true)
    public void setUp() {
        app = ApplicationBuilder.newManagedApp(TestApplication.class);
        app.start(ImmutableList.of(new SimulatedLocation()));
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown(){
        if (app != null)
            Entities.destroyAll(app.getManagementContext());
    }

    @Test
    public void testDistributionBySensor() {
        final Group source = app.createAndManageChild(EntitySpec.create(BasicGroup.class));
        final DynamicMultiGroup dmg = app.createAndManageChild(
                EntitySpec.create(DynamicMultiGroup.class)
                .configure(ENTITY_FILTER, isChildOf(source))
                .configure(BUCKET_FUNCTION, bucketFromAttribute(SENSOR))
        );
        app.subscribeToChildren(source, SENSOR, new SensorEventListener<String>() {
            public void onEvent(SensorEvent<String> event) { dmg.distributeEntities(); }
        });

        final EntitySpec<TestEntity> childSpec = EntitySpec.create(TestEntity.class);
        final TestEntity child1 = source.addChild(EntitySpec.create(childSpec).displayName("child1"));
        final TestEntity child2 = source.addChild(EntitySpec.create(childSpec).displayName("child2"));
        Entities.manage(child1);
        Entities.manage(child2);

        // Start with both children in bucket A; there is no bucket B
        child1.setAttribute(SENSOR, "bucketA");
        child2.setAttribute(SENSOR, "bucketA");
        Asserts.succeedsEventually(new Runnable() {
            public void run() {
                Group bucketA = (Group) find(dmg.getChildren(), displayNameEqualTo("bucketA"), null);
                Group bucketB = (Group) find(dmg.getChildren(), displayNameEqualTo("bucketB"), null);
                assertNotNull(bucketA);
                assertNull(bucketB);
                assertEquals(ImmutableSet.copyOf(bucketA.getMembers()), ImmutableSet.of(child1, child2));
            }
        });

        // Move child 1 into bucket B
        child1.setAttribute(SENSOR, "bucketB");
        Asserts.succeedsEventually(new Runnable() {
            public void run() {
                Group bucketA = (Group) find(dmg.getChildren(), displayNameEqualTo("bucketA"), null);
                Group bucketB = (Group) find(dmg.getChildren(), displayNameEqualTo("bucketB"), null);
                assertNotNull(bucketA);
                assertNotNull(bucketB);
                assertEquals(ImmutableSet.copyOf(bucketB.getMembers()), ImmutableSet.of(child1));
                assertEquals(ImmutableSet.copyOf(bucketA.getMembers()), ImmutableSet.of(child2));
            }
        });

        // Move child 2 into bucket B; there is now no bucket A
        child2.setAttribute(SENSOR, "bucketB");
        Asserts.succeedsEventually(new Runnable() {
            public void run() {
                Group bucketA = (Group) find(dmg.getChildren(), displayNameEqualTo("bucketA"), null);
                Group bucketB = (Group) find(dmg.getChildren(), displayNameEqualTo("bucketB"), null);
                assertNull(bucketA);
                assertNotNull(bucketB);
                assertEquals(ImmutableSet.copyOf(bucketB.getMembers()), ImmutableSet.of(child1, child2));
            }
        });

        // Add new child 3, associated with new bucket C
        final TestEntity child3 = source.addChild(EntitySpec.create(childSpec).displayName("child3"));
        Entities.manage(child3);
        child3.setAttribute(SENSOR, "bucketC");
        Asserts.succeedsEventually(new Runnable() {
            public void run() {
                Group bucketC = (Group) find(dmg.getChildren(), displayNameEqualTo("bucketC"), null);
                assertNotNull(bucketC);
                assertEquals(ImmutableSet.copyOf(bucketC.getMembers()), ImmutableSet.of(child3));
            }
        });

        // Un-set the sensor on child 3 -- gets removed from bucket C, which then
        // disappears as it is empty.
        child3.setAttribute(SENSOR, null);
        Asserts.succeedsEventually(new Runnable() {
            public void run() {
                Group bucketB = (Group) find(dmg.getChildren(), displayNameEqualTo("bucketB"), null);
                Group bucketC = (Group) find(dmg.getChildren(), displayNameEqualTo("bucketC"), null);
                assertNotNull(bucketB);
                assertNull(bucketC);
                assertEquals(ImmutableSet.copyOf(bucketB.getMembers()), ImmutableSet.of(child1, child2));
            }
        });
    }

}
