package brooklyn.location.jclouds;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

import brooklyn.location.MachineProvisioningLocation;
import brooklyn.location.basic.LocationRegistry;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.location.jclouds.JcloudsResolver;
import brooklyn.location.jclouds.JcloudsSshMachineLocation;
import brooklyn.util.collections.MutableMap;

public class AwsJcloudsMinRamTest {

    private static final Logger log = LoggerFactory.getLogger(AwsJcloudsMinRamTest.class);
    
    @Test(groups="Live")
    public void testJcloudsCreateWithMinRam() throws Exception {
        JcloudsLocation l = JcloudsResolver.resolve("aws-ec2:us-east-1");
        l.configure(MutableMap.of("minRam", "4096"));
        
        JcloudsSshMachineLocation m1 = l.obtain();

        log.info("GOT "+m1);
        
        l.release(m1);
    }

//    @Test(groups="Live")
//    public void testJcloudsCreateNamedJungleBig() throws Exception {
//        @SuppressWarnings("unchecked")
//        MachineProvisioningLocation<SshMachineLocation> l = (MachineProvisioningLocation<SshMachineLocation>) new LocationRegistry().resolve("named:jungle-big");
//        
//        SshMachineLocation m1 = l.obtain(MutableMap.<String,String>of());
//
//        log.info("GOT "+m1);
//        
//        l.release(m1);
//    }

}
