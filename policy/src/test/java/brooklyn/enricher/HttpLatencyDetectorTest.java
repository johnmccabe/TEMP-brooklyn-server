package brooklyn.enricher;

import java.net.URL;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.entity.basic.ApplicationBuilder;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.event.AttributeSensor;
import brooklyn.event.basic.Sensors;
import brooklyn.location.basic.LocalhostMachineProvisioningLocation;
import brooklyn.test.EntityTestUtils;
import brooklyn.test.entity.TestApplication;
import brooklyn.test.entity.TestEntity;
import brooklyn.util.collections.MutableMap;
import brooklyn.util.http.BetterMockWebServer;

import com.google.common.collect.ImmutableList;
import com.google.mockwebserver.MockResponse;

public class HttpLatencyDetectorTest {

    private static final Logger log = LoggerFactory.getLogger(HttpLatencyDetectorTest.class);
    public static final AttributeSensor<String> TEST_URL = Sensors.newStringSensor( "test.url");
    
    private BetterMockWebServer server;
    private LocalhostMachineProvisioningLocation loc;
    private TestApplication app;
    private TestEntity entity;
    private URL baseUrl;
    
    @BeforeMethod(alwaysRun=true)
    public void setUp() throws Exception {
        loc = new LocalhostMachineProvisioningLocation();
        app = ApplicationBuilder.newManagedApp(TestApplication.class);
        entity = app.createAndManageChild(EntitySpec.create(TestEntity.class));
        app.start(ImmutableList.of(loc));
        
        server = BetterMockWebServer.newInstanceLocalhost();
        for (int i = 0; i < 100; i++) {
            server.enqueue(new MockResponse().setResponseCode(200).addHeader("content-type: application/json").setBody("{\"foo\":\"myfoo\"}"));
        }
        server.play();
        baseUrl = server.getUrl("/");
    }

    @AfterMethod(alwaysRun=true)
    public void tearDown() throws Exception {
        if (server != null) server.shutdown();
        if (app != null) Entities.destroyAll(app.getManagementContext());
    }
    
    @Test(groups="Integration")
    public void testWaitsThenPolls() throws Exception {
        entity.addEnricher(HttpLatencyDetector.builder().
                url(TEST_URL).
                noServiceUp().
                rollup(500, TimeUnit.MILLISECONDS).
                period(100, TimeUnit.MILLISECONDS).
                build());
        
        // nothing until url is set
        EntityTestUtils.assertAttributeEqualsContinually(
                MutableMap.of("timeout", 200), 
                entity, HttpLatencyDetector.REQUEST_LATENCY_IN_SECONDS_MOST_RECENT, null);
        
        // gets value after url is set, and gets rolling average
        entity.setAttribute(TEST_URL, baseUrl.toString());
        EntityTestUtils.assertAttributeEventuallyNonNull(entity, HttpLatencyDetector.REQUEST_LATENCY_IN_SECONDS_MOST_RECENT); 
        EntityTestUtils.assertAttributeEventuallyNonNull(entity, HttpLatencyDetector.REQUEST_LATENCY_IN_SECONDS_IN_WINDOW); 

        log.info("Latency to "+entity.getAttribute(TEST_URL)+" is "+entity.getAttribute(HttpLatencyDetector.REQUEST_LATENCY_IN_SECONDS_MOST_RECENT));
        log.info("Mean latency to "+entity.getAttribute(TEST_URL)+" is "+entity.getAttribute(HttpLatencyDetector.REQUEST_LATENCY_IN_SECONDS_IN_WINDOW));
    }
}
