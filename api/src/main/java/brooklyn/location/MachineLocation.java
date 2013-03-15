package brooklyn.location;

import java.net.InetAddress;

/**
 * A location that is a machine.
 *
 * This interface marks a {@link Location} being a network node with an IP address, 
 * and supports appropriate operations on the node.
 */
public interface MachineLocation extends AddressableLocation {
    /**
     * @return the machine's network address.
     */
    InetAddress getAddress();
    
    OsDetails getOsDetails();
    
}
