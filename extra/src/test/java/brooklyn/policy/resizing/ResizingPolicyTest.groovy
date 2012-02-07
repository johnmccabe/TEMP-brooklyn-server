package brooklyn.policy.resizing

import static brooklyn.test.TestUtils.*
import static java.util.concurrent.TimeUnit.*
import static org.testng.Assert.*

import java.util.List;

import org.testng.annotations.BeforeMethod
import org.testng.annotations.Test

import com.google.common.collect.ImmutableMap;

import brooklyn.entity.LocallyManagedEntity
import brooklyn.entity.trait.Resizable
import brooklyn.policy.loadbalancing.BalanceableWorkerPool;
import brooklyn.policy.loadbalancing.MockContainerEntity;
import brooklyn.test.entity.TestCluster
import brooklyn.util.internal.TimeExtras


class ResizingPolicyTest {
    
    /**
     * Test class for providing a Resizable LocallyManagedEntity for policy testing
     * It is hooked up to a TestCluster that can be used to make assertions against
     */
    public static class LocallyResizableEntity extends LocallyManagedEntity implements Resizable {
        TestCluster cluster
        public LocallyResizableEntity (TestCluster tc) { this.cluster = tc }
        Integer resize(Integer newSize) { Thread.sleep(250); cluster.size = newSize }
        Integer getCurrentSize() { return cluster.size }
        String toString() { return getDisplayName() }
    }
    
    
    private static long TIMEOUT_MS = 5000
    
    ResizingPolicy policy
    TestCluster cluster
    LocallyResizableEntity resizable
    
    static { TimeExtras.init() }
    
    
    @BeforeMethod()
    public void before() {
        policy = new ResizingPolicy([:])
        cluster = new TestCluster(1)
        resizable = new LocallyResizableEntity(cluster)
        resizable.addPolicy(policy)
    }
    
    @Test
    public void testShrinkColdPool() {
        resizable.resize(4)
        resizable.emit(BalanceableWorkerPool.POOL_COLD, message(4, 30l, 40l, 80l))
        
        // expect pool to shrink to 3
        executeUntilSucceeds(timeout:TIMEOUT_MS) { assertEquals(resizable.currentSize, 3) }
    }
    
    @Test
    public void testGrowHotPool() {
        resizable.resize(2)
        resizable.emit(BalanceableWorkerPool.POOL_HOT, message(2, 90l, 40l, 80l))
        
        // expect pool to grow to 3
        executeUntilSucceeds(timeout:TIMEOUT_MS) { assertEquals(resizable.currentSize, 3) }
    }
    
    @Test
    public void testConcurrentShrinkShrink() {
        resizable.resize(4)
        resizable.emit(BalanceableWorkerPool.POOL_HOT, message(4, 30l, 40l, 80l))
        // expect pool to shrink to 3
        
        resizable.emit(BalanceableWorkerPool.POOL_HOT, message(4, 15l, 40l, 80l))
        // now expect pool to shrink to 1
        
        executeUntilSucceeds(timeout:TIMEOUT_MS) { assertEquals(resizable.currentSize, 1) }
    }
    
    @Test
    public void testConcurrentGrowGrow() { /* TODO */ }
    
    @Test
    public void testConcurrentGrowShrink() { /* TODO */ }
    
    @Test
    public void testConcurrentShrinkGrow() { /* TODO */ }
    
    
    static Map<String, Object> message(int currentSize, double currentWorkrate, double lowThreshold, double highThreshold) {
        return ImmutableMap.of(
            ResizingPolicy.POOL_CURRENT_SIZE_KEY, currentSize,
            ResizingPolicy.POOL_CURRENT_WORKRATE_KEY, currentWorkrate,
            ResizingPolicy.POOL_LOW_THRESHOLD_KEY, lowThreshold,
            ResizingPolicy.POOL_HIGH_THRESHOLD_KEY, highThreshold)
    }
    
}
