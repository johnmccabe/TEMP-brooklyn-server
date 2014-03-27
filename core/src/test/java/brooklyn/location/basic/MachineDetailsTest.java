package brooklyn.location.basic;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.entity.basic.ApplicationBuilder;
import brooklyn.entity.basic.Entities;
import brooklyn.location.LocationSpec;
import brooklyn.location.MachineDetails;
import brooklyn.location.OsDetails;
import brooklyn.management.ManagementContext;
import brooklyn.management.Task;
import brooklyn.test.entity.TestApplication;
import brooklyn.util.task.DynamicSequentialTask;
import brooklyn.util.task.DynamicTasks;

public class MachineDetailsTest {

    private static final Logger LOG = LoggerFactory.getLogger(SshMachineLocationTest.class);

    TestApplication app;
    ManagementContext mgmt;
    SshMachineLocation host;

    @BeforeMethod(alwaysRun=true)
    public void setup() throws Exception {
        DynamicSequentialTask<Object> t = new DynamicSequentialTask<Object>();
        DynamicTasks.setTaskQueueingContext(t);
        app = ApplicationBuilder.newManagedApp(TestApplication.class);
        mgmt = app.getManagementContext();

        LocalhostMachineProvisioningLocation localhost = mgmt.getLocationManager().createLocation(
                LocationSpec.create(LocalhostMachineProvisioningLocation.class));
        host = localhost.obtain();
        app.start(Arrays.asList(host));
    }

    @AfterMethod(alwaysRun=true)
    public void tearDown() throws Exception {
        if (mgmt != null) Entities.destroyAll(mgmt);
        mgmt = null;
    }

    @Test(groups = "Integration")
    public void testGetMachineDetails() {
        Task<BasicMachineDetails> detailsTask = app.getExecutionContext().submit(
                BasicMachineDetails.taskForSshMachineLocation(host));
        MachineDetails machine = detailsTask.getUnchecked();
        LOG.info("Found the following on localhost: {}", machine);
        assertNotNull(machine);
        OsDetails details = machine.getOsDetails();
        assertNotNull(details);
        assertNotNull(details.getArch());
        assertNotNull(details.getName());
        assertNotNull(details.getVersion());
        assertFalse(details.getArch().startsWith("architecture:"), "architecture prefix not removed from details");
        assertFalse(details.getName().startsWith("name:"), "name prefix not removed from details");
        assertFalse(details.getVersion().startsWith("version:"), "version prefix not removed from details");
    }
}
