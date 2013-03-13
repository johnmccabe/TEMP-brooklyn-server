package brooklyn.rest.domain;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import javax.annotation.Nullable;

import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.annotate.JsonProperty;

import brooklyn.entity.Entity;
import brooklyn.management.Task;
import brooklyn.rest.util.JsonUtils;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

public class TaskSummary {

  private final String entityId;
  private final String entityDisplayName;
  
  private final String displayName;
  private final String description;
  private final String id;
  private final Collection<Object> tags;
  private final long rawSubmitTimeUtc;
  private final String submitTimeUtc;
  private final String startTimeUtc;
  private final String endTimeUtc;
  private final String currentStatus;
  private final String detailedStatus;

  
  public TaskSummary(
          @JsonProperty("entityId") String entityId, 
          @JsonProperty("entityDisplayName") String entityDisplayName, 
          @JsonProperty("displayName") String displayName, 
          @JsonProperty("description") String description, 
          @JsonProperty("id") String id, 
          @JsonProperty("tags") Collection<Object> tags,
          @JsonProperty("rawSubmitTimeUtc") long rawSubmitTimeUtc, 
          @JsonProperty("submitTimeUtc") String submitTimeUtc, 
          @JsonProperty("startTimeUtc") String startTimeUtc, 
          @JsonProperty("endTimeUtc") String endTimeUtc, 
          @JsonProperty("currentStatus") String currentStatus, 
          @JsonProperty("detailedStatus") String detailedStatus) {
    this.entityId = entityId;
    this.entityDisplayName = entityDisplayName;
    this.displayName = displayName;
    this.description = description;
    this.id = id;
    this.tags = ImmutableList.<Object>copyOf(tags);
    this.rawSubmitTimeUtc = rawSubmitTimeUtc;
    this.submitTimeUtc = submitTimeUtc;
    this.startTimeUtc = startTimeUtc;
    this.endTimeUtc = endTimeUtc;
    this.currentStatus = currentStatus;
    this.detailedStatus = detailedStatus;
}

  @SuppressWarnings({ "unchecked", "rawtypes" })
public TaskSummary(Task task) {
    Preconditions.checkNotNull(task);
    // 'ported' from groovy web console TaskSummary.groovy , not sure if always works as intended
    Entity entity = (Entity) Iterables.tryFind(task.getTags(), Predicates.instanceOf(Entity.class)).orNull();
    if (entity!=null) {
        this.entityId = entity.getId();
        this.entityDisplayName = entity.getDisplayName();
    } else {
        this.entityId = null;
        this.entityDisplayName = null;
    }

    this.tags = task.getTags();
    this.displayName = task.getDisplayName();
    this.description = task.getDescription();
    this.id = task.getId();
    this.rawSubmitTimeUtc = task.getSubmitTimeUtc();
    this.submitTimeUtc = (task.getSubmitTimeUtc() == -1) ? "" : formatter.get().format(new Date(task.getSubmitTimeUtc()));
    this.startTimeUtc = (task.getStartTimeUtc() == -1) ? "" : formatter.get().format(new Date(task.getStartTimeUtc()));
    this.endTimeUtc = (task.getEndTimeUtc() == -1) ? "" : formatter.get().format(new Date(task.getEndTimeUtc()));
    this.currentStatus = task.getStatusSummary();
    this.detailedStatus = task.getStatusDetail(true);
  }

  public static final TaskSummary fromTask(Task<?> task) { return new TaskSummary(task); }
  
  public static final Function<Task<?>, TaskSummary> FROM_TASK = new Function<Task<?>, TaskSummary>() {
      @Override
      public TaskSummary apply(@Nullable Task<?> input) { return fromTask(input); }
  };
    
  // formatter is not thread-safe; use thread-local storage
  private static final ThreadLocal<DateFormat> formatter = new ThreadLocal<DateFormat>() {
    @Override
    protected DateFormat initialValue() {
      SimpleDateFormat result = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
      result.setTimeZone(TimeZone.getTimeZone("GMT"));
      return result;
    }
  };

  public String getEntityId() {
    return entityId;
  }

  public String getEntityDisplayName() {
    return entityDisplayName;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getDescription() {
    return description;
  }

  public String getId() {
    return id;
  }

  public Collection<Object> getTags() {
    List<Object> result = new ArrayList<Object>();
    for (Object t: tags)
        result.add(JsonUtils.toJsonable(t));
    return result;
  }

  @JsonIgnore
  public Collection<Object> getRawTags() {
      return tags;
    }

  public long getRawSubmitTimeUtc() {
    return rawSubmitTimeUtc;
  }

  public String getSubmitTimeUtc() {
    return submitTimeUtc;
  }

  public String getStartTimeUtc() {
    return startTimeUtc;
  }

  public String getEndTimeUtc() {
    return endTimeUtc;
  }

  public String getCurrentStatus() {
    return currentStatus;
  }

  public String getDetailedStatus() {
    return detailedStatus;
  }

  @Override
  public String toString() {
    return "TaskSummary{" +
        "id='" + id + '\'' +
        ", displayName='" + displayName + '\'' +
        ", currentStatus='" + currentStatus + '\'' +
        ", startTimeUtc='" + startTimeUtc + '\'' +
        ", endTimeUtc='" + endTimeUtc + '\'' +
        '}';
  }
}
