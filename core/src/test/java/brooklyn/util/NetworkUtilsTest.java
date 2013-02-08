package brooklyn.util;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;

import org.testng.Assert;
import org.testng.annotations.Test;

import brooklyn.util.text.Identifiers;

public class NetworkUtilsTest {

    @Test
    public void testGetInetAddressWithFixedNameByIpBytes() throws Exception {
        InetAddress addr = NetworkUtils.getInetAddressWithFixedName(new byte[] {1,2,3,4});
        assertEquals(addr.getAddress(), new byte[] {1,2,3,4});
        assertEquals(addr.getHostName(), "1.2.3.4");
    }
    
    @Test
    public void testGetInetAddressWithFixedNameByIp() throws Exception {
        InetAddress addr = NetworkUtils.getInetAddressWithFixedName("1.2.3.4");
        assertEquals(addr.getAddress(), new byte[] {1,2,3,4});
        assertEquals(addr.getHostName(), "1.2.3.4");
        
        InetAddress addr2 = NetworkUtils.getInetAddressWithFixedName("255.255.255.255");
        assertEquals(addr2.getAddress(), new byte[] {(byte)(int)255,(byte)(int)255,(byte)(int)255,(byte)(int)255});
        assertEquals(addr2.getHostName(), "255.255.255.255");
        
        InetAddress addr3 = NetworkUtils.getInetAddressWithFixedName("localhost");
        assertEquals(addr3.getHostName(), "localhost");
        
    }
    
    @Test(expectedExceptions=UnknownHostException.class, groups="Integration")
    public void testGetInetAddressWithFixedNameButInvalidIpThrowsException() throws Exception {
        // as with ByonLocationResolverTest.testNiceError
        // some DNS servers give an IP for this "hostname"
        // so test is marked as integration now
        NetworkUtils.getInetAddressWithFixedName("1.2.3.400");
    }
    
    @Test
    public void testIsPortAvailableReportsTrueWhenPortIsFree() throws Exception {
        int port = 58769;
        for (int i = 0; i < 10; i++) {
            assertTrue(NetworkUtils.isPortAvailable(port));
        }
    }
    
    @Test
    public void testIsPortAvailableReportsFalseWhenPortIsInUse() throws Exception {
        int port = 58768;
        ServerSocket ss = null;
        try {
            ss = new ServerSocket(port);
            assertFalse(NetworkUtils.isPortAvailable(port));
        } finally {
            if (ss != null) {
                ss.close();
            }
        }

        assertTrue(NetworkUtils.isPortAvailable(port));
    }
    
    //just some system health-checks... localhost may not resolve properly elsewhere
    //(e.g. in geobytes, AddressableLocation, etc) if this does not work
    
    @Test
    public void testLocalhostIpLookup() throws UnknownHostException {
        InetAddress address = InetAddress.getByName("127.0.0.1");
        Assert.assertEquals(127, address.getAddress()[0]);
        Assert.assertTrue(NetworkUtils.isPrivateSubnet(address));
    }
    
    @Test
    public void testLocalhostLookup() throws UnknownHostException {
        InetAddress address = InetAddress.getByName("localhost");
        Assert.assertEquals(127, address.getAddress()[0]);
        Assert.assertTrue(NetworkUtils.isPrivateSubnet(address));
        Assert.assertEquals("127.0.0.1", address.getHostAddress());
    }

    @Test
    public void test10_x_x_xSubnetPrivate() throws UnknownHostException {
        InetAddress address = InetAddress.getByAddress(new byte[] { 10, 0, 0, 1 });
        Assert.assertTrue(NetworkUtils.isPrivateSubnet(address));
    }

    @Test
    public void test172_16_x_xSubnetPrivate() throws UnknownHostException {
        InetAddress address = InetAddress.getByAddress(new byte[] { (byte)172, 31, (byte)255, (byte)255 });
        Assert.assertTrue(NetworkUtils.isPrivateSubnet(address));
    }

    @Test(groups="Integration")
    public void testBogusHostnameUnresolvable() {
        Assert.assertEquals(NetworkUtils.resolve("bogus-hostname-"+Identifiers.makeRandomId(8)), null);
    }

}
