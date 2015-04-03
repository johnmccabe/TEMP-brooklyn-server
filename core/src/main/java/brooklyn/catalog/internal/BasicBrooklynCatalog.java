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
package brooklyn.catalog.internal;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import io.brooklyn.camp.CampPlatform;
import io.brooklyn.camp.spi.AssemblyTemplate;
import io.brooklyn.camp.spi.instantiate.AssemblyTemplateInstantiator;
import io.brooklyn.camp.spi.pdp.DeploymentPlan;
import io.brooklyn.camp.spi.pdp.Service;

import java.io.FileNotFoundException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import brooklyn.basic.AbstractBrooklynObjectSpec;
import brooklyn.basic.BrooklynObjectInternal.ConfigurationSupportInternal;
import brooklyn.camp.brooklyn.api.AssemblyTemplateSpecInstantiator;
import brooklyn.catalog.BrooklynCatalog;
import brooklyn.catalog.CatalogItem;
import brooklyn.catalog.CatalogItem.CatalogBundle;
import brooklyn.catalog.CatalogItem.CatalogItemType;
import brooklyn.catalog.CatalogPredicates;
import brooklyn.config.BrooklynServerConfig;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.location.Location;
import brooklyn.location.LocationSpec;
import brooklyn.location.basic.BasicLocationRegistry;
import brooklyn.management.ManagementContext;
import brooklyn.management.classloading.BrooklynClassLoadingContext;
import brooklyn.management.internal.EntityManagementUtils;
import brooklyn.management.internal.ManagementContextInternal;
import brooklyn.policy.Policy;
import brooklyn.policy.PolicySpec;
import brooklyn.util.collections.MutableList;
import brooklyn.util.collections.MutableMap;
import brooklyn.util.collections.MutableSet;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.flags.TypeCoercions;
import brooklyn.util.guava.Maybe;
import brooklyn.util.javalang.AggregateClassLoader;
import brooklyn.util.javalang.LoadedClassLoader;
import brooklyn.util.javalang.Reflections;
import brooklyn.util.stream.Streams;
import brooklyn.util.text.Strings;
import brooklyn.util.time.Duration;
import brooklyn.util.time.Time;
import brooklyn.util.yaml.Yamls;
import brooklyn.util.yaml.Yamls.YamlExtract;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

public class BasicBrooklynCatalog implements BrooklynCatalog {
    private static final String POLICIES_KEY = "brooklyn.policies";
    private static final String LOCATIONS_KEY = "brooklyn.locations";
    public static final String NO_VERSION = "0.0.0.SNAPSHOT";

    private static final Logger log = LoggerFactory.getLogger(BasicBrooklynCatalog.class);

    public static class BrooklynLoaderTracker {
        public static final ThreadLocal<BrooklynClassLoadingContext> loader = new ThreadLocal<BrooklynClassLoadingContext>();
        
        public static void setLoader(BrooklynClassLoadingContext val) {
            loader.set(val);
        }
        
        // TODO Stack, for recursive calls?
        public static void unsetLoader(BrooklynClassLoadingContext val) {
            loader.set(null);
        }
        
        public static BrooklynClassLoadingContext getLoader() {
            return loader.get();
        }
    }

    private final ManagementContext mgmt;
    private CatalogDo catalog;
    private volatile CatalogDo manualAdditionsCatalog;
    private volatile LoadedClassLoader manualAdditionsClasses;

    public BasicBrooklynCatalog(ManagementContext mgmt) {
        this(mgmt, CatalogDto.newNamedInstance("empty catalog", "empty catalog", "empty catalog, expected to be reset later"));
    }

    public BasicBrooklynCatalog(ManagementContext mgmt, CatalogDto dto) {
        this.mgmt = checkNotNull(mgmt, "managementContext");
        this.catalog = new CatalogDo(mgmt, dto);
    }

    public boolean blockIfNotLoaded(Duration timeout) {
        try {
            return getCatalog().blockIfNotLoaded(timeout);
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }
    
    public void reset(CatalogDto dto) {
        // Unregister all existing persisted items.
        for (CatalogItem<?, ?> toRemove : getCatalogItems()) {
            if (log.isTraceEnabled()) {
                log.trace("Scheduling item for persistence removal: {}", toRemove.getId());
            }
            mgmt.getRebindManager().getChangeListener().onUnmanaged(toRemove);
        }
        CatalogDo catalog = new CatalogDo(mgmt, dto);
        CatalogUtils.logDebugOrTraceIfRebinding(log, "Resetting "+this+" catalog to "+dto);
        catalog.load(mgmt, null);
        CatalogUtils.logDebugOrTraceIfRebinding(log, "Reloaded catalog for "+this+", now switching");
        this.catalog = catalog;

        // Inject management context into and persist all the new entries.
        for (CatalogItem<?, ?> entry : getCatalogItems()) {
            boolean setManagementContext = false;
            if (entry instanceof CatalogItemDo) {
                CatalogItemDo<?, ?> cid = CatalogItemDo.class.cast(entry);
                if (cid.getDto() instanceof CatalogItemDtoAbstract) {
                    CatalogItemDtoAbstract<?, ?> cdto = CatalogItemDtoAbstract.class.cast(cid.getDto());
                    if (cdto.getManagementContext() == null) {
                        cdto.setManagementContext((ManagementContextInternal) mgmt);
                    }
                    setManagementContext = true;
                }
            }
            if (!setManagementContext) {
                log.warn("Can't set management context on entry with unexpected type in catalog. type={}, " +
                        "expected={}", entry, CatalogItemDo.class);
            }
            if (log.isTraceEnabled()) {
                log.trace("Scheduling item for persistence addition: {}", entry.getId());
            }
            mgmt.getRebindManager().getChangeListener().onManaged(entry);
        }

    }

    /**
     * Resets the catalog to the given entries
     */
    @Override
    public void reset(Collection<CatalogItem<?, ?>> entries) {
        CatalogDto newDto = CatalogDto.newDtoFromCatalogItems(entries, "explicit-catalog-reset");
        reset(newDto);
    }
    
    public CatalogDo getCatalog() {
        return catalog;
    }

    protected CatalogItemDo<?,?> getCatalogItemDo(String symbolicName, String version) {
        String fixedVersionId = getFixedVersionId(symbolicName, version);
        if (fixedVersionId == null) {
            //no items with symbolicName exist
            return null;
        }

        String versionedId = CatalogUtils.getVersionedId(symbolicName, fixedVersionId);
        CatalogItemDo<?, ?> item = null;
        //TODO should remove "manual additions" bucket; just have one map a la osgi
        if (manualAdditionsCatalog!=null) item = manualAdditionsCatalog.getIdCache().get(versionedId);
        if (item == null) item = catalog.getIdCache().get(versionedId);
        return item;
    }
    
    private String getFixedVersionId(String symbolicName, String version) {
        if (!DEFAULT_VERSION.equals(version)) {
            return version;
        } else {
            return getDefaultVersion(symbolicName);
        }
    }

    private String getDefaultVersion(String symbolicName) {
        Iterable<CatalogItem<Object, Object>> versions = getCatalogItems(CatalogPredicates.symbolicName(Predicates.equalTo(symbolicName)));
        Collection<CatalogItem<Object, Object>> orderedVersions = sortVersionsDesc(versions);
        if (!orderedVersions.isEmpty()) {
            return orderedVersions.iterator().next().getVersion();
        } else {
            return null;
        }
    }

    private <T,SpecT> Collection<CatalogItem<T,SpecT>> sortVersionsDesc(Iterable<CatalogItem<T,SpecT>> versions) {
        return ImmutableSortedSet.orderedBy(CatalogItemComparator.<T,SpecT>getInstance()).addAll(versions).build();
    }

    @Override
    @Deprecated
    public CatalogItem<?,?> getCatalogItem(String symbolicName) {
        return getCatalogItem(symbolicName, DEFAULT_VERSION);
    }
    
    @Override
    public CatalogItem<?,?> getCatalogItem(String symbolicName, String version) {
        if (symbolicName == null) return null;
        checkNotNull(version, "version");
        CatalogItemDo<?, ?> itemDo = getCatalogItemDo(symbolicName, version);
        if (itemDo == null) return null;
        return itemDo.getDto();
    }
    
    @Override
    @Deprecated
    public void deleteCatalogItem(String id) {
        //Delete only if installed through the
        //deprecated methods. Don't support DEFAULT_VERSION for delete.
        deleteCatalogItem(id, NO_VERSION);
    }

    @Override
    public void deleteCatalogItem(String symbolicName, String version) {
        log.debug("Deleting manual catalog item from "+mgmt+": "+symbolicName + ":" + version);
        checkNotNull(symbolicName, "id");
        checkNotNull(version, "version");
        if (DEFAULT_VERSION.equals(version)) {
            throw new IllegalStateException("Deleting items with unspecified version (argument DEFAULT_VERSION) not supported.");
        }
        CatalogItem<?, ?> item = getCatalogItem(symbolicName, version);
        CatalogItemDtoAbstract<?,?> itemDto = getAbstractCatalogItem(item);
        if (itemDto == null) {
            throw new NoSuchElementException("No catalog item found with id "+symbolicName);
        }
        if (manualAdditionsCatalog==null) loadManualAdditionsCatalog();
        manualAdditionsCatalog.deleteEntry(itemDto);
        
        // Ensure the cache is de-populated
        getCatalog().deleteEntry(itemDto);

        // And indicate to the management context that it should be removed.
        if (log.isTraceEnabled()) {
            log.trace("Scheduling item for persistence removal: {}", itemDto.getId());
        }
        if (itemDto.getCatalogItemType() == CatalogItemType.LOCATION) {
            @SuppressWarnings("unchecked")
            CatalogItem<Location,LocationSpec<?>> locationItem = (CatalogItem<Location, LocationSpec<?>>) itemDto;
            ((BasicLocationRegistry)mgmt.getLocationRegistry()).removeDefinedLocation(locationItem);
        }
        mgmt.getRebindManager().getChangeListener().onUnmanaged(itemDto);

    }

    @Override
    @Deprecated
    public <T,SpecT> CatalogItem<T,SpecT> getCatalogItem(Class<T> type, String id) {
        return getCatalogItem(type, id, DEFAULT_VERSION);
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public <T,SpecT> CatalogItem<T,SpecT> getCatalogItem(Class<T> type, String id, String version) {
        if (id==null || version==null) return null;
        CatalogItem<?,?> result = getCatalogItem(id, version);
        if (result==null) return null;
        if (type==null || type.isAssignableFrom(result.getCatalogItemJavaType())) 
            return (CatalogItem<T,SpecT>)result;
        return null;
    }

    @Override
    public void persist(CatalogItem<?, ?> catalogItem) {
        checkArgument(getCatalogItem(catalogItem.getSymbolicName(), catalogItem.getVersion()) != null, "Unknown catalog item %s", catalogItem);
        mgmt.getRebindManager().getChangeListener().onChanged(catalogItem);
    }
    
    @Override
    public ClassLoader getRootClassLoader() {
        return catalog.getRootClassLoader();
    }

    /**
     * Loads this catalog. No effect if already loaded.
     */
    public void load() {
        log.debug("Loading catalog for " + mgmt);
        getCatalog().load(mgmt, null);
        if (log.isDebugEnabled()) {
            log.debug("Loaded catalog for " + mgmt + ": " + catalog + "; search classpath is " + catalog.getRootClassLoader());
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T, SpecT> SpecT createSpec(CatalogItem<T, SpecT> item) {
        CatalogItemDo<T,SpecT> loadedItem = (CatalogItemDo<T, SpecT>) getCatalogItemDo(item.getSymbolicName(), item.getVersion());
        if (loadedItem == null) return null;
        Class<SpecT> specType = loadedItem.getSpecType();
        if (specType==null) return null;

        String yaml = loadedItem.getPlanYaml();

        if (yaml!=null) {
            // preferred way is to parse the yaml, to resolve references late;
            // the parsing on load is to populate some fields, but it is optional.
            // TODO messy for location and policy that we need brooklyn.{locations,policies} root of the yaml, but it works;
            // see related comment when the yaml is set, in addAbstractCatalogItems
            // (not sure if anywhere else relies on that syntax; if not, it should be easy to fix!)
            DeploymentPlan plan = makePlanFromYaml(yaml);
            BrooklynClassLoadingContext loader = CatalogUtils.newClassLoadingContext(mgmt, item);
            SpecT spec;
            switch (item.getCatalogItemType()) {
                case TEMPLATE:
                case ENTITY:
                    spec = createEntitySpec(loadedItem.getSymbolicName(), plan, loader);
                    break;
                case POLICY:
                    spec = createPolicySpec(plan, loader);
                    break;
                case LOCATION:
                    spec = createLocationSpec(plan, loader);
                    break;
                default: throw new RuntimeException("Only entity, policy & location catalog items are supported. Unsupported catalog item type " + item.getCatalogItemType());
            }
            ((AbstractBrooklynObjectSpec<?, ?>)spec).catalogItemId(item.getId());
            return spec;
        }

        // revert to legacy mechanism
        SpecT spec = null;
        try {
            if (loadedItem.getJavaType()!=null) {
                SpecT specT = (SpecT) Reflections.findMethod(specType, "create", Class.class).invoke(null, loadedItem.loadJavaClass(mgmt));
                spec = specT;
            }
        } catch (Exception e) {
            Exceptions.propagateIfFatal(e);
            throw new IllegalStateException("Unsupported creation of spec type "+specType+"; it must have a public static create(Class) method", e);
        }

        if (spec==null) 
            throw new IllegalStateException("Unknown how to create instance of "+this);

        return spec;
    }

    @SuppressWarnings("unchecked")
    private <T, SpecT> SpecT createEntitySpec(String symbolicName, DeploymentPlan plan, BrooklynClassLoadingContext loader) {
        CampPlatform camp = BrooklynServerConfig.getCampPlatform(mgmt).get();

        // TODO should not register new AT each time we instantiate from the same plan; use some kind of cache
        AssemblyTemplate at;
        BrooklynLoaderTracker.setLoader(loader);
        try {
            at = camp.pdp().registerDeploymentPlan(plan);
        } finally {
            BrooklynLoaderTracker.unsetLoader(loader);
        }

        try {
            AssemblyTemplateInstantiator instantiator = at.getInstantiator().newInstance();
            if (instantiator instanceof AssemblyTemplateSpecInstantiator) {
                return (SpecT) ((AssemblyTemplateSpecInstantiator)instantiator).createNestedSpec(at, camp, loader, MutableSet.of(symbolicName));
            }
            throw new IllegalStateException("Unable to instantiate YAML; incompatible instantiator "+instantiator+" for "+at);
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }

    private <T, SpecT> SpecT createPolicySpec(DeploymentPlan plan, BrooklynClassLoadingContext loader) {
        //Would ideally re-use io.brooklyn.camp.brooklyn.spi.creation.BrooklynEntityDecorationResolver.PolicySpecResolver
        //but it is CAMP specific and there is no easy way to get hold of it.
        Object policies = checkNotNull(plan.getCustomAttributes().get(POLICIES_KEY), "policy config");
        if (!(policies instanceof Iterable<?>)) {
            throw new IllegalStateException("The value of " + POLICIES_KEY + " must be an Iterable.");
        }

        Object policy = Iterables.getOnlyElement((Iterable<?>)policies);

        return createPolicySpec(loader, policy);
    }

    @SuppressWarnings("unchecked")
    private <T, SpecT> SpecT createPolicySpec(BrooklynClassLoadingContext loader, Object policy) {
        Map<String, Object> config;
        if (policy instanceof String) {
            config = ImmutableMap.<String, Object>of("type", policy);
        } else if (policy instanceof Map) {
            config = (Map<String, Object>) policy;
        } else {
            throw new IllegalStateException("Policy expected to be string or map. Unsupported object type " + policy.getClass().getName() + " (" + policy.toString() + ")");
        }

        String type = (String) checkNotNull(Yamls.getMultinameAttribute(config, "policy_type", "policyType", "type"), "policy type");
        Map<String, Object> brooklynConfig = (Map<String, Object>) config.get("brooklyn.config");
        PolicySpec<? extends Policy> spec = PolicySpec.create(loader.loadClass(type, Policy.class));
        if (brooklynConfig != null) {
            spec.configure(brooklynConfig);
        }
        return (SpecT) spec;
    }
    
    private <T, SpecT> SpecT createLocationSpec(DeploymentPlan plan, BrooklynClassLoadingContext loader) {
        // See #createPolicySpec; this impl is modeled on that.
        // spec.catalogItemId is set by caller
        Object locations = checkNotNull(plan.getCustomAttributes().get(LOCATIONS_KEY), "location config");
        if (!(locations instanceof Iterable<?>)) {
            throw new IllegalStateException("The value of " + LOCATIONS_KEY + " must be an Iterable.");
        }

        Object location = Iterables.getOnlyElement((Iterable<?>)locations);

        return createLocationSpec(loader, location); 
    }

    @SuppressWarnings("unchecked")
    private <T, SpecT> SpecT createLocationSpec(BrooklynClassLoadingContext loader, Object location) {
        Map<String, Object> config;
        if (location instanceof String) {
            config = ImmutableMap.<String, Object>of("type", location);
        } else if (location instanceof Map) {
            config = (Map<String, Object>) location;
        } else {
            throw new IllegalStateException("Location expected to be string or map. Unsupported object type " + location.getClass().getName() + " (" + location.toString() + ")");
        }

        String type = (String) checkNotNull(Yamls.getMultinameAttribute(config, "location_type", "locationType", "type"), "location type");
        Map<String, Object> brooklynConfig = (Map<String, Object>) config.get("brooklyn.config");
        Maybe<Class<? extends Location>> javaClass = loader.tryLoadClass(type, Location.class);
        if (javaClass.isPresent()) {
            LocationSpec<?> spec = LocationSpec.create(javaClass.get());
            if (brooklynConfig != null) {
                spec.configure(brooklynConfig);
            }
            return (SpecT) spec;
        } else {
            Maybe<Location> loc = mgmt.getLocationRegistry().resolve(type, false, brooklynConfig);
            if (loc.isPresent()) {
                // TODO extensions?
                Map<String, Object> locConfig = ((ConfigurationSupportInternal)loc.get().config()).getBag().getAllConfig();
                Class<? extends Location> locType = loc.get().getClass();
                Set<Object> locTags = loc.get().tags().getTags();
                String locDisplayName = loc.get().getDisplayName();
                return (SpecT) LocationSpec.create(locType)
                        .configure(locConfig)
                        .displayName(locDisplayName)
                        .tags(locTags);
            } else {
                throw new IllegalStateException("No class or resolver found for location type "+type);
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    /** @deprecated since 0.7.0 use {@link #createSpec(CatalogItem)} */
    @Deprecated
    public <T,SpecT> Class<? extends T> loadClass(CatalogItem<T,SpecT> item) {
        if (log.isDebugEnabled())
            log.debug("Loading class for catalog item " + item);
        checkNotNull(item);
        CatalogItemDo<?,?> loadedItem = getCatalogItemDo(item.getSymbolicName(), item.getVersion());
        if (loadedItem==null) throw new NoSuchElementException("Unable to load '"+item.getId()+"' to instantiate it");
        return (Class<? extends T>) loadedItem.getJavaClass();
    }
    
    @SuppressWarnings("unchecked")
    @Override
    /** @deprecated since 0.7.0 use {@link #createSpec(CatalogItem)} */
    @Deprecated
    public <T> Class<? extends T> loadClassByType(String typeName, Class<T> typeClass) {
        final CatalogItem<?,?> resultI = getCatalogItemForType(typeName);

        if (resultI == null) {
            throw new NoSuchElementException("Unable to find catalog item for type "+typeName);
        }

        return (Class<? extends T>) loadClass(resultI);
    }

    @Deprecated /** @deprecated since 0.7.0 only used by other deprecated items */ 
    private <T,SpecT> CatalogItemDtoAbstract<T,SpecT> getAbstractCatalogItem(CatalogItem<T,SpecT> item) {
        while (item instanceof CatalogItemDo) item = ((CatalogItemDo<T,SpecT>)item).itemDto;
        if (item==null) return null;
        if (item instanceof CatalogItemDtoAbstract) return (CatalogItemDtoAbstract<T,SpecT>) item;
        throw new IllegalStateException("Cannot unwrap catalog item '"+item+"' (type "+item.getClass()+") to restore DTO");
    }
    
    @SuppressWarnings("unchecked")
    private <T> Maybe<T> getFirstAs(Map<?,?> map, Class<T> type, String firstKey, String ...otherKeys) {
        if (map==null) return Maybe.absent("No map available");
        String foundKey = null;
        Object value = null;
        if (map.containsKey(firstKey)) foundKey = firstKey;
        else for (String key: otherKeys) {
            if (map.containsKey(key)) {
                foundKey = key;
                break;
            }
        }
        if (foundKey==null) return Maybe.absent("Missing entry '"+firstKey+"'");
        value = map.get(foundKey);
        if (type.equals(String.class) && Number.class.isInstance(value)) value = value.toString();
        if (!type.isInstance(value)) 
            throw new IllegalArgumentException("Entry for '"+firstKey+"' should be of type "+type+", not "+value.getClass());
        return Maybe.of((T)value);
    }
    
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private Maybe<Map<?,?>> getFirstAsMap(Map<?,?> map, String firstKey, String ...otherKeys) {
        return (Maybe<Map<?,?>>)(Maybe) getFirstAs(map, Map.class, firstKey, otherKeys);
    }

    private List<CatalogItemDtoAbstract<?,?>> addAbstractCatalogItems(String yaml, Boolean whenAddingAsDtoShouldWeForce) {
        Map<?,?> itemDef = Yamls.getAs(Yamls.parseAll(yaml), Map.class);
        Map<?,?> catalogMetadata = getFirstAsMap(itemDef, "brooklyn.catalog", "catalog").orNull();
        if (catalogMetadata==null)
            log.warn("No `brooklyn.catalog` supplied in catalog request; using legacy mode for "+itemDef);
        catalogMetadata = MutableMap.copyOf(catalogMetadata);

        List<CatalogItemDtoAbstract<?, ?>> result = MutableList.of();
        
        addAbstractCatalogItems(Yamls.getTextOfYamlAtPath(yaml, "brooklyn.catalog").getMatchedYamlTextOrWarn(), 
            catalogMetadata, result, null, whenAddingAsDtoShouldWeForce);
        
        itemDef.remove("brooklyn.catalog");
        catalogMetadata.remove("item");
        catalogMetadata.remove("items");
        if (!itemDef.isEmpty()) {
            log.debug("Reading brooklyn.catalog peer keys as item ('top-level syntax')");
            Map<String,?> rootItem = MutableMap.of("item", itemDef);
            String rootItemYaml = yaml;
            YamlExtract yamlExtract = Yamls.getTextOfYamlAtPath(rootItemYaml, "brooklyn.catalog");
            String match = yamlExtract.withOriginalIndentation(true).withKeyIncluded(true).getMatchedYamlTextOrWarn();
            if (match!=null) {
                if (rootItemYaml.startsWith(match)) rootItemYaml = Strings.removeFromStart(rootItemYaml, match);
                else rootItemYaml = Strings.replaceAllNonRegex(rootItemYaml, "\n"+match, "");
            }
            addAbstractCatalogItems("item:\n"+makeAsIndentedObject(rootItemYaml), rootItem, result, catalogMetadata, whenAddingAsDtoShouldWeForce);
        }
        
        return result;
    }

    @SuppressWarnings("unchecked")
    private void addAbstractCatalogItems(String sourceYaml, Map<?,?> itemMetadata, List<CatalogItemDtoAbstract<?, ?>> result, Map<?,?> parentMetadata, Boolean whenAddingAsDtoShouldWeForce) {

        if (sourceYaml==null) sourceYaml = new Yaml().dump(itemMetadata);

        // TODO:
//        docs used as test cases -- (doc assertions that these match -- or import -- known test cases yamls)
//        multiple versions in web app root

        Map<Object,Object> catalogMetadata = MutableMap.builder().putAll(parentMetadata).putAll(itemMetadata).build();
        
        // libraries we treat specially, to append the list, with the child's list preferred in classloading order
        List<?> librariesL = MutableList.copyOf(getFirstAs(itemMetadata, List.class, "brooklyn.libraries", "libraries").orNull())
            .appendAll(getFirstAs(parentMetadata, List.class, "brooklyn.libraries", "libraries").orNull());
        if (!librariesL.isEmpty())
            catalogMetadata.put("brooklyn.libraries", librariesL);
        Collection<CatalogBundle> libraries = CatalogItemDtoAbstract.parseLibraries(librariesL);

        Object items = catalogMetadata.remove("items");
        Object item = catalogMetadata.remove("item");

        if (items!=null) {
            int count = 0;
            for (Map<?,?> i: ((List<Map<?,?>>)items)) {
                addAbstractCatalogItems(Yamls.getTextOfYamlAtPath(sourceYaml, "items", count).getMatchedYamlTextOrWarn(), 
                    i, result, catalogMetadata, whenAddingAsDtoShouldWeForce);
                count++;
            }
        }
        
        if (item==null) return;

        // now look at the actual item, first correcting the sourceYaml and interpreting the catalog metadata
        String itemYaml = Yamls.getTextOfYamlAtPath(sourceYaml, "item").getMatchedYamlTextOrWarn();
        if (itemYaml!=null) sourceYaml = itemYaml;
        else sourceYaml = new Yaml().dump(item);
        
        CatalogItemType itemType = TypeCoercions.coerce(getFirstAs(catalogMetadata, Object.class, "item.type", "itemType", "item_type").orNull(), CatalogItemType.class);
        
        String id = getFirstAs(catalogMetadata, String.class, "id").orNull();
        String version = getFirstAs(catalogMetadata, String.class, "version").orNull();
        String symbolicName = getFirstAs(catalogMetadata, String.class, "symbolicName").orNull();
        String displayName = getFirstAs(catalogMetadata, String.class, "displayName").orNull();
        String name = getFirstAs(catalogMetadata, String.class, "name").orNull();

        if ((Strings.isNonBlank(id) || Strings.isNonBlank(symbolicName)) && 
                Strings.isNonBlank(displayName) &&
                Strings.isNonBlank(name) && !name.equals(displayName)) {
            log.warn("Name property will be ignored due to the existence of displayName and at least one of id, symbolicName");
        }
                
        DeploymentPlan plan = null;
        try {
            plan = makePlanFromYaml(sourceYaml);
        } catch (Exception e) {
            Exceptions.propagateIfFatal(e);
            if (itemType==CatalogItemType.ENTITY || itemType==CatalogItemType.TEMPLATE)
                log.warn("Could not parse item YAML for "+itemType+" (registering anyway): "+e+"\n"+sourceYaml);
        }
        
        if (Strings.isBlank(id)) {
            // let ID be inferred, especially from name, to support style where only "name" is specified, with inline version
            if (Strings.isNonBlank(symbolicName) && Strings.isNonBlank(version)) {
                id = symbolicName + ":" + version;
            } else if (Strings.isNonBlank(name)) {
                id = name;
            }
        }

        final String catalogSymbolicName;
        if (Strings.isNonBlank(symbolicName)) {
            catalogSymbolicName = symbolicName;
        } else if (Strings.isNonBlank(id)) {
            if (Strings.isNonBlank(id) && CatalogUtils.looksLikeVersionedId(id)) {
                catalogSymbolicName = CatalogUtils.getIdFromVersionedId(id);
            } else {
                catalogSymbolicName = id;
            }
        } else if (plan!=null && Strings.isNonBlank(plan.getName())) {
            catalogSymbolicName = plan.getName();
        } else if (plan!=null && plan.getServices().size()==1) {
            Service svc = Iterables.getOnlyElement(plan.getServices());
            if (Strings.isBlank(svc.getServiceType())) {
                throw new IllegalStateException("CAMP service type not expected to be missing for " + svc);
            }
            catalogSymbolicName = svc.getServiceType();
        } else {
            log.error("Can't infer catalog item symbolicName from the following plan:\n" + sourceYaml);
            throw new IllegalStateException("Can't infer catalog item symbolicName from catalog item metadata");
        }

        final String catalogVersion;
        if (CatalogUtils.looksLikeVersionedId(id)) {
            catalogVersion = CatalogUtils.getVersionFromVersionedId(id);
            if (version != null  && !catalogVersion.equals(version)) {
                throw new IllegalArgumentException("Discrepency between version set in id " + catalogVersion + " and version property " + version);
            }
        } else if (Strings.isNonBlank(version)) {
            catalogVersion = version;
        } else {
            log.warn("No version specified for catalog item " + catalogSymbolicName + ". Using default value.");
            catalogVersion = null;
        }

        final String catalogDisplayName;
        if (Strings.isNonBlank(displayName)) {
            catalogDisplayName = displayName;
        } else if (Strings.isNonBlank(name)) {
            catalogDisplayName = name;
        } else if (Strings.isNonBlank(plan.getName())) {
            catalogDisplayName = plan.getName();
        } else {
            catalogDisplayName = null;
        }

        final String description = getFirstAs(catalogMetadata, String.class, "description").orNull();
        final String catalogDescription;
        if (Strings.isNonBlank(description)) {
            catalogDescription = description;
        } else if (Strings.isNonBlank(plan.getDescription())) {
            catalogDescription = plan.getDescription();
        } else {
            catalogDescription = null;
        }

        final String catalogIconUrl = getFirstAs(catalogMetadata, String.class, "icon.url", "iconUrl", "icon_url").orNull();

        final String deprecated = getFirstAs(catalogMetadata, String.class, "deprecated").orNull();
        final Boolean catalogDeprecated = Boolean.valueOf(deprecated);

        CatalogUtils.installLibraries(mgmt, libraries);

        String versionedId = CatalogUtils.getVersionedId(catalogSymbolicName, catalogVersion!=null ? catalogVersion : NO_VERSION);
        BrooklynClassLoadingContext loader = CatalogUtils.newClassLoadingContext(mgmt, versionedId, libraries);
        
        CatalogItemType inferredItemType = inferType(plan);
        boolean usePlan;
        if (inferredItemType!=null) {
            if (itemType!=null) {
                if (itemType==CatalogItemType.TEMPLATE && inferredItemType==CatalogItemType.ENTITY) {
                    // template - we use the plan, but coupled with the type to make a catalog template item
                    usePlan = true;
                } else if (itemType==inferredItemType) {
                    // if the plan showed the type, it is either a parsed entity or a legacy spec type
                    usePlan = true;
                    if (itemType==CatalogItemType.TEMPLATE || itemType==CatalogItemType.ENTITY) {
                        // normal
                    } else {
                        log.warn("Legacy syntax used for "+itemType+" "+catalogSymbolicName+": should declare item.type and specify type");
                    }
                } else {
                    throw new IllegalStateException("Explicit type "+itemType+" "+catalogSymbolicName+" does not match blueprint inferred type "+inferredItemType);
                }
            } else {
                // no type was declared, use the inferred type from the plan
                itemType = inferredItemType;
                usePlan = true;
            }
        } else if (itemType!=null) {
            if (itemType==CatalogItemType.TEMPLATE || itemType==CatalogItemType.ENTITY) {
                log.warn("Incomplete plan detected for "+itemType+" "+catalogSymbolicName+"; will likely fail subsequently");
                usePlan = true;
            } else {
                usePlan = false;
            }
        } else {
            throw new IllegalStateException("Unable to detect type for "+catalogSymbolicName+"; error in catalog metadata or blueprint");
        }
        
        AbstractBrooklynObjectSpec<?, ?> spec;
        if (usePlan) {
            spec = createSpecFromPlan(catalogSymbolicName, plan, loader);
        } else {
            String key;
            switch (itemType) {
            // we don't actually need the spec here, since the yaml is what is used at load time,
            // but it isn't a bad idea to confirm that it resolves
            case POLICY: 
                spec = createPolicySpec(loader, item);
                key = POLICIES_KEY;
                break;
            case LOCATION: 
                spec = createLocationSpec(loader, item); 
                key = LOCATIONS_KEY;
                break;
            default: throw new IllegalStateException("Cannot create "+itemType+" "+catalogSymbolicName+"; invalid metadata or blueprint");
            }
            // TODO currently we then convert yaml to legacy brooklyn.{location,policy} syntax for subsequent usage; 
            // would be better to (in the other path) convert to {type: ..., brooklyn.config: ... } format and expect that elsewhere
            sourceYaml = key + ":\n" + makeAsIndentedList(sourceYaml);
        }

        CatalogItemDtoAbstract<?, ?> dto = createItemBuilder(itemType, spec, catalogSymbolicName, catalogVersion)
            .libraries(libraries)
            .displayName(catalogDisplayName)
            .description(catalogDescription)
            .deprecated(catalogDeprecated)
            .iconUrl(catalogIconUrl)
            .plan(sourceYaml)
            .build();

        dto.setManagementContext((ManagementContextInternal) mgmt);
        if (whenAddingAsDtoShouldWeForce!=null) {
            addItemDto(dto, whenAddingAsDtoShouldWeForce);
        }
        result.add(dto);
    }

    private String makeAsIndentedList(String yaml) {
        String[] lines = yaml.split("\n");
        lines[0] = "- "+lines[0];
        for (int i=1; i<lines.length; i++)
            lines[i] = "  " + lines[i];
        return Strings.join(lines, "\n");
    }

    private String makeAsIndentedObject(String yaml) {
        String[] lines = yaml.split("\n");
        for (int i=0; i<lines.length; i++)
            lines[i] = "  " + lines[i];
        return Strings.join(lines, "\n");
    }

    private CatalogItemType inferType(DeploymentPlan plan) {
        if (plan==null) return null;
        if (isEntityPlan(plan)) return CatalogItemType.ENTITY; 
        if (isPolicyPlan(plan)) return CatalogItemType.POLICY;
        if (isLocationPlan(plan)) return CatalogItemType.LOCATION;
        return null;
    }

    private AbstractBrooklynObjectSpec<?,?> createSpecFromPlan(String symbolicName, DeploymentPlan plan, BrooklynClassLoadingContext loader) {
        if (isPolicyPlan(plan)) {
            return createPolicySpec(plan, loader);
        } else if (isLocationPlan(plan)) {
            return createLocationSpec(plan, loader);
        } else {
            return createEntitySpec(symbolicName, plan, loader);
        }
    }

    private CatalogItemBuilder<?> createItemBuilder(CatalogItemType itemType, AbstractBrooklynObjectSpec<?, ?> spec, String itemId, String version) {
        if (itemType!=null) {
            switch (itemType) {
            case ENTITY: return CatalogItemBuilder.newEntity(itemId, version);
            case TEMPLATE: return CatalogItemBuilder.newTemplate(itemId, version);
            case POLICY: return CatalogItemBuilder.newPolicy(itemId, version);
            case LOCATION: return CatalogItemBuilder.newLocation(itemId, version);
            }
            log.warn("Legacy syntax for "+itemId+"; unexpected item.type "+itemType);
        } else {
            log.warn("Legacy syntax for "+itemId+"; no item.type declared or inferred");
        }
        
        // @deprecated - should not come here
        if (spec instanceof EntitySpec) {
            if (isApplicationSpec((EntitySpec<?>)spec)) {
                return CatalogItemBuilder.newTemplate(itemId, version);
            } else {
                return CatalogItemBuilder.newEntity(itemId, version);
            }
        } else if (spec instanceof PolicySpec) {
            return CatalogItemBuilder.newPolicy(itemId, version);
        } else if (spec instanceof LocationSpec) {
            return CatalogItemBuilder.newLocation(itemId, version);
        } else {
            throw new IllegalStateException("Unknown spec type " + spec.getClass().getName() + " (" + spec + ")");
        }
    }

    private boolean isApplicationSpec(EntitySpec<?> spec) {
        return !Boolean.TRUE.equals(spec.getConfig().get(EntityManagementUtils.WRAPPER_APP_MARKER));
    }

    private boolean isEntityPlan(DeploymentPlan plan) {
        return plan!=null && !plan.getServices().isEmpty() || !plan.getArtifacts().isEmpty();
    }
    
    private boolean isPolicyPlan(DeploymentPlan plan) {
        return !isEntityPlan(plan) && plan.getCustomAttributes().containsKey(POLICIES_KEY);
    }

    private boolean isLocationPlan(DeploymentPlan plan) {
        return !isEntityPlan(plan) && plan.getCustomAttributes().containsKey(LOCATIONS_KEY);
    }

    private DeploymentPlan makePlanFromYaml(String yaml) {
        CampPlatform camp = BrooklynServerConfig.getCampPlatform(mgmt).get();
        return camp.pdp().parseDeploymentPlan(Streams.newReaderWithContents(yaml));
    }

    @Override
    public CatalogItem<?,?> addItem(String yaml) {
        return addItem(yaml, false);
    }

    @Override
    public List<? extends CatalogItem<?,?>> addItems(String yaml) {
        return addItems(yaml, false);
    }

    @Override
    public CatalogItem<?,?> addItem(String yaml, boolean forceUpdate) {
        return Iterables.getOnlyElement(addItems(yaml, forceUpdate));
    }
    
    @Override
    public List<? extends CatalogItem<?,?>> addItems(String yaml, boolean forceUpdate) {
        log.debug("Adding manual catalog item to "+mgmt+": "+yaml);
        checkNotNull(yaml, "yaml");
        List<CatalogItemDtoAbstract<?, ?>> result = addAbstractCatalogItems(yaml, forceUpdate);
        // previously we did this here, but now we do it on each item, in case #2 refers to #1
//        for (CatalogItemDtoAbstract<?, ?> item: result) {
//            addItemDto(item, forceUpdate);
//        }
        return result;
    }
    
    private CatalogItem<?,?> addItemDto(CatalogItemDtoAbstract<?, ?> itemDto, boolean forceUpdate) {
        checkItemNotExists(itemDto, forceUpdate);

        if (manualAdditionsCatalog==null) loadManualAdditionsCatalog();
        manualAdditionsCatalog.addEntry(itemDto);

        // Ensure the cache is populated and it is persisted by the management context
        getCatalog().addEntry(itemDto);

        // Request that the management context persist the item.
        if (log.isTraceEnabled()) {
            log.trace("Scheduling item for persistence addition: {}", itemDto.getId());
        }
        if (itemDto.getCatalogItemType() == CatalogItemType.LOCATION) {
            @SuppressWarnings("unchecked")
            CatalogItem<Location,LocationSpec<?>> locationItem = (CatalogItem<Location, LocationSpec<?>>) itemDto;
            ((BasicLocationRegistry)mgmt.getLocationRegistry()).updateDefinedLocation(locationItem);
        }
        mgmt.getRebindManager().getChangeListener().onManaged(itemDto);

        return itemDto;
    }

    private void checkItemNotExists(CatalogItem<?,?> itemDto, boolean forceUpdate) {
        if (!forceUpdate && getCatalogItemDo(itemDto.getSymbolicName(), itemDto.getVersion()) != null) {
            throw new IllegalStateException("Updating existing catalog entries is forbidden: " +
                    itemDto.getSymbolicName() + ":" + itemDto.getVersion() + ". Use forceUpdate argument to override.");
        }
    }

    @Override @Deprecated /** @deprecated see super */
    public void addItem(CatalogItem<?,?> item) {
        //assume forceUpdate for backwards compatibility
        log.debug("Adding manual catalog item to "+mgmt+": "+item);
        checkNotNull(item, "item");
        CatalogUtils.installLibraries(mgmt, item.getLibraries());
        if (manualAdditionsCatalog==null) loadManualAdditionsCatalog();
        manualAdditionsCatalog.addEntry(getAbstractCatalogItem(item));
    }

    @Override @Deprecated /** @deprecated see super */
    public CatalogItem<?,?> addItem(Class<?> type) {
        //assume forceUpdate for backwards compatibility
        log.debug("Adding manual catalog item to "+mgmt+": "+type);
        checkNotNull(type, "type");
        if (manualAdditionsCatalog==null) loadManualAdditionsCatalog();
        manualAdditionsClasses.addClass(type);
        return manualAdditionsCatalog.classpath.addCatalogEntry(type);
    }

    private synchronized void loadManualAdditionsCatalog() {
        if (manualAdditionsCatalog!=null) return;
        CatalogDto manualAdditionsCatalogDto = CatalogDto.newNamedInstance(
                "Manual Catalog Additions", "User-additions to the catalog while Brooklyn is running, " +
                "created "+Time.makeDateString(),
                "manual-additions");
        CatalogDo manualAdditionsCatalog = catalog.addCatalog(manualAdditionsCatalogDto);
        if (manualAdditionsCatalog==null) {
            // not hard to support, but slightly messy -- probably have to use ID's to retrieve the loaded instance
            // for now block once, then retry
            log.warn("Blocking until catalog is loaded before changing it");
            boolean loaded = blockIfNotLoaded(Duration.TEN_SECONDS);
            if (!loaded)
                log.warn("Catalog still not loaded after delay; subsequent operations may fail");
            manualAdditionsCatalog = catalog.addCatalog(manualAdditionsCatalogDto);
            if (manualAdditionsCatalog==null) {
                throw new UnsupportedOperationException("Catalogs cannot be added until the base catalog is loaded, and catalog is taking a while to load!");
            }
        }
        
        log.debug("Creating manual additions catalog for "+mgmt+": "+manualAdditionsCatalog);
        manualAdditionsClasses = new LoadedClassLoader();
        ((AggregateClassLoader)manualAdditionsCatalog.classpath.getLocalClassLoader()).addFirst(manualAdditionsClasses);
        
        // expose when we're all done
        this.manualAdditionsCatalog = manualAdditionsCatalog;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public <T,SpecT> Iterable<CatalogItem<T,SpecT>> getCatalogItems() {
        if (!getCatalog().isLoaded()) {
            // some callers use this to force the catalog to load (maybe when starting as hot_backup without a catalog ?)
            log.debug("Forcing catalog load on access of catalog items");
            load();
        }
        return ImmutableList.copyOf((Iterable)catalog.getIdCache().values());
    }
    
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public <T,SpecT> Iterable<CatalogItem<T,SpecT>> getCatalogItems(Predicate<? super CatalogItem<T,SpecT>> filter) {
        Iterable<CatalogItemDo<T,SpecT>> filtered = Iterables.filter((Iterable)catalog.getIdCache().values(), (Predicate<CatalogItem<T,SpecT>>)(Predicate) filter);
        return Iterables.transform(filtered, BasicBrooklynCatalog.<T,SpecT>itemDoToDto());
    }

    private static <T,SpecT> Function<CatalogItemDo<T,SpecT>, CatalogItem<T,SpecT>> itemDoToDto() {
        return new Function<CatalogItemDo<T,SpecT>, CatalogItem<T,SpecT>>() {
            @Override
            public CatalogItem<T,SpecT> apply(@Nullable CatalogItemDo<T,SpecT> item) {
                return item.getDto();
            }
        };
    }

    transient CatalogXmlSerializer serializer;
    
    public String toXmlString() {
        if (serializer==null) loadSerializer();
        return serializer.toString(catalog.dto);
    }
    
    private synchronized void loadSerializer() {
        if (serializer==null) 
            serializer = new CatalogXmlSerializer();
    }

    public void resetCatalogToContentsAtConfiguredUrl() {
        CatalogDto dto = null;
        String catalogUrl = mgmt.getConfig().getConfig(BrooklynServerConfig.BROOKLYN_CATALOG_URL);
        try {
            if (!Strings.isEmpty(catalogUrl)) {
                dto = CatalogDto.newDtoFromUrl(catalogUrl);
                if (log.isDebugEnabled()) {
                    log.debug("Loading catalog from {}: {}", catalogUrl, catalog);
                }
            }
        } catch (Exception e) {
            if (Throwables.getRootCause(e) instanceof FileNotFoundException) {
                Maybe<Object> nonDefaultUrl = mgmt.getConfig().getConfigRaw(BrooklynServerConfig.BROOKLYN_CATALOG_URL, true);
                if (nonDefaultUrl.isPresentAndNonNull() && !"".equals(nonDefaultUrl.get())) {
                    log.warn("Could not find catalog XML specified at {}; using default (local classpath) catalog. Error was: {}", nonDefaultUrl, e);
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("No default catalog file available at {}; trying again using local classpath to populate catalog. Error was: {}", catalogUrl, e);
                    }
                }
            } else {
                log.warn("Error importing catalog XML at " + catalogUrl + "; using default (local classpath) catalog. Error was: " + e, e);
            }
        }
        if (dto == null) {
            // retry, either an error, or was blank
            dto = CatalogDto.newDefaultLocalScanningDto(CatalogClasspathDo.CatalogScanningModes.ANNOTATIONS);
            if (log.isDebugEnabled()) {
                log.debug("Loaded default (local classpath) catalog: " + catalog);
            }
        }

        reset(dto);
    }

    @Deprecated
    public CatalogItem<?,?> getCatalogItemForType(String typeName) {
        final CatalogItem<?,?> resultI;
        final BrooklynCatalog catalog = mgmt.getCatalog();
        if (CatalogUtils.looksLikeVersionedId(typeName)) {
            //All catalog identifiers of the form xxxx:yyyy are composed of symbolicName+version.
            //No javaType is allowed as part of the identifier.
            resultI = CatalogUtils.getCatalogItemOptionalVersion(mgmt, typeName);
        } else {
            //Usually for catalog items with javaType (that is items from catalog.xml)
            //the symbolicName and javaType match because symbolicName (was ID)
            //is not specified explicitly. But could be the case that there is an item
            //whose symbolicName is explicitly set to be different from the javaType.
            //Note that in the XML the attribute is called registeredTypeName.
            Iterable<CatalogItem<Object,Object>> resultL = catalog.getCatalogItems(CatalogPredicates.javaType(Predicates.equalTo(typeName)));
            if (!Iterables.isEmpty(resultL)) {
                //Push newer versions in front of the list (not that there should
                //be more than one considering the items are coming from catalog.xml).
                resultI = sortVersionsDesc(resultL).iterator().next();
                if (log.isDebugEnabled() && Iterables.size(resultL)>1) {
                    log.debug("Found "+Iterables.size(resultL)+" matches in catalog for type "+typeName+"; returning the result with preferred version, "+resultI);
                }
            } else {
                //As a last resort try searching for items with the same symbolicName supposedly
                //different from the javaType.
                resultI = catalog.getCatalogItem(typeName, BrooklynCatalog.DEFAULT_VERSION);
                if (resultI != null) {
                    if (resultI.getJavaType() == null) {
                        throw new NoSuchElementException("Unable to find catalog item for type "+typeName +
                                ". There is an existing catalog item with ID " + resultI.getId() +
                                " but it doesn't define a class type.");
                    }
                }
            }
        }
        return resultI;
    }

}
