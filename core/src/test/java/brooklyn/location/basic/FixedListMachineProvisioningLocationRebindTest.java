package brooklyn.location.basic;

import static org.testng.Assert.assertEquals;

import java.util.Set;

import javax.annotation.Nullable;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.entity.rebind.RebindTestUtils;
import brooklyn.location.Location;
import brooklyn.test.entity.TestApplication;
import brooklyn.util.MutableSet;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

public class FixedListMachineProvisioningLocationRebindTest {

    private FixedListMachineProvisioningLocation<SshMachineLocation> origLoc;
    private TestApplication origApp;
    
    @BeforeMethod
    public void setUp() throws Exception {
    	origLoc = new FixedListMachineProvisioningLocation.Builder()
    			.addAddresses("localhost", "localhost2")
    			.user("myuser")
    			.keyFile("/path/to/myPrivateKeyFile")
    			.keyData("myKeyData")
    			.keyPassphrase("myKeyPassphrase")
    			.build();
    	origApp = new TestApplication();
    	origApp.start(ImmutableList.of(origLoc));
    	origApp.getManagementContext().manage(origApp);
    }

    @Test
    public void testRebindPreservesConfig() throws Exception {
    	TestApplication newApp = (TestApplication) RebindTestUtils.serializeRebindAndManage(origApp, getClass().getClassLoader());
    	FixedListMachineProvisioningLocation<SshMachineLocation> newLoc = (FixedListMachineProvisioningLocation<SshMachineLocation>) Iterables.get(newApp.getLocations(), 0);
    	
    	assertEquals(newLoc.getId(), origLoc.getId());
    	assertEquals(newLoc.getName(), origLoc.getName());
    	assertEquals(newLoc.getHostGeoInfo(), origLoc.getHostGeoInfo());
    	assertEquals(newLoc.getLocationProperty("user"), origLoc.getLocationProperty("user"));
    	assertEquals(newLoc.getLocationProperty("privateKeyPassphrase"), origLoc.getLocationProperty("privateKeyPassphrase"));
    	assertEquals(newLoc.getLocationProperty("privateKeyFile"), origLoc.getLocationProperty("privateKeyFile"));
    	assertEquals(newLoc.getLocationProperty("privateKeyData"), origLoc.getLocationProperty("privateKeyData"));
    }

    @Test
    public void testRebindParentRelationship() throws Exception {
    	TestApplication newApp = (TestApplication) RebindTestUtils.serializeRebindAndManage(origApp, getClass().getClassLoader());
    	FixedListMachineProvisioningLocation<SshMachineLocation> newLoc = (FixedListMachineProvisioningLocation<SshMachineLocation>) Iterables.get(newApp.getLocations(), 0);
    	
    	assertLocationIdsEqual(newLoc.getChildLocations(), origLoc.getChildLocations());
    	assertEquals(Iterables.get(newLoc.getChildLocations(), 0).getParentLocation(), newLoc);
    	assertEquals(Iterables.get(newLoc.getChildLocations(), 1).getParentLocation(), newLoc);
    }

    @Test
    public void testRebindPreservesInUseMachines() throws Exception {
    	SshMachineLocation inuseMachine = origLoc.obtain();
    	
    	TestApplication newApp = (TestApplication) RebindTestUtils.serializeRebindAndManage(origApp, getClass().getClassLoader());
    	FixedListMachineProvisioningLocation<SshMachineLocation> newLoc = (FixedListMachineProvisioningLocation<SshMachineLocation>) Iterables.get(newApp.getLocations(), 0);
    	
    	assertLocationIdsEqual(newLoc.getInUse(), origLoc.getInUse());
    	assertLocationIdsEqual(newLoc.getAvailable(), origLoc.getAvailable());
    }

    private void assertLocationIdsEqual(Iterable<? extends Location> actual, Iterable<? extends Location> expected) {
    	Function<Location, String> locationIdFunction = new Function<Location, String>() {
			@Override public String apply(@Nullable Location input) {
				return (input != null) ? input.getId() : null;
			}
    	};
    	Set<String> actualIds = MutableSet.copyOf(Iterables.transform(actual, locationIdFunction));
    	Set<String> expectedIds = MutableSet.copyOf(Iterables.transform(expected, locationIdFunction));
    	
    	assertEquals(actualIds, expectedIds);
    }
}
