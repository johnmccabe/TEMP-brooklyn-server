package brooklyn.extras.whirr.core

import static com.google.common.collect.Iterables.getOnlyElement

import org.apache.commons.configuration.PropertiesConfiguration
import org.apache.whirr.Cluster
import org.apache.whirr.ClusterController
import org.apache.whirr.ClusterControllerFactory
import org.apache.whirr.ClusterSpec
import org.apache.whirr.ClusterSpec.Property
import org.jclouds.scriptbuilder.domain.OsFamily
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import brooklyn.entity.Entity
import brooklyn.entity.basic.AbstractEntity
import brooklyn.entity.proxying.BasicEntitySpec;
import brooklyn.location.Location
import brooklyn.location.basic.LocalhostMachineProvisioningLocation
import brooklyn.location.basic.LocationConfigUtils;
import brooklyn.location.jclouds.JcloudsLocation

/**
 * Generic entity that can be used to deploy clusters that are
 * managed by Apache Whirr.
 *
 */
public class WhirrClusterImpl extends AbstractEntity implements WhirrCluster {

    public static final Logger log = LoggerFactory.getLogger(WhirrClusterImpl.class);

    protected ClusterController _controller = null
    protected ClusterSpec clusterSpec = null
    protected Cluster cluster = null

    protected Location location = null

    /**
     * General entity initialisation
     */
    public WhirrClusterImpl(Map flags = [:], Entity parent = null) {
        super(flags, parent)
    }

    @Override
    public ClusterSpec getClusterSpec() {
        return clusterSpec;
    }
    
    @Override
    public Cluster getCluster() {
        return cluster;
    }
    
    /**
     * Apache Whirr can only start and manage a cluster in a single location
     *
     * @param locations
     */
    void start(Collection<? extends Location> locations) {
        location = getOnlyElement(locations)
        startInLocation(location)
    }

    /**
     * Start a cluster as specified in the recipe on localhost
     *
     * @param location corresponding to localhost
     */
    void startInLocation(LocalhostMachineProvisioningLocation location) {

        PropertiesConfiguration config = new PropertiesConfiguration()
        config.load(new StringReader(getConfig(RECIPE)))

        StringBuilder nodes = []
        nodes.with {
            append "nodes:\n"
            for (int i=0; i<10; i++) {
                String mid = (i==0?"":(""+(i+1)));
                append "    - id: localhost"+mid+"\n"
                append "      name: local machine "+mid+"\n"
                append "      hostname: 127.0.0.1\n"
                append "      os_arch: "+System.getProperty("os.arch")+"\n"
                append "      os_family: "+OsFamily.UNIX+"\n"
                append "      os_description: "+System.getProperty("os.name")+"\n"
                append "      os_version: "+System.getProperty("os.version")+"\n"
                append "      group: whirr\n"
                append "      tags:\n"
                append "          - local\n"
                append "      username: "+System.getProperty("user.name")+"\n" //NOTE: needs passwordless sudo!!!
                append "      credential_url: file://"+System.getProperty("user.home")+"/.ssh/id_rsa\n"
            }
        }

        //provide the BYON nodes to whirr
        config.setProperty("jclouds.byon.nodes", nodes.toString())
        config.setProperty(ClusterSpec.Property.LOCATION_ID.getConfigName(), "byon");

        clusterSpec = new ClusterSpec(config)

        clusterSpec.setServiceName("byon")
        clusterSpec.setProvider("byon")
        clusterSpec.setIdentity("notused")
        clusterSpec.setCredential("notused")

        log.info("Starting cluster with roles " + config.getProperty("whirr.instance-templates")
                + " in location " + location)

        startWithClusterSpec(clusterSpec,config);
    }

    /**
     * Start a cluster as specified in the recipe in a given location
     *
     * @param location jclouds location spec
     */
    void startInLocation(JcloudsLocation location) {
        PropertiesConfiguration config = new PropertiesConfiguration()
        config.load(new StringReader(getConfig(RECIPE)))

        customizeClusterSpecConfiguration(location, config);        

        clusterSpec = new ClusterSpec(config)
        clusterSpec.setProvider(location.getProvider())
        clusterSpec.setIdentity(location.getIdentity())
        clusterSpec.setCredential(location.getCredential())
        // TODO inherit key data?
        clusterSpec.setPrivateKey(LocationConfigUtils.getPrivateKeyData(location.getConfigBag()));
        clusterSpec.setPublicKey(LocationConfigUtils.getPublicKeyData(location.getConfigBag()));
        // TODO: also add security groups when supported in the Whirr trunk

        startWithClusterSpec(clusterSpec, config);
    }

    protected void customizeClusterSpecConfiguration(JcloudsLocation location, PropertiesConfiguration config) {
        if (location.getRegion())
            config.setProperty(ClusterSpec.Property.LOCATION_ID.getConfigName(), location.getRegion());
    }
    
    public synchronized ClusterController getController() {
        if (_controller==null) {
            _controller = new ClusterControllerFactory().create(clusterSpec?.getServiceName());
        }
        return _controller;
    }

    void startWithClusterSpec(ClusterSpec clusterSpec, PropertiesConfiguration config) {
        log.info("Starting cluster "+this+" with roles " + config.getProperty("whirr.instance-templates")
                + " in location " + location)
        if (log.isDebugEnabled()) log.debug("Cluster "+this+" using recipe:\n"+getConfig(RECIPE));
        
        cluster = controller.launchCluster(clusterSpec)

        for (Cluster.Instance instance : cluster.getInstances()) {
            log.info("Creating group for instance " + instance.id)
            def rolesGroup = 
                addChild(getEntityManager().createEntity(BasicEntitySpec.newInstance(WhirrInstance.class).
                    displayName("Instance:" + instance.id).
                    configure("instance", instance)) );

            for (String role: instance.roles) {
                log.info("Creating entity for '" + role + "' on instance " + instance.id)
                rolesGroup.addChild(
                    getEntityManager().createEntity(BasicEntitySpec.newInstance(WhirrRole.class).
                        displayName("Role:" + role).
                        configure("role", role)) );
            }
            addGroup(rolesGroup)
        }

        setAttribute(CLUSTER_NAME, clusterSpec.getClusterName());
        setAttribute(SERVICE_UP, true);
    }

    void stop() {
        if (clusterSpec != null) {
            controller.destroyCluster(clusterSpec)
        }
        clusterSpec = null
        cluster = null
    }

    void restart() {
        // TODO better would be to restart the software instances, not the machines ?
        stop();
        start([location]);
    }
}
