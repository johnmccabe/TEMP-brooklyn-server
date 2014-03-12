package brooklyn.util.task;

import static org.testng.Assert.*;

import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.testng.collections.Lists;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import brooklyn.management.Task;

/**
 * Test the operation of the {@link CompoundTask} class.
 */
public class CompoundTaskExecutionTest {

    private static final Logger LOG = LoggerFactory.getLogger(CompoundTaskExecutionTest.class);

    BasicExecutionManager em;
    BasicExecutionContext ec;

    @BeforeClass
    public void setup() {
        em = new BasicExecutionManager("mycontext");
        ec = new BasicExecutionContext(em);
    }

    @AfterClass
    public void teardown() {
        if (em != null) em.shutdownNow();
        em = null;
    }

    private BasicTask<String> taskReturning(final String val) {
        return new BasicTask<String>(new Callable<String>() {
                @Override public String call() {
                    return val;
                }
            });
    }

    @Test
    public void runSequenceTask() throws Exception {
        BasicTask<String> t1 = taskReturning("a");
        BasicTask<String> t2 = taskReturning("b");
        BasicTask<String> t3 = taskReturning("c");
        BasicTask<String> t4 = taskReturning("d");
        Task<List<String>> tSequence = ec.submit(new SequentialTask<String>(t1, t2, t3, t4));
        assertEquals(tSequence.get(), ImmutableList.of("a", "b", "c", "d"));
    }

    @Test
    public void runParallelTask() throws Exception {
        BasicTask<String> t1 = taskReturning("a");
        BasicTask<String> t2 = taskReturning("b");
        BasicTask<String> t3 = taskReturning("c");
        BasicTask<String> t4 = taskReturning("d");
        Task<List<String>> tSequence = ec.submit(new ParallelTask<String>(t4, t2, t1, t3));
        assertEquals(new HashSet<String>(tSequence.get()), ImmutableSet.of("a", "b", "c", "d"));
    }

    @Test
    public void runParallelTaskWithDelay() throws Exception {
        final Semaphore locker = new Semaphore(0);
        BasicTask<String> t1 = new BasicTask<String>(new Callable<String>() {
                @Override public String call() {
                    try {
                        locker.acquire();
                    } catch (InterruptedException e) {
                        throw Throwables.propagate(e);
                    }
                    return "a";
                }
            });
        BasicTask<String> t2 = taskReturning("b");
        BasicTask<String> t3 = taskReturning("c");
        BasicTask<String> t4 = taskReturning("d");
        final Task<List<String>> tSequence = ec.submit(new ParallelTask<String>(t4, t2, t1, t3));

        assertEquals(ImmutableSet.of(t2.get(), t3.get(), t4.get()), ImmutableSet.of("b", "c", "d"));
        assertFalse(t1.isDone());
        assertFalse(tSequence.isDone());

        // get blocks until tasks have completed
        Thread t = new Thread() {
            @Override public void run() {
                try {
                    tSequence.get();
                } catch (Exception e) {
                    throw Throwables.propagate(e);
                }
                locker.release();
            }
        };
        t.start();
        Thread.sleep(30);
        assertTrue(t.isAlive());

        locker.release();

        assertEquals(new HashSet<String>(tSequence.get()), ImmutableSet.of("a", "b", "c", "d"));
        assertTrue(t1.isDone());
        assertTrue(tSequence.isDone());

        locker.acquire();
    }

    @Test
    public void testComplexOrdering() throws Exception {
        List<String> data = new CopyOnWriteArrayList<String>();
        SequentialTask<String> taskA = new SequentialTask<String>(
                appendAfterDelay(data, "a1"), appendAfterDelay(data, "a2"), appendAfterDelay(data, "a3"), appendAfterDelay(data, "a4"));
        SequentialTask<String> taskB = new SequentialTask<String>(
                appendAfterDelay(data, "b1"), appendAfterDelay(data, "b2"), appendAfterDelay(data, "b3"), appendAfterDelay(data, "b4"));
        Task<List<String>> t = ec.submit(new ParallelTask<String>(taskA, taskB));
        t.get();

        LOG.debug("Tasks happened in order: {}", data);
        assertEquals(data.size(), 8);
        assertEquals(new HashSet<String>(data), ImmutableSet.of("a1", "a2", "a3", "a4", "b1", "b2", "b3", "b4"));

        // a1, ..., a4 should be in order
        List<String> as = Lists.newArrayList(), bs = Lists.newArrayList();
        for (String value : data) {
            ((value.charAt(0) == 'a') ? as : bs).add(value);
        }
        assertEquals(as, ImmutableList.of("a1", "a2", "a3", "a4"));
        assertEquals(bs, ImmutableList.of("b1", "b2", "b3", "b4"));
    }

    private BasicTask<String> appendAfterDelay(final List<String> list, final String value) {
        return new BasicTask<String>(new Callable<String>() {
                @Override public String call() {
                    try {
                        Thread.sleep((int) (100 * Math.random()));
                    } catch (InterruptedException e) {
                        throw Throwables.propagate(e);
                    }
                    LOG.debug("running {}", value);
                    list.add(value);
                    return value;
                }
            });
    }

}
