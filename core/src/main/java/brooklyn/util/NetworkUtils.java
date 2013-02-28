package brooklyn.util;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Throwables;

import brooklyn.location.geo.UtraceHostGeoLookup;
import brooklyn.util.net.Cidr;
import brooklyn.util.text.Identifiers;

public class NetworkUtils {

    private static final Logger log = LoggerFactory.getLogger(NetworkUtils.class);
    
    public static final int MIN_PORT_NUMBER = 1;
    public static final int MAX_PORT_NUMBER = 65535;

    public static final String VALID_IP_ADDRESS_REGEX = "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$";
    public static final Pattern VALID_IP_ADDRESS_PATTERN;
    static {
        VALID_IP_ADDRESS_PATTERN = Pattern.compile(VALID_IP_ADDRESS_REGEX);
    }

    private static boolean loggedLocalhostNotAvailable = false;
    public static boolean isPortAvailable(int port) {
        try {
            return isPortAvailable(InetAddress.getByName("localhost"), port);
        } catch (UnknownHostException e) {
            if (!loggedLocalhostNotAvailable) {
                loggedLocalhostNotAvailable = true;
                log.warn("localhost unavailable during port availability check for "+port+": "+e+"; ignoring, but this may be a sign of network misconfiguration");
            }
            return isPortAvailable(null, port);
        }
    }
    public static boolean isPortAvailable(InetAddress localAddress, int port) {
        if (port < MIN_PORT_NUMBER || port > MAX_PORT_NUMBER) {
            throw new IllegalArgumentException("Invalid start port: " + port);
        }
        try {
            Socket s = new Socket(localAddress, port);
            try {
                s.close();
            } catch (Exception e) {}
            return false;
        } catch (Exception e) {
            //expected - shouldn't be able to connect
        }
        //despite http://stackoverflow.com/questions/434718/sockets-discover-port-availability-using-java
        //(recommending the following) it isn't 100% reliable (e.g. nginx will happily coexist with ss+ds)
        //so we also do the above check
        ServerSocket ss = null;
        DatagramSocket ds = null;
        try {
            ss = new ServerSocket(port);
            ss.setReuseAddress(true);
            ds = new DatagramSocket(port);
            ds.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (ds != null) {
                ds.close();
            }

            if (ss != null) {
                try {
                    ss.close();
                } catch (IOException e) {
                    /* should not be thrown */
                }
            }
        }
    }
    /** returns the first port available on the local machine >= the port supplied */
    public static int nextAvailablePort(int port) {
        while (!isPortAvailable(port)) port++;
        return port;
    }

    public static boolean isPortValid(Integer port) {
        return (port!=null && port>=NetworkUtils.MIN_PORT_NUMBER && port<=NetworkUtils.MAX_PORT_NUMBER);
    }
    public static int checkPortValid(Integer port, String errorMessage) {
        if (!isPortValid(port)) {
            throw new IllegalArgumentException("Invalid port value "+port+": "+errorMessage);
        }
        return port;
    }

    public static void checkPortsValid(@SuppressWarnings("rawtypes") Map ports) {
        for (Object ppo : ports.entrySet()) {
            Map.Entry<?,?> pp = (Map.Entry<?,?>)ppo;
            Object val = pp.getValue();
            if(val == null){
                throw new IllegalArgumentException("port for "+pp.getKey()+" is null");
            }else if (!(val instanceof Integer)) {
                throw new IllegalArgumentException("port "+val+" for "+pp.getKey()+" is not an integer ("+val.getClass()+")");
            }
            checkPortValid((Integer)val, ""+pp.getKey());
        }
    }

    /**
     * Check if this is a private address, not exposed on the public Internet.
     *
     * For IPV4 addresses this is an RFC1918 subnet address ({code 10.0.0.0/8},
     * {@code 172.16.0.0/12} and {@code 192.168.0.0/16}), a link-local address
     * ({@code 169.254.0.0/16}) or a loopback address ({@code 127.0.0.1/0}).
     * <p>
     * For IPV6 addresses this is the RFC3514 link local block ({@code fe80::/10})
     * and site local block ({@code feco::/10}) or the loopback block
     * ({@code ::1/128}).
     *
     * @return true if the address is private
     */
    public static boolean isPrivateSubnet(InetAddress address) {
        return address.isSiteLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress();
    }

    private static boolean triedUnresolvableHostname = false;
    private static String cachedAddressOfUnresolvableHostname = null;
    
    /** returns null in a sane DNS environment, but if DNS provides a bogus address for made-up hostnames, this returns that address */
    public synchronized static String getAddressOfUnresolvableHostname() {
        if (triedUnresolvableHostname) return cachedAddressOfUnresolvableHostname;
        String h = "noexistent-machine-"+Identifiers.makeRandomBase64Id(8);
        try {
            cachedAddressOfUnresolvableHostname = InetAddress.getByName(h).getHostAddress();
            log.info("NetworkUtils detected "+cachedAddressOfUnresolvableHostname+" being returned by DNS for bogus hostnames ("+h+")");
        } catch (Exception e) {
            log.debug("NetworkUtils detected failure on DNS resolution of unknown hostname ("+h+" throws "+e+")");
            cachedAddressOfUnresolvableHostname = null;
        }
        triedUnresolvableHostname = true;
        return cachedAddressOfUnresolvableHostname;
    }
    
    /** resolves the given hostname to an IP address, returning null if unresolvable or 
     * if the resolution is bogus (eg 169.* subnet or a "catch-all" IP resolution supplied by some miscreant DNS services) */
    public static InetAddress resolve(String hostname) {
        try {
            InetAddress a = InetAddress.getByName(hostname);
            if (a==null) return null;
            String ha = a.getHostAddress();
            if (log.isDebugEnabled()) log.debug("NetworkUtils resolved "+hostname+" as "+a);
            if (ha.equals(getAddressOfUnresolvableHostname())) return null;
            if (ha.startsWith("169.")) return null;
            return a;
        } catch (Exception e) {
            if (log.isDebugEnabled()) log.debug("NetworkUtils failed to resolve "+hostname+", threw "+e);
            return null;
        }
    }
    
    /**
     * Gets an InetAddress using the given IP, and using that IP as the hostname (i.e. avoids any hostname resolution).
     * <p>
     * This is very useful if using the InetAddress for updating config files on remote machines, because then it will
     * not be pickup a hostname from the local /etc/hosts file, which might not be known on the remote machine.
     */
    public static InetAddress getInetAddressWithFixedName(byte[] ip) throws UnknownHostException {
        StringBuilder name = new StringBuilder();
        for (byte part : ip) {
            if (name.length() > 0) name.append(".");
            name.append(part);
        }
        // only if of illegal length; don't need checked exception!
        return InetAddress.getByAddress(name.toString(), ip);
    }
    
    /**
     * Gets an InetAddress using the given hostname or IP. If it is an IPv4 address, then this is equivalent
     * to {@link getInetAddressWithFixedName(byte[])}. If it is a hostname, then this hostname will be used
     * in the returned InetAddress.
     */
    public static InetAddress getInetAddressWithFixedName(String hostnameOrIp) throws UnknownHostException {
        if (VALID_IP_ADDRESS_PATTERN.matcher(hostnameOrIp).matches()) {
            byte[] ip = new byte[4];
            String[] parts = hostnameOrIp.split("\\.");
            assert parts.length == 4 : "val="+hostnameOrIp+"; split="+Arrays.toString(parts)+"; length="+parts.length;
            for (int i = 0; i < parts.length; i++) {
                ip[i] = (byte)Integer.parseInt(parts[i]);
            }
            return InetAddress.getByAddress(hostnameOrIp, ip);
        } else {
            return InetAddress.getByName(hostnameOrIp);
        }
    }
    
    public static InetAddress getLocalHost() {
        try {
            return InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            InetAddress result = null;
            try {
                result = getInetAddressWithFixedName("127.0.0.1");
            } catch (UnknownHostException e1) {
                // shouldn't happen
                throw Throwables.propagate(e1);
            }
            log.warn("Localhost is not resolvable; using "+result);
            return result;
        }
    }

    public static Cidr cidr(String cidr) {
        return new Cidr(cidr);
    }

    public static Cidr getLargestPrivateSubnet(InetAddress address) {
        return getLargestPrivateSubnet(address.getHostAddress());
    }
    
    public static Cidr getLargestPrivateSubnet(String ip) {
        Cidr me = new Cidr(ip+"/32");
        if (ip.startsWith("192.168."))
            return Cidr._192_168;
        if (ip.startsWith("10."))
            return Cidr._10;
        if (me.getBytes()[0]==172 && ((me.getBytes()[1]&0xf0) == 0x10))
            return Cidr._172_16;
        return me;
    }

    public static Cidr getLargestLocalPrivateSubnet() {
        return getLargestPrivateSubnet(NetworkUtils.getLocalHost());
    }

    public static String getLocalhostExternalIp() {
        return UtraceHostGeoLookup.getLocalhostExternalIp();
    }

}
