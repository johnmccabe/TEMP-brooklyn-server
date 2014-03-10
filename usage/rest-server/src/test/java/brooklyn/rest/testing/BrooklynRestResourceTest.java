package brooklyn.rest.testing;

import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.net.URI;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import javax.ws.rs.core.MediaType;

import org.codehaus.jackson.map.ObjectMapper;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;

import brooklyn.rest.domain.ApplicationSpec;
import brooklyn.rest.domain.ApplicationSummary;
import brooklyn.rest.domain.Status;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.internal.Repeater;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.UniformInterfaceException;
import com.sun.jersey.spi.inject.Errors;

public abstract class BrooklynRestResourceTest extends BrooklynRestApiTest {

    @BeforeClass(alwaysRun = true)
    public void setUp() throws Exception {
        // need this to debug jersey inject errors
        java.util.logging.Logger.getLogger(Errors.class.getName()).setLevel(Level.INFO);

        setUpJersey();
    }

    @AfterClass(alwaysRun = false)
    public void tearDown() throws Exception {
        tearDownJersey();
        super.tearDown();
    }

    protected ClientResponse clientDeploy(ApplicationSpec spec) {
        try {
            // dropwizard TestClient won't skip deserialization of trivial things like string and byte[] and inputstream
            // if we pass in an object it serializes, so we have to serialize things ourselves
            return client().resource("/v1/applications")
                .entity(new ObjectMapper().writer().writeValueAsBytes(spec), MediaType.APPLICATION_OCTET_STREAM)
                .post(ClientResponse.class);
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }
    
    protected void waitForApplicationToBeRunning(final URI applicationRef) {
        if (applicationRef==null)
            throw new NullPointerException("No application URI available (consider using BrooklynRestResourceTest.clientDeploy)");
        
        boolean started = Repeater.create("Wait for application startup")
                .until(new Callable<Boolean>() {
                    @Override
                    public Boolean call() throws Exception {
                        Status status = getApplicationStatus(applicationRef);
                        if (status == Status.ERROR) {
                            fail("Application failed with ERROR");
                        }
                        return status == Status.RUNNING;
                    }
                })
                .every(100, TimeUnit.MILLISECONDS)
                .limitTimeTo(3, TimeUnit.MINUTES)
                .run();
        assertTrue(started);
    }

    protected Status getApplicationStatus(URI uri) {
        return client().resource(uri).get(ApplicationSummary.class).getStatus();
    }

    protected void waitForPageNotFoundResponse(final String resource, final Class<?> clazz) {
        boolean found = Repeater.create("Wait for page not found")
                .until(new Callable<Boolean>() {
                    @Override
                    public Boolean call() throws Exception {
                        try {
                            client().resource(resource).get(clazz);
                            return false;
                        } catch (UniformInterfaceException e) {
                            return e.getResponse().getStatus() == 404;
                        }
                    }
                })
                .every(1, TimeUnit.SECONDS)
                .limitTimeTo(30, TimeUnit.SECONDS)
                .run();
        assertTrue(found);
    }
}
