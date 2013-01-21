package brooklyn.location;

import java.util.Collection;
import java.util.Map;

/**
 * A location that is able to provision new machines within its location.
 *
 * This interface extends {@link Location} to add the ability to provision {@link MachineLocation}s in this location.
 */
public interface MachineProvisioningLocation<T extends MachineLocation> extends Location {
    /**
     * Obtain a machine in this location.
     * 
     * @param flags Details of the desired machine (e.g. image, size, open ports, etc; some flag support is limited to selected providers).
     * "callerContext" can be specified to have custom logging and error messages (useful if starting machines in parallel)
     * @return a machine that is a child of this location.
     * @throws NoMachinesAvailableException if there are no machines available in this location (or impls may return null, but that is discouraged)
     */
    // TODO Document dictionary of flag keys
    T obtain(Map<String,? extends Object> flags) throws NoMachinesAvailableException;

    /**
     * Release a previously-obtained machine.
     *
     * @param machine a {@link MachineLocation} previously obtained from a call to {@link #obtain()}
     * @throws IllegalStateException if the machine did not come from a call to {@link #obtain()} or it has already been released.
     */
    void release(T machine);
    
    /**
     * Gets flags, suitable as an argument to {@link #obtain(Map)}. The tags provided give
     * hints about the machine required. The provisioning-location could be configured to 
     * understand those tags. 
     * 
     * For example, an AWS-location could be configured to understand that a particular entity
     * type (e.g. "TomcatServer") requires a particular AMI in that region, so would return the 
     * required image id.
     *  
     * @param tags
     * @return
     */
    Map<String,Object> getProvisioningFlags(Collection<String> tags);
}
