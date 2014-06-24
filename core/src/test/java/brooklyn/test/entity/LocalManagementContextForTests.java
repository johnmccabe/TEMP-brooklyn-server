package brooklyn.test.entity;

import brooklyn.config.BrooklynProperties;
import brooklyn.config.BrooklynServerConfig;
import brooklyn.management.internal.AbstractManagementContext;
import brooklyn.management.internal.LocalManagementContext;

/** management context which forces an empty catalog to prevent scanning / interacting with local filesystem.
 * TODO this class could also force standard properties
 * TODO this should be more widely used in tests! */
public class LocalManagementContextForTests extends LocalManagementContext {

    public LocalManagementContextForTests(BrooklynProperties brooklynProperties) {
        super(disablePersistentStoreBackup(emptyIfNull(setEmptyCatalogAsDefault(emptyIfNull(brooklynProperties)))));
    }
    
    private static BrooklynProperties emptyIfNull(BrooklynProperties bp) {
        if (bp!=null) return bp;
        return BrooklynProperties.Factory.newEmpty();
    }

    public static BrooklynProperties setEmptyCatalogAsDefault(BrooklynProperties brooklynProperties) {
        if (brooklynProperties==null) return null;
        brooklynProperties.putIfAbsent(AbstractManagementContext.BROOKLYN_CATALOG_URL, "classpath://brooklyn-catalog-empty.xml");
        return brooklynProperties;
    }
    
    public static BrooklynProperties disablePersistentStoreBackup(BrooklynProperties brooklynProperties) {
        if (brooklynProperties==null) return null;
        brooklynProperties.putIfAbsent(BrooklynServerConfig.PERSISTENCE_BACKUPS_REQUIRED, false);
        return brooklynProperties;
    }
    
    public LocalManagementContextForTests() {
        this(null);
    }
    
}
