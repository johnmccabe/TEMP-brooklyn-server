package brooklyn.location.basic

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import brooklyn.location.MachineLocation
import brooklyn.location.PortRange
import brooklyn.location.PortSupplier
import brooklyn.util.flags.SetFromFlag
import brooklyn.util.internal.SshJschTool

import com.google.common.base.Preconditions

/**
 * Operations on a machine that is accessible via ssh.
 */
public class SshMachineLocation extends AbstractLocation implements MachineLocation, PortSupplier {
    public static final Logger LOG = LoggerFactory.getLogger(SshMachineLocation.class)
            
    @SetFromFlag('username')
    String user

    @SetFromFlag(nullable = false)
    InetAddress address

    @SetFromFlag
    Map config

    private final Set<Integer> ports = [] as HashSet

    public SshMachineLocation(Map properties = [:]) {
        super(properties)
    }
    
    public void configure(Map properties) {
        if (config==null) config = [:]

        super.configure(properties)

        Preconditions.checkNotNull(address, "address is required for SshMachineLocation")
        String host = (user ? "${user}@" : "") + address.hostName
        
        if (name == null) {
            name = host
        }
    }

    public InetAddress getAddress() { return address }

    public int run(Map props=[:], String command, Map env=[:]) {
        run(props, [ command ], env)
    }

    /**
     * Convenience for running a script, returning the result code.
     *
     * Currently runs the commands in an interactive/login shell
     * by passing each as a line to bash. To terminate early, use:
     * <pre>
     * foo || exit 1
     * </pre>
     * It may be desirable instead, in some situations, to wrap as:
     * <pre>
     * { line1 ; } && { line2 ; } ... 
     * </pre>
     * and run as a single command (possibly not as an interacitve/login
     * shell) causing the script to exit on the first command which fails.
     *
     * @todo Perhaps add a flag {@code exitIfAnyNonZero} to toggle between
     *       the above modes ?
     */
    public int run(Map props=[:], List<String> commands, Map env=[:]) {
        Preconditions.checkNotNull address, "host address must be specified for ssh"
        if (!commands) return 0

        if (!user) user = System.getProperty "user.name"
        Map args = [ user:user, host:address.hostName ]
        args << config
        args << leftoverProperties
        SshJschTool ssh = new SshJschTool(args)
        ssh.connect()
        int result = ssh.execShell props, commands, env
        ssh.disconnect()
        result
    }

    public int copyTo(Map props=[:], File src, File destination) {
        return copyTo(props, src, destination.path)
    }

    // FIXME the return code is not a reliable indicator of success or failure
    public int copyTo(Map props=[:], File src, String destination) {
        Preconditions.checkNotNull address, "host address must be specified for scp"
        Preconditions.checkArgument src.exists(), "File %s must exist for scp", src.path
		copyTo new FileInputStream(src), src.length(), destination 
    }
	public int copyTo(Map props=[:], InputStream src, String destination) {
		copyTo(props, src, -1, destination)
	}
	public int copyTo(Map props=[:], InputStream src, long filesize, String destination) {
		if (filesize==-1) {
			def bytes = src.getBytes() //DefaultGroovyMethods.getBytes(src)   //src.getBytes() doesn't work as advertised?
			filesize = bytes.size()
			src = new ByteArrayInputStream(bytes)
		}
		
        if (!user) user = System.getProperty "user.name"
        Map args = [user:user, host:address.hostName]
        args << config
        args << leftoverProperties

        SshJschTool ssh = new SshJschTool(args)
        ssh.connect()
        int result = ssh.createFile props, destination, src, filesize
        ssh.disconnect()
        result
    }

    // FIXME the return code is not a reliable indicator of success or failure
    public int copyFrom(Map props=[:], String remote, String local) {
        Preconditions.checkNotNull address, "host address must be specified for scp"

        if (!user) user = System.getProperty "user.name"
        Map args = [user:user, host:address.hostName]
        args << config
        args << leftoverProperties

        SshJschTool ssh = new SshJschTool(args)
        ssh.connect()
        int result = ssh.transferFileFrom props, remote, local
        ssh.disconnect()
        result
    }

    @Override
    public String toString() {
        return address
    }

    /**
     * @see #obtainPort(PortRange)
     * @see BasicPortRange#ANY_HIGH_PORT
     */
    public boolean obtainSpecificPort(int portNumber) {
	    // TODO Does not yet check if the port really is free on this machine
        if (ports.contains(portNumber)) {
            return false
        } else {
            ports.add(portNumber)
            return true
        }
    }

    public int obtainPort(PortRange range) {
        for (int p: range)
            if (obtainSpecificPort(p)) return p
         if (LOG.isDebugEnabled()) LOG.debug("unable to find port in {} on {}; returning -1", range, this)
         return -1
    }

    public void releasePort(int portNumber) {
        ports.remove((Object) portNumber)
    }

    public boolean isSshable() {
        String cmd = "date"
        try {
            int result = run(cmd)
            if (result == 0) {
                return true
            } else {
                if (LOG.isDebugEnabled()) LOG.debug("Not reachable: $this, executing `$cmd`, exit code $result")
                return false
            }
        } catch (IllegalStateException e) {
            if (LOG.isDebugEnabled()) LOG.debug("Exception checking if $this is reachable; assuming not", e)
            return false
        } catch (IOException e) {
            if (LOG.isDebugEnabled()) LOG.debug("Exception checking if $this is reachable; assuming not", e)
            return false
        }
    }
}
