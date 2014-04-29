package brooklyn.location.jclouds.networking;

import java.util.Set;

import org.jclouds.aws.ec2.AWSEC2Api;
import org.jclouds.aws.util.AWSUtils;
import org.jclouds.compute.domain.SecurityGroup;
import org.jclouds.compute.extensions.SecurityGroupExtension;
import org.jclouds.net.domain.IpPermission;
import org.jclouds.rest.ApiContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.location.jclouds.JcloudsLocationConfig;
import brooklyn.util.exceptions.Exceptions;

import com.google.common.base.Optional;

public class SecurityGroupTool {

    private static final Logger log = LoggerFactory.getLogger(SecurityGroupTool.class);
    
    protected final JcloudsLocation location;
    protected final SecurityGroupDefinition sgDef;

    public SecurityGroupTool(JcloudsLocation location, SecurityGroupDefinition sgDef) {
        this.location = location;
        this.sgDef = sgDef;
    }
    
    public String getName() {
        return sgDef.getName();
    }
    
    public void apply() {
        Optional<SecurityGroupExtension> sgExtO = location.getComputeService().getSecurityGroupExtension();
        if (!sgExtO.isPresent()) {
            throw new IllegalStateException("Advanced networking not supported in this location ("+location+")");
        }
        SecurityGroupExtension sgExt = sgExtO.get();
        
        SecurityGroup sg = findSecurityGroupWithName(sgExt, getName());
        if (sg==null) {
            // TODO initialize the location
            org.jclouds.domain.Location sgLoc = null;

            // TODO record that we created it
            // create it
            try {
                sg = sgExt.createSecurityGroup(getName(), sgLoc);
            } catch (Exception e) {
                Exceptions.propagateIfFatal(e);
                // check if someone else already created it
                sg = findSecurityGroupWithName(sgExt, getName());
                if (sg==null) {
                    // no - so propagate error
                    throw Exceptions.propagate(e);
                } else {
                    log.debug("Looks like parallel thread created security group "+getName()+"; ignoring error in our thread ("+e+") as we now have an SG");
                }
            }
        }
        
        if (sg==null)
            throw new IllegalStateException("Unable to find or create security group ID for "+getName());

        addPermissions(sgExt, sg);
//        return sg.getId();
    }
    
    protected SecurityGroup findSecurityGroupWithName(SecurityGroupExtension sgExt, String name) {
        Set<SecurityGroup> groups = sgExt.listSecurityGroups();
        for (SecurityGroup g: groups) {
            if (name.equals(g.getName())) return g;
        }
        return null;
    }

    protected void addPermissions(SecurityGroupExtension sgExt, SecurityGroup sg) {

        Object api = ((ApiContext<?>)location.getComputeService().getContext().unwrap()).getApi();
        if (api instanceof AWSEC2Api) {
            // optimization for AWS where rules can be added all at once, and it cuts down Req Limit Exceeded problems!
            String region = AWSUtils.getRegionFromLocationOrNull(sg.getLocation());
            String id = sg.getProviderId();
            
            ((AWSEC2Api)api).getSecurityGroupApi().get().authorizeSecurityGroupIngressInRegion(region, id, sgDef.getPermissions());
            
        } else {
            for (IpPermission p: sgDef.getPermissions()) {
                sgExt.addIpPermission(p, sg);
            }
        }
    }
    
    
    // TODO remove this method once we've confirmed the above works nicely (this is an early attempt)
    protected void applyOldEc2(AWSEC2Api client) {
        String region = location.getConfig(JcloudsLocationConfig.CLOUD_REGION_ID);
        if (region==null) {
            // TODO where does the default come from?
            log.warn("No region set for "+location+"; assuming EC2");
            region = "us-east-1"; 
        }
        
        Set<org.jclouds.ec2.domain.SecurityGroup> groups = client.getSecurityGroupApi().get().describeSecurityGroupsInRegion(region, getName());
        String id = null;
        if (groups.isEmpty()) {
            // create it
            try {
                id = client.getSecurityGroupApi().get().createSecurityGroupInRegionAndReturnId(region , getName(), "Brooklyn-managed security group "+getName());
            } catch (Exception e) {
                Exceptions.propagateIfFatal(e);
                // check if someone else already created it!
                groups = client.getSecurityGroupApi().get().describeSecurityGroupsInRegion(region, getName());
                if (groups.isEmpty()) {
                    // no - so propagate error
                    throw Exceptions.propagate(e);
                } else {
                    log.debug("Looks like parallel thread created security group "+getName()+"; ignoring error in our thread ("+e+") as we now have an SG");
                }
            }
        }
        if (!groups.isEmpty()) {
            if (groups.size()>1)
                log.warn("Multiple security groups matching '"+getName()+"' (using the first): "+groups);
            id = groups.iterator().next().getId();
        }
        if (id==null)
            throw new IllegalStateException("Unable to find or create security group ID for "+getName());

        client.getSecurityGroupApi().get().authorizeSecurityGroupIngressInRegion(region, id, sgDef.getPermissions());
    }

}
