/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package brooklyn.entity.rebind;

import static org.testng.Assert.assertEquals;

import java.io.File;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;

import brooklyn.catalog.BrooklynCatalog;
import brooklyn.catalog.CatalogItem;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.EntityFunctions;
import brooklyn.entity.basic.StartableApplication;
import brooklyn.entity.rebind.persister.BrooklynMementoPersisterToObjectStore;
import brooklyn.entity.rebind.persister.FileBasedObjectStore;
import brooklyn.entity.rebind.persister.PersistMode;
import brooklyn.management.ManagementContext;
import brooklyn.management.ha.HighAvailabilityMode;
import brooklyn.management.internal.LocalManagementContext;
import brooklyn.management.internal.ManagementContextInternal;
import brooklyn.mementos.BrooklynMementoManifest;
import brooklyn.util.os.Os;
import brooklyn.util.time.Duration;

import com.google.api.client.util.Sets;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Iterables;

public abstract class RebindTestFixture<T extends StartableApplication> {

    private static final Logger LOG = LoggerFactory.getLogger(RebindTestFixture.class);

    protected static final Duration TIMEOUT_MS = Duration.TEN_SECONDS;

    protected ClassLoader classLoader = getClass().getClassLoader();
    protected LocalManagementContext origManagementContext;
    protected File mementoDir;
    
    protected T origApp;
    protected T newApp;
    protected ManagementContext newManagementContext;

    @BeforeMethod(alwaysRun=true)
    public void setUp() throws Exception {
        mementoDir = Os.newTempDir(getClass());
        origManagementContext = createOrigManagementContext();
        origApp = createApp();
        
        LOG.info("Test "+getClass()+" persisting to "+mementoDir);
    }

    /** @return A started management context */
    protected LocalManagementContext createOrigManagementContext() {
        return RebindTestUtils.managementContextBuilder(mementoDir, classLoader)
                .persistPeriodMillis(getPersistPeriodMillis())
                .forLive(useLiveManagementContext())
                .emptyCatalog(useEmptyCatalog())
                .buildStarted();
    }

    /** @return An unstarted management context */
    protected LocalManagementContext createNewManagementContext() {
        return RebindTestUtils.managementContextBuilder(mementoDir, classLoader)
                .forLive(useLiveManagementContext())
                .emptyCatalog(useEmptyCatalog())
                .buildUnstarted();
    }

    protected boolean useLiveManagementContext() {
        return false;
    }

    protected boolean useEmptyCatalog() {
        return true;
    }

    protected int getPersistPeriodMillis() {
        return 1;
    }
    
    /** optionally, create the app as part of every test; can be no-op if tests wish to set origApp themselves */
    protected abstract T createApp();

    @AfterMethod(alwaysRun=true)
    public void tearDown() throws Exception {
        if (origApp != null) Entities.destroyAll(origApp.getManagementContext());
        if (newApp != null) Entities.destroyAll(newApp.getManagementContext());
        if (newManagementContext != null) Entities.destroyAll(newManagementContext);
        origApp = null;
        newApp = null;
        newManagementContext = null;

        if (origManagementContext != null) Entities.destroyAll(origManagementContext);
        if (mementoDir != null) FileBasedObjectStore.deleteCompletely(mementoDir);
        origManagementContext = null;
    }

    /** rebinds, and sets newApp */
    protected T rebind() throws Exception {
        return rebind(true);
    }

    protected T rebind(boolean checkSerializable) throws Exception {
        // TODO What are sensible defaults?!
        return rebind(checkSerializable, false);
    }
    
    @SuppressWarnings("unchecked")
    protected T rebind(boolean checkSerializable, boolean terminateOrigManagementContext) throws Exception {
        if (newApp!=null || newManagementContext!=null) throw new IllegalStateException("already rebound");
        
        RebindTestUtils.waitForPersisted(origApp);
        if (checkSerializable) {
            RebindTestUtils.checkCurrentMementoSerializable(origApp);
        }
        if (terminateOrigManagementContext) {
            origManagementContext.terminate();
        }
        
        newManagementContext = createNewManagementContext();
        newApp = (T) RebindTestUtils.rebind((LocalManagementContext)newManagementContext, classLoader);
        return newApp;
    }

    @SuppressWarnings("unchecked")
    protected T rebind(RebindExceptionHandler exceptionHandler) throws Exception {
        RebindTestUtils.waitForPersisted(origApp);
        return (T) RebindTestUtils.rebind(mementoDir, classLoader, exceptionHandler);
    }

    @SuppressWarnings("unchecked")
    protected T rebind(ManagementContext newManagementContext, RebindExceptionHandler exceptionHandler) throws Exception {
        RebindTestUtils.waitForPersisted(origApp);
        return (T) RebindTestUtils.rebind(newManagementContext, mementoDir, classLoader, exceptionHandler);
    }
    
    protected BrooklynMementoManifest loadMementoManifest() throws Exception {
        newManagementContext = createNewManagementContext();
        FileBasedObjectStore objectStore = new FileBasedObjectStore(mementoDir);
        objectStore.injectManagementContext(newManagementContext);
        objectStore.prepareForSharedUse(PersistMode.AUTO, HighAvailabilityMode.DISABLED);
        BrooklynMementoPersisterToObjectStore persister = new BrooklynMementoPersisterToObjectStore(
                objectStore,
                ((ManagementContextInternal)newManagementContext).getBrooklynProperties(),
                classLoader);
        RebindExceptionHandler exceptionHandler = new RecordingRebindExceptionHandler(RebindManager.RebindFailureMode.FAIL_AT_END, RebindManager.RebindFailureMode.FAIL_AT_END);
        BrooklynMementoManifest mementoManifest = persister.loadMementoManifest(exceptionHandler);
        persister.stop(false);
        return mementoManifest;
    }

    protected void assertCatalogsEqual(BrooklynCatalog actual, BrooklynCatalog expected) {
        Set<String> actualIds = getCatalogItemIds(actual.getCatalogItems());
        Set<String> expectedIds = getCatalogItemIds(expected.getCatalogItems());
        assertEquals(actualIds.size(), Iterables.size(actual.getCatalogItems()), "id keyset size != size of catalog. Are there duplicates in the catalog?");
        assertEquals(actualIds, expectedIds);
        for (String id : actualIds) {
            assertCatalogItemsEqual(actual.getCatalogItem(id), expected.getCatalogItem(id));
        }
    }

    private Set<String> getCatalogItemIds(Iterable<CatalogItem<Object, Object>> catalogItems) {
        return FluentIterable.from(catalogItems)
                .transform(EntityFunctions.id())
                .copyInto(Sets.<String>newHashSet());
    }

    protected void assertCatalogItemsEqual(CatalogItem<?, ?> actual, CatalogItem<?, ?> expected) {
        assertEquals(actual.getClass(), expected.getClass());
        assertEquals(actual.getId(), expected.getId());
        assertEquals(actual.getDisplayName(), expected.getDisplayName());
        assertEquals(actual.getVersion(), expected.getVersion());
        assertEquals(actual.getJavaType(), expected.getJavaType());
        assertEquals(actual.getDescription(), expected.getDescription());
        assertEquals(actual.getIconUrl(), expected.getIconUrl());
        assertEquals(actual.getVersion(), expected.getVersion());
        assertEquals(actual.getCatalogItemJavaType(), expected.getCatalogItemJavaType());
        assertEquals(actual.getCatalogItemType(), expected.getCatalogItemType());
        assertEquals(actual.getSpecType(), expected.getSpecType());
        assertEquals(actual.getRegisteredTypeName(), expected.getRegisteredTypeName());
        if (actual.getLibraries() != null && expected.getLibraries() != null) {
            assertEquals(actual.getLibraries().getBundles(), expected.getLibraries().getBundles());
        } else {
            assertEquals(actual.getLibraries(), expected.getLibraries());
        }
    }
}
