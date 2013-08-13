package brooklyn.location.jclouds;

import static org.testng.Assert.assertEquals;

import java.io.File;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.config.BrooklynProperties;
import brooklyn.entity.basic.Entities;
import brooklyn.location.LocationSpec;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.management.ManagementContext;
import brooklyn.management.internal.LocalManagementContext;
import brooklyn.util.ResourceUtils;
import brooklyn.util.collections.MutableMap;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

/**
 * Tests different login options for ssh keys, passwords, etc.
 */
public class JcloudsLoginLiveTest {

    private static final Logger LOG = LoggerFactory.getLogger(JcloudsLoginLiveTest.class);

    public static final String BROOKLYN_PROPERTIES_PREFIX = "brooklyn.jclouds.";
    
    public static final String AWS_EC2_PROVIDER = "aws-ec2";
    public static final String AWS_EC2_REGION_NAME = "us-east-1";
    public static final String AWS_EC2_TINY_HARDWARE_ID = "t1.micro";
    public static final String AWS_EC2_SMALL_HARDWARE_ID = "m1.small";
    public static final String AWS_EC2_LOCATION_SPEC = "jclouds:" + AWS_EC2_PROVIDER + (AWS_EC2_REGION_NAME == null ? "" : ":" + AWS_EC2_REGION_NAME);
    
    // Image: {id=us-east-1/ami-7d7bfc14, providerId=ami-7d7bfc14, name=RightImage_CentOS_6.3_x64_v5.8.8.5, location={scope=REGION, id=us-east-1, description=us-east-1, parent=aws-ec2, iso3166Codes=[US-VA]}, os={family=centos, arch=paravirtual, version=6.0, description=rightscale-us-east/RightImage_CentOS_6.3_x64_v5.8.8.5.manifest.xml, is64Bit=true}, description=rightscale-us-east/RightImage_CentOS_6.3_x64_v5.8.8.5.manifest.xml, version=5.8.8.5, status=AVAILABLE[available], loginUser=root, userMetadata={owner=411009282317, rootDeviceType=instance-store, virtualizationType=paravirtual, hypervisor=xen}}
    public static final String AWS_EC2_CENTOS_IMAGE_ID = "us-east-1/ami-7d7bfc14";

    // Image: {id=us-east-1/ami-950680fc, providerId=ami-950680fc, name=RightImage_Ubuntu_12.04_x64_v5.8.8, location={scope=REGION, id=us-east-1, description=us-east-1, parent=aws-ec2, iso3166Codes=[US-VA]}, os={family=ubuntu, arch=paravirtual, version=12.04, description=rightscale-us-east/RightImage_Ubuntu_12.04_x64_v5.8.8.manifest.xml, is64Bit=true}, description=rightscale-us-east/RightImage_Ubuntu_12.04_x64_v5.8.8.manifest.xml, version=5.8.8, status=AVAILABLE[available], loginUser=root, userMetadata={owner=411009282317, rootDeviceType=instance-store, virtualizationType=paravirtual, hypervisor=xen}}
    public static final String AWS_EC2_UBUNTU_IMAGE_ID = "us-east-1/ami-950680fc";

    public static final String RACKSPACE_PROVIDER = "rackspace-cloudservers-uk";
    public static final String RACKSPACE_LOCATION_SPEC = "jclouds:" + RACKSPACE_PROVIDER;
    
    public static final String RACKSPACE_CENTOS_IMAGE_NAME_REGEX = "CentOS 6.0";
    public static final String RACKSPACE_DEBIAN_IMAGE_NAME_REGEX = "Debian 6";
    
    protected BrooklynProperties brooklynProperties;
    protected ManagementContext managementContext;
    
    protected JcloudsLocation jcloudsLocation;
    protected JcloudsSshMachineLocation machine;
    
    private File privateRsaFile = new File(ResourceUtils.tidyFilePath("~/.ssh/id_rsa"));
    private File privateDsaFile = new File(ResourceUtils.tidyFilePath("~/.ssh/id_dsa"));
    private File privateRsaFileTmp = new File(privateRsaFile.getAbsoluteFile()+".tmp");
    private File privateDsaFileTmp = new File(privateDsaFile.getAbsoluteFile()+".tmp");
    private File publicRsaFile = new File(ResourceUtils.tidyFilePath("~/.ssh/id_rsa.pub"));
    private File publicDsaFile = new File(ResourceUtils.tidyFilePath("~/.ssh/id_dsa.pub"));
    private File publicRsaFileTmp = new File(publicRsaFile.getAbsoluteFile()+".tmp");
    private File publicDsaFileTmp = new File(publicDsaFile.getAbsoluteFile()+".tmp");
    private boolean privateRsaFileMoved;
    private boolean privateDsaFileMoved;
    private boolean publicRsaFileMoved;
    private boolean publicDsaFileMoved;
    
    @BeforeMethod(alwaysRun=true)
    public void setUp() throws Exception {
        // Don't let any defaults from brooklyn.properties (except credentials) interfere with test
        brooklynProperties = BrooklynProperties.Factory.newDefault();
        for (String key : ImmutableSet.copyOf(brooklynProperties.asMapWithStringKeys().keySet())) {
            if (key.startsWith("brooklyn.jclouds") && !(key.endsWith("identity") || key.endsWith("credential"))) {
                brooklynProperties.remove(key);
            }
            
            // Also removes scriptHeader (e.g. if doing `. ~/.bashrc` and `. ~/.profile`, then that can cause "stdin: is not a tty")
            if (key.startsWith("brooklyn.ssh")) {
                brooklynProperties.remove(key);
            }
        }
        
        managementContext = new LocalManagementContext(brooklynProperties);
    }

    @AfterMethod(alwaysRun=true)
    public void tearDown() throws Exception {
        try {
            if (machine != null) jcloudsLocation.release(machine);
            machine = null;
        } finally {
            if (managementContext != null) Entities.destroyAll(managementContext);
        }
    }

    @Test(groups = {"Live"})
    protected void testAwsEc2SpecifyingJustPrivateSshKeyInDeprecatedForm() throws Exception {
        brooklynProperties.put(BROOKLYN_PROPERTIES_PREFIX+JcloudsLocationConfig.LEGACY_PRIVATE_KEY_FILE.getName(), "~/.ssh/id_rsa");
        jcloudsLocation = (JcloudsLocation) managementContext.getLocationRegistry().resolve(AWS_EC2_LOCATION_SPEC);
        
        machine = createEc2Machine(ImmutableMap.<String,Object>of());
        assertSshable(machine);
    }
    
    @Test(groups = {"Live"})
    protected void testAwsEc2SpecifyingPrivateAndPublicSshKeyInDeprecatedForm() throws Exception {
        brooklynProperties.put(BROOKLYN_PROPERTIES_PREFIX+JcloudsLocationConfig.LEGACY_PRIVATE_KEY_FILE.getName(), "~/.ssh/id_rsa");
        brooklynProperties.put(BROOKLYN_PROPERTIES_PREFIX+JcloudsLocationConfig.LEGACY_PUBLIC_KEY_FILE.getName(), "~/.ssh/id_rsa.pub");
        jcloudsLocation = (JcloudsLocation) managementContext.getLocationRegistry().resolve(AWS_EC2_LOCATION_SPEC);
        
        machine = createEc2Machine(ImmutableMap.<String,Object>of());
        assertSshable(machine);
    }
    
    @Test(groups = {"Live"})
    protected void testAwsEc2SpecifyingNoKeyFiles() throws Exception {
        jcloudsLocation = (JcloudsLocation) managementContext.getLocationRegistry().resolve(AWS_EC2_LOCATION_SPEC);
        
        machine = createEc2Machine(ImmutableMap.<String,Object>of());
        assertSshable(machine);
    }
    
    @Test(groups = {"Live"})
    public void testSpecifyingPasswordAndNoDefaultKeyFilesExist() throws Exception {
        try {
            moveSshKeyFiles();
            
            brooklynProperties.put(BROOKLYN_PROPERTIES_PREFIX+JcloudsLocationConfig.PASSWORD.getName(), "mypassword");
            jcloudsLocation = (JcloudsLocation) managementContext.getLocationRegistry().resolve(RACKSPACE_LOCATION_SPEC);
            
            machine = createRackspaceMachine(ImmutableMap.of("imageNameRegex", RACKSPACE_DEBIAN_IMAGE_NAME_REGEX));
            assertSshable(machine);
        } finally {
            restoreSshKeyFiles();
        }
    }

    @Test(groups = {"Live"})
    protected void testSpecifyingNothingAndNoDefaultKeyFilesExist() throws Exception {
        try {
            moveSshKeyFiles();
            
            jcloudsLocation = (JcloudsLocation) managementContext.getLocationRegistry().resolve(RACKSPACE_LOCATION_SPEC);
            
            machine = createRackspaceMachine(ImmutableMap.of("imageNameRegex", RACKSPACE_DEBIAN_IMAGE_NAME_REGEX));
            assertSshable(machine);
        } finally {
            restoreSshKeyFiles();
        }
    }

    @Test(groups = {"Live"})
    protected void testSpecifyingPasswordAndSshKeysPrefersKeys() throws Exception {
        brooklynProperties.put(BROOKLYN_PROPERTIES_PREFIX+JcloudsLocationConfig.PRIVATE_KEY_FILE.getName(), "~/.ssh/id_rsa");
        brooklynProperties.put(BROOKLYN_PROPERTIES_PREFIX+JcloudsLocationConfig.PUBLIC_KEY_FILE.getName(), "~/.ssh/id_rsa.pub");
        brooklynProperties.put(BROOKLYN_PROPERTIES_PREFIX+JcloudsLocationConfig.PASSWORD.getName(), "mypassword");
        jcloudsLocation = (JcloudsLocation) managementContext.getLocationRegistry().resolve(RACKSPACE_LOCATION_SPEC);
        
        machine = createRackspaceMachine(ImmutableMap.of("imageNameRegex", RACKSPACE_DEBIAN_IMAGE_NAME_REGEX));
        assertSshable(machine);
        
        SshMachineLocation machineUsingKey = managementContext.getLocationManager().createLocation(LocationSpec.create(SshMachineLocation.class)
                .configure("address", machine.getAddress())
                .configure("user", machine.getUser())
                .configure(SshMachineLocation.PRIVATE_KEY_FILE, ResourceUtils.tidyFilePath("~/.ssh/id_rsa")));
        
        assertSshable(machineUsingKey);
        
        SshMachineLocation machineUsingPassword = managementContext.getLocationManager().createLocation(LocationSpec.create(SshMachineLocation.class)
                .configure("address", machine.getAddress())
                .configure("user", machine.getUser())
                .configure(SshMachineLocation.PASSWORD, "mypassword"));
        assertSshable(machineUsingPassword);
    }

    @Test(groups = {"Live"})
    protected void testSpecifyingPasswordWhenDefaultSshKeysExistPrefersKeys() throws Exception {
        brooklynProperties.put(BROOKLYN_PROPERTIES_PREFIX+JcloudsLocationConfig.PASSWORD.getName(), "mypassword");
        jcloudsLocation = (JcloudsLocation) managementContext.getLocationRegistry().resolve(RACKSPACE_LOCATION_SPEC);
        
        machine = createRackspaceMachine(ImmutableMap.of("imageNameRegex", RACKSPACE_DEBIAN_IMAGE_NAME_REGEX));
        assertSshable(machine);
        
        SshMachineLocation machineUsingKey = managementContext.getLocationManager().createLocation(LocationSpec.create(SshMachineLocation.class)
                .configure("address", machine.getAddress())
                .configure("user", machine.getUser())
                .configure(SshMachineLocation.PRIVATE_KEY_FILE, ResourceUtils.tidyFilePath("~/.ssh/id_rsa")));
        
        assertSshable(machineUsingKey);
        
        SshMachineLocation machineUsingPassword = managementContext.getLocationManager().createLocation(LocationSpec.create(SshMachineLocation.class)
                .configure("address", machine.getAddress())
                .configure("user", machine.getUser())
                .configure(SshMachineLocation.PASSWORD, "mypassword"));
        assertSshable(machineUsingPassword);
    }

    @Test(groups = {"Live"})
    protected void testAwsEc2SpecifyingRootUser() throws Exception {
        // Image: {id=us-east-1/ami-5e008437, providerId=ami-5e008437, name=RightImage_Ubuntu_10.04_x64_v5.8.8.3, location={scope=REGION, id=us-east-1, description=us-east-1, parent=aws-ec2, iso3166Codes=[US-VA]}, os={family=ubuntu, arch=paravirtual, version=10.04, description=rightscale-us-east/RightImage_Ubuntu_10.04_x64_v5.8.8.3.manifest.xml, is64Bit=true}, description=rightscale-us-east/RightImage_Ubuntu_10.04_x64_v5.8.8.3.manifest.xml, version=5.8.8.3, status=AVAILABLE[available], loginUser=root, userMetadata={owner=411009282317, rootDeviceType=instance-store, virtualizationType=paravirtual, hypervisor=xen}}
        // Uses "root" as loginUser
        String imageId = "us-east-1/ami-5e008437";
        
        brooklynProperties.put(BROOKLYN_PROPERTIES_PREFIX+JcloudsLocationConfig.PRIVATE_KEY_FILE.getName(), "~/.ssh/id_rsa");
        brooklynProperties.put(BROOKLYN_PROPERTIES_PREFIX+JcloudsLocationConfig.PUBLIC_KEY_FILE.getName(), "~/.ssh/id_rsa.pub");
        brooklynProperties.put(BROOKLYN_PROPERTIES_PREFIX+JcloudsLocationConfig.USER.getName(), "root");
        jcloudsLocation = (JcloudsLocation) managementContext.getLocationRegistry().resolve(AWS_EC2_LOCATION_SPEC);
        
        machine = createEc2Machine(ImmutableMap.<String,Object>of("imageId", imageId));
        assertSshable(machine);
    }
    
    private JcloudsSshMachineLocation createEc2Machine(Map<String,? extends Object> conf) throws Exception {
        return createMachine(MutableMap.<String,Object>builder()
                .putAll(conf)
                .putIfAbsent("imageId", AWS_EC2_CENTOS_IMAGE_ID)
                .putIfAbsent("hardwareId", AWS_EC2_SMALL_HARDWARE_ID)
                .putIfAbsent("inboundPorts", ImmutableList.of(22))
                .build());
    }
    
    private JcloudsSshMachineLocation createRackspaceMachine(Map<String,? extends Object> conf) throws Exception {
        return createMachine(MutableMap.<String,Object>builder()
                .putAll(conf)
                .putIfAbsent("inboundPorts", ImmutableList.of(22))
                .build());
    }
    
    private JcloudsSshMachineLocation createMachine(Map<String,? extends Object> conf) throws Exception {
        return jcloudsLocation.obtain(conf);
    }
    
    private void assertSshable(SshMachineLocation machine) {
        int result = machine.execScript("simplecommand", ImmutableList.of("true"));
        assertEquals(result, 0);
    }
    
    private void moveSshKeyFiles() throws Exception {
        privateRsaFileMoved = false;
        privateDsaFileMoved = false;
        publicRsaFileMoved = false;
        publicDsaFileMoved = false;

        if (privateRsaFile.exists()) {
            LOG.info("Moving {} to {}", privateRsaFile, privateRsaFileTmp);
            Runtime.getRuntime().exec("mv "+privateRsaFile.getAbsolutePath()+" "+privateRsaFileTmp.getAbsolutePath());
            privateRsaFileMoved = true;
        }
        if (privateDsaFile.exists()) {
            LOG.info("Moving {} to {}", privateDsaFile, privateDsaFileTmp);
            Runtime.getRuntime().exec("mv "+privateDsaFile.getAbsolutePath()+" "+privateDsaFileTmp.getAbsolutePath());
            privateDsaFileMoved = true;
        }
        if (publicRsaFile.exists()) {
            LOG.info("Moving {} to {}", publicRsaFile, publicRsaFileTmp);
            Runtime.getRuntime().exec("mv "+publicRsaFile.getAbsolutePath()+" "+publicRsaFileTmp.getAbsolutePath());
            publicRsaFileMoved = true;
        }
        if (publicDsaFile.exists()) {
            LOG.info("Moving {} to {}", publicDsaFile, publicDsaFileTmp);
            Runtime.getRuntime().exec("mv "+publicDsaFile.getAbsolutePath()+" "+publicDsaFileTmp.getAbsolutePath());
            publicDsaFileMoved = true;
        }
    }
    
    private void restoreSshKeyFiles() throws Exception {
        if (privateRsaFileMoved) {
            LOG.info("Restoring {} form {}", privateRsaFile, privateRsaFileTmp);
            Runtime.getRuntime().exec("mv "+privateRsaFileTmp.getAbsolutePath()+" "+privateRsaFile.getAbsolutePath());
            privateRsaFileMoved = false;
        }
        if (privateDsaFileMoved) {
            LOG.info("Restoring {} form {}", privateDsaFile, privateDsaFileTmp);
            Runtime.getRuntime().exec("mv "+privateDsaFileTmp.getAbsolutePath()+" "+privateDsaFile.getAbsolutePath());
            privateDsaFileMoved = false;
        }
        if (publicRsaFileMoved) {
            LOG.info("Restoring {} form {}", publicRsaFile, publicRsaFileTmp);
            Runtime.getRuntime().exec("mv "+publicRsaFileTmp.getAbsolutePath()+" "+publicRsaFile.getAbsolutePath());
            publicRsaFileMoved = false;
        }
        if (publicDsaFileMoved) {
            LOG.info("Restoring {} form {}", publicDsaFile, publicDsaFileTmp);
            Runtime.getRuntime().exec("mv "+publicDsaFileTmp.getAbsolutePath()+" "+publicDsaFile.getAbsolutePath());
            publicDsaFileMoved = false;
        }
    }
}
