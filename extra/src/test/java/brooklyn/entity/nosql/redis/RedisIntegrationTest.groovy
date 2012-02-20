package brooklyn.entity.nosql.redis;

import static brooklyn.test.TestUtils.*
import static java.util.concurrent.TimeUnit.*
import static org.testng.Assert.*

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeMethod
import org.testng.annotations.Test

import redis.clients.jedis.Connection
import brooklyn.entity.basic.legacy.JavaApp
import brooklyn.location.Location
import brooklyn.location.basic.LocalhostMachineProvisioningLocation
import brooklyn.test.entity.TestApplication
import brooklyn.util.internal.TimeExtras

/**
 * Test the operation of the {@link RedisStore} class.
 *
 * TODO clarify test purpose
 */
public class RedisIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(RedisIntegrationTest.class)

    static { TimeExtras.init() }

    private TestApplication app
    private Location testLocation
    private RedisStore redis

    @BeforeMethod(groups = "Integration")
    public void setup() {
        app = new TestApplication();
        testLocation = new LocalhostMachineProvisioningLocation(name:'london')
    }

    @AfterMethod(groups = "Integration")
    public void shutdown() {
        if (app != null) app.stop()
    }

    /**
     * Test that the server starts up and sets SERVICE_UP correctly.
     */
    @Test(groups = "Integration")
    public void canStartupAndShutdown() {
        redis = new RedisStore(owner:app);
        app.start([ testLocation ])
        executeUntilSucceeds() {
            assertTrue redis.getAttribute(JavaApp.SERVICE_UP)
        }
        
        redis.stop()
        assertFalse redis.getAttribute(JavaApp.SERVICE_UP)
    }

    /**
     * Test that a client can connect to the service.
     */
    @Test(groups = "Integration")
    public void testRedisConnection() {
        // Start Redis
        redis = new RedisStore(owner:app)
        app.start([ testLocation ])
        executeUntilSucceeds {
            assertTrue redis.getAttribute(JavaApp.SERVICE_UP)
        }

        try {
            // Access Redis
            Connection connection = getRedisConnection(redis)
            assertTrue connection.isConnected()
            connection.disconnect()
        } finally {
            // Stop broker
	        redis.stop()
        }
    }

    private Connection getRedisConnection(RedisStore redis) {
        int port = redis.getAttribute(RedisStore.REDIS_PORT)
        Connection connection = new Connection("localhost", port)
        connection.connect()
        return connection
    }
}
