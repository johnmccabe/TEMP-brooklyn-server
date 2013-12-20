package io.brooklyn.camp.brooklyn;

import static com.google.common.base.Preconditions.checkNotNull;
import io.brooklyn.camp.spi.Assembly;
import io.brooklyn.camp.spi.AssemblyTemplate;
import io.brooklyn.camp.spi.PlatformComponent;
import io.brooklyn.camp.spi.PlatformRootSummary;
import io.brooklyn.camp.spi.collection.ResolvableLink;

import java.io.IOException;
import java.io.Reader;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.entity.Entity;
import brooklyn.entity.basic.BrooklynTasks;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.webapp.JavaWebAppService;
import brooklyn.management.ManagementContext;
import brooklyn.management.Task;
import brooklyn.test.Asserts;
import brooklyn.util.ResourceUtils;
import brooklyn.util.collections.MutableMap;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.stream.Streams;
import brooklyn.util.time.Duration;

@Test(groups="Integration")
public class JavaWebAppsIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(JavaWebAppsIntegrationTest.class);
    
    private ManagementContext brooklynMgmt;
    private BrooklynCampPlatform platform;

    @BeforeMethod(alwaysRun=true)
    public void setup() {
        BrooklynCampPlatformLauncherNoServer launcher = new BrooklynCampPlatformLauncherNoServer();
        launcher.launch();
        brooklynMgmt = launcher.getBrooklynMgmt();
      
        platform = new BrooklynCampPlatform(
              PlatformRootSummary.builder().name("Brooklyn CAMP Platform").build(),
              brooklynMgmt);
    }
    
    @AfterMethod
    public void teardown() {
        if (brooklynMgmt!=null) Entities.destroyAll(brooklynMgmt);
    }
    
    public void testSimpleYamlDeploy() throws IOException {
        Reader input = Streams.reader(new ResourceUtils(this).getResourceFromUrl("java-web-app-simple.yaml"));
        AssemblyTemplate at = platform.pdp().registerDeploymentPlan(input);

        try {
            Assembly assembly = at.getInstantiator().newInstance().instantiate(at, platform);
            log.info("Test - created "+assembly);
            
            final Entity app = brooklynMgmt.getEntityManager().getEntity(assembly.getId());
            log.info("App - "+app);
            Set<Task<?>> tasks = BrooklynTasks.getTasksInEntityContext(brooklynMgmt.getExecutionManager(), app);
            log.info("Waiting on "+tasks.size()+" task(s)");
            for (Task<?> t: tasks) {
                t.blockUntilEnded();
            }

            log.info("App started:");
            Entities.dumpInfo(app);

            final String url = Asserts.succeedsEventually(MutableMap.of("timeout", Duration.TEN_SECONDS), new Callable<String>() {
                @Override public String call() throws Exception {
                    String url = app.getChildren().iterator().next().getAttribute(JavaWebAppService.ROOT_URL);
                    return checkNotNull(url, "url of %s", app);
                }});
        
            String site = Asserts.succeedsEventually(MutableMap.of("timeout", Duration.TEN_SECONDS), new Callable<String>() {
                    @Override public String call() throws Exception {
                        return new ResourceUtils(this).getResourceAsString(url);
                    }});
            
            log.info("App URL for "+app+": "+url);
            Assert.assertTrue(url.contains("928"), "URL should be on port 9280+ based on config set in yaml, url "+url+", app "+app);
            Assert.assertTrue(site.toLowerCase().contains("hello"), site);
            Assert.assertTrue(!platform.assemblies().isEmpty());
        } catch (Exception e) {
            log.warn("Unable to instantiate "+at+" (rethrowing): "+e);
            throw Exceptions.propagate(e);
        }
    }

    public void testWithDbDeploy() throws IOException {
        Reader input = Streams.reader(new ResourceUtils(this).getResourceFromUrl("java-web-app-and-db-with-function.yaml"));
        AssemblyTemplate at = platform.pdp().registerDeploymentPlan(input);

        try {
            Assembly assembly = at.getInstantiator().newInstance().instantiate(at, platform);
            log.info("Test - created "+assembly);
            
            final Entity app = brooklynMgmt.getEntityManager().getEntity(assembly.getId());
            log.info("App - "+app);
            
            Iterator<ResolvableLink<PlatformComponent>> pcs = assembly.getPlatformComponents().links().iterator();
            PlatformComponent pc1 = pcs.next().resolve();
            Entity cluster = brooklynMgmt.getEntityManager().getEntity(pc1.getId());
            log.info("pc1 - "+pc1+" - "+cluster);
            
            PlatformComponent pc2 = pcs.next().resolve();
            log.info("pc2 - "+pc2);
            // FIXME doesn't work -- 
//            Object javaSysprops = ((EntityInternal)cluster).getConfigMap().getRawConfig(UsesJava.JAVA_SYSPROPS);
//            Object dbUrl = ((Map<?,?>)javaSysprops).get("brooklyn.example.db.url");
//            Assert.assertTrue(dbUrl instanceof DeferredSupplier, "dbUrl is "+dbUrl);
            
            Set<Task<?>> tasks = BrooklynTasks.getTasksInEntityContext(brooklynMgmt.getExecutionManager(), app);
            log.info("Waiting on "+tasks.size()+" task(s)");
            for (Task<?> t: tasks) {
                t.blockUntilEnded();
            }

            log.info("App started:");
            Entities.dumpInfo(app);

            final String url = Asserts.succeedsEventually(MutableMap.of("timeout", Duration.TEN_SECONDS), new Callable<String>() {
                    @Override public String call() throws Exception {
                        String url = app.getChildren().iterator().next().getAttribute(JavaWebAppService.ROOT_URL);
                        return checkNotNull(url, "url of %s", app);
                    }});
            
            String site = Asserts.succeedsEventually(MutableMap.of("timeout", Duration.TEN_SECONDS), new Callable<String>() {
                    @Override public String call() throws Exception {
                        return new ResourceUtils(this).getResourceAsString(url);
                    }});
            
            log.info("App URL for "+app+": "+url);
            Assert.assertTrue(url.contains("921"), "URL should be on port 9280+ based on config set in yaml, url "+url+", app "+app);
            Assert.assertTrue(site.toLowerCase().contains("hello"), site);
            Assert.assertTrue(!platform.assemblies().isEmpty());
            
            // TODO assert DB is working (send visitors command, and get result)
        } catch (Exception e) {
            log.warn("Unable to instantiate "+at+" (rethrowing): "+e);
            throw Exceptions.propagate(e);
        }
    }

    
}
