package brooklyn.entity.rebind.persister;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.mementos.BrooklynMemento;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.time.Time;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Stopwatch;
import com.google.common.io.Files;

public class BrooklynMementoPersisterToFile extends AbstractBrooklynMementoPersister {

    // FIXME This is no longer used (instead we use ToMultiFile).
    // Is this definitely no longer useful? Delete if not, and 
    // merge AbstractBrooklynMementoPersister+BrooklynMementoPerisisterInMemory.

    private static final Logger LOG = LoggerFactory.getLogger(BrooklynMementoPersisterToFile.class);

    private final File file;
    private final MementoSerializer<BrooklynMemento> serializer;
    private final Object mutex = new Object();
    
    public BrooklynMementoPersisterToFile(File file, ClassLoader classLoader) {
        this.file = file;
        this.serializer = new XmlMementoSerializer<BrooklynMemento>(classLoader);
    }
    
    @VisibleForTesting
    @Override
    public void waitForWritesCompleted(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        // TODO Could wait for concurrent checkpoint/delta, but don't need to for tests
        // because they first wait for checkpoint/delta to have been called by RebindManagerImpl.
        return;
    }

    @Override
    public BrooklynMemento loadMemento(LookupContext lookupContext) {
        Stopwatch stopwatch = Stopwatch.createStarted();
        
        String xml = readFile();
        serializer.setLookupContext(lookupContext);
        try {
            BrooklynMemento result = serializer.fromString(xml);
            
            if (LOG.isDebugEnabled()) LOG.debug("Loaded memento; took {}", Time.makeTimeStringRounded(stopwatch));
            return result;
            
        } finally {
            serializer.unsetLookupContext();
        }
    }
    
    private String readFile() {
        try {
            synchronized (mutex) {
                return Files.asCharSource(file, Charsets.UTF_8).read();
            }
        } catch (IOException e) {
            LOG.error("Failed to persist memento", e);
            throw Exceptions.propagate(e);
        }
    }
    
    @Override
    public void checkpoint(BrooklynMemento newMemento) {
        Stopwatch stopwatch = Stopwatch.createStarted();
        synchronized (mutex) {
            long timeObtainedMutex = stopwatch.elapsed(TimeUnit.MILLISECONDS);
            super.checkpoint(newMemento);
            long timeCheckpointed = stopwatch.elapsed(TimeUnit.MILLISECONDS);
            writeMemento();
            long timeWritten = stopwatch.elapsed(TimeUnit.MILLISECONDS);
            
            if (LOG.isDebugEnabled()) LOG.debug("Checkpointed memento; total={}ms, obtainingMutex={}ms, " +
                    "checkpointing={}ms, writing={}ms", 
                    new Object[] {timeWritten, timeObtainedMutex, (timeCheckpointed-timeObtainedMutex), 
                    (timeWritten-timeCheckpointed)});
        }
    }
    
    @Override
    public void delta(Delta delta) {
        Stopwatch stopwatch = Stopwatch.createStarted();
        synchronized (mutex) {
            long timeObtainedMutex = stopwatch.elapsed(TimeUnit.MILLISECONDS);
            super.delta(delta);
            long timeDeltad = stopwatch.elapsed(TimeUnit.MILLISECONDS);
            writeMemento();
            long timeWritten = stopwatch.elapsed(TimeUnit.MILLISECONDS);
            
            if (LOG.isDebugEnabled()) LOG.debug("Checkpointed memento; total={}ms, obtainingMutex={}ms, " +
                    "delta'ing={}ms, writing={}", 
                    new Object[] {timeWritten, timeObtainedMutex, (timeDeltad-timeObtainedMutex), 
                    (timeWritten-timeDeltad)});
        }
    }
    
    private void writeMemento() {
        assert Thread.holdsLock(mutex);
        try {
            Files.write(serializer.toString(memento), file, Charsets.UTF_8);
        } catch (IOException e) {
            LOG.error("Failed to persist memento", e);
        }
    }
}
