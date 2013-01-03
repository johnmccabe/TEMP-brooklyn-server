package brooklyn.rest.domain;

import java.net.URI;
import java.util.Map;

import org.codehaus.jackson.annotate.JsonProperty;

import brooklyn.entity.Entity;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;

public class EntitySummary {

  private final String id;
  private final String name;
  private final String type;
  private final Map<String, URI> links;

  public EntitySummary(
      @JsonProperty("id") String id,
      @JsonProperty("name") String name,
      @JsonProperty("type") String type,
      @JsonProperty("links") Map<String, URI> links
  ) {
    this.type = type;
    this.id = id;
    this.name = name;
    this.links = ImmutableMap.copyOf(links);
  }

  @Deprecated
  public EntitySummary(ApplicationSummary application, Entity entity) {
      this(entity);
      assert entity.getApplicationId().equals(application.getId());
  }
  
  protected EntitySummary(Entity entity) {
    this.type = entity.getClass().getName();
    this.id = entity.getId();
    this.name = entity.getDisplayName();

    String applicationUri = "/v1/applications/" + entity.getApplicationId();
    String entityUri = applicationUri + "/entities/" + entity.getId();
    Builder<String, URI> lb = ImmutableMap.<String, URI>builder()
        .put("self", URI.create(entityUri));
    if (entity.getParent()!=null)
        lb.put("parent", URI.create(applicationUri+"/entities/"+entity.getParent().getId()));
    lb.put("application", URI.create(applicationUri))
        .put("children", URI.create(entityUri + "/entities"))
        .put("config", URI.create(entityUri + "/config"))
        .put("sensors", URI.create(entityUri + "/sensors"))
        .put("effectors", URI.create(entityUri + "/effectors"))
        .put("policies", URI.create(entityUri + "/policies"))
        .put("activities", URI.create(entityUri + "/activities"))
        .put("catalog", URI.create("/v1/catalog/entities/" + type));
    this.links = lb.build();
  }
  
  public static EntitySummary fromEntity(Entity entity) {
      return new EntitySummary(entity);
  }

  public String getType() {
    return type;
  }

  public String getId() {
    return id;
  }
  
  public String getName() {
    return name;
  }
  
  public Map<String, URI> getLinks() {
    return links;
  }

  @Override
  public boolean equals(Object o) {
    return (o instanceof EntitySummary) && id.equals(((EntitySummary)o).getId());
  }

  @Override
  public int hashCode() {
    return id != null ? id.hashCode() : 0;
  }

  @Override
  public String toString() {
    return "EntitySummary{" +
        "id='" + id + '\'' +
        ", links=" + links +
        '}';
  }
}
