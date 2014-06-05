package brooklyn.entity.rebind.persister.jclouds;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;

import org.jclouds.ContextBuilder;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.PageSet;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.options.ListContainerOptions;
import org.junit.Assert;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import brooklyn.config.BrooklynProperties;
import brooklyn.entity.basic.Entities;
import brooklyn.location.basic.LocationConfigKeys;
import brooklyn.location.cloud.CloudLocationConfig;
import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.management.ManagementContext;
import brooklyn.test.entity.LocalManagementContextForTests;
import brooklyn.util.stream.Streams;
import brooklyn.util.text.Identifiers;

import com.google.common.base.Preconditions;

public class BlobStoreTest {

    private String locationSpec = BrooklynMementoPersisterToSoftlayerObjectStoreTest.PERSIST_TO_OBJECT_STORE_FOR_TEST_SPEC;
    private JcloudsLocation location;
    private BlobStoreContext context;

    private ManagementContext mgmt;
    private String testContainerName;

    public synchronized BlobStoreContext getBlobStoreContext() {
        if (context==null) {
            if (location==null) {
                Preconditions.checkNotNull(locationSpec, "locationSpec required for remote object store when location is null");
                Preconditions.checkNotNull(mgmt, "mgmt required for remote object store when location is null");
                location = (JcloudsLocation) mgmt.getLocationRegistry().resolve(locationSpec);
            }
            
            String identity = checkNotNull(location.getConfig(LocationConfigKeys.ACCESS_IDENTITY), "identity must not be null");
            String credential = checkNotNull(location.getConfig(LocationConfigKeys.ACCESS_CREDENTIAL), "credential must not be null");
            String provider = checkNotNull(location.getConfig(LocationConfigKeys.CLOUD_PROVIDER), "provider must not be null");
            String endpoint = location.getConfig(CloudLocationConfig.CLOUD_ENDPOINT);

            context = ContextBuilder.newBuilder(provider)
                .credentials(identity, credential)
                .endpoint(endpoint)
                .buildView(BlobStoreContext.class);
        }
        return context;
    }
    
    @BeforeTest(alwaysRun=true)
    public void setup() {
        testContainerName = "brooklyn-test-"+Identifiers.makeRandomId(8);
        mgmt = new LocalManagementContextForTests(BrooklynProperties.Factory.newDefault());
        getBlobStoreContext();
    }
    
    @AfterTest(alwaysRun=true)
    public void teardown() {
        Entities.destroyAll(mgmt);
    }
    
    
    @Test(groups="Integration")
    public void testCreateListDestroyContainer() throws IOException {
        context.getBlobStore().createContainerInLocation(null, testContainerName);
        context.getBlobStore().list(testContainerName);
        PageSet<? extends StorageMetadata> ps = context.getBlobStore().list();
        assertHasItemNamed(ps, testContainerName);
        
        Blob b = context.getBlobStore().blobBuilder("my-blob-1").payload(Streams.newInputStreamWithContents("hello world")).build();
        context.getBlobStore().putBlob(testContainerName, b);
        
        Blob b2 = context.getBlobStore().getBlob(testContainerName, "my-blob-1");
        Assert.assertEquals(Streams.readFullyString(b2.getPayload().openStream()), "hello world");
        
        context.getBlobStore().deleteContainer(testContainerName);
    }
    
    @Test(groups="Integration")
    public void testCreateListDestroySimpleDirInContainer() throws IOException {
        context.getBlobStore().createContainerInLocation(null, testContainerName);
        context.getBlobStore().createDirectory(testContainerName, "my-dir-1");
        
        PageSet<? extends StorageMetadata> ps = context.getBlobStore().list(testContainerName);
        assertHasItemNamed(ps, "my-dir-1");
        
        Blob b = context.getBlobStore().blobBuilder("my-blob-1").payload(Streams.newInputStreamWithContents("hello world")).build();
        context.getBlobStore().putBlob(testContainerName+"/"+"my-dir-1", b);
        
        ps = context.getBlobStore().list(testContainerName, ListContainerOptions.Builder.inDirectory("my-dir-1"));
        assertHasItemNamed(ps, "my-dir-1/my-blob-1");

        // both these syntaxes work:
        Blob b2 = context.getBlobStore().getBlob(testContainerName+"/"+"my-dir-1", "my-blob-1");
        Assert.assertEquals(Streams.readFullyString(b2.getPayload().openStream()), "hello world");

        Blob b3 = context.getBlobStore().getBlob(testContainerName, "my-dir-1"+"/"+"my-blob-1");
        Assert.assertEquals(Streams.readFullyString(b3.getPayload().openStream()), "hello world");

        context.getBlobStore().deleteContainer(testContainerName);
    }

    private void assertHasItemNamed(PageSet<? extends StorageMetadata> ps, String name) {
        for (StorageMetadata sm: ps)
            if (name==null || name.equals(sm.getName())) return;
        Assert.fail("No item named '"+name+"' in "+ps);
    }

}
