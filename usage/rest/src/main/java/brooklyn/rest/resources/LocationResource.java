package brooklyn.rest.resources;

import static com.google.common.collect.Iterables.transform;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.validation.Valid;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import brooklyn.location.Location;
import brooklyn.rest.apidoc.Apidoc;
import brooklyn.rest.domain.LocationSpec;
import brooklyn.rest.domain.LocationSummary;
import brooklyn.rest.util.EntityLocationUtils;
import brooklyn.util.MutableMap;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.wordnik.swagger.core.ApiOperation;
import com.wordnik.swagger.core.ApiParam;

@Path("/v1/locations")
@Apidoc("Locations")
@Produces(MediaType.APPLICATION_JSON)
public class LocationResource extends AbstractBrooklynRestResource {

  @GET
  @ApiOperation(value = "Fetch the list of locations",
      responseClass = "brooklyn.rest.domain.LocationSummary",
      multiValueResponse = true)
  public List<LocationSummary> list() {
    return Lists.newArrayList(transform(brooklyn().getLocationStore().entries(),
        new Function<Map.Entry<Integer, LocationSpec>, LocationSummary>() {
          @Override
          public LocationSummary apply(Map.Entry<Integer, LocationSpec> entry) {
            return new LocationSummary(entry.getKey().toString(), entry.getValue());
          }
        }));
  }

  // this is here to support the web GUI's circles
  @GET
  @Path("/usage/LocatedLocations")
  @ApiOperation(value = "Return a summary of all usage", notes="interim API, expected to change")
  public Map<String,Map<String,Object>> get() {
      Map<String,Map<String,Object>> result = new LinkedHashMap<String,Map<String,Object>>();
      Map<Location, Integer> counts = new EntityLocationUtils(mgmt()).countLeafEntitiesByLocatedLocations();
      for (Map.Entry<Location,Integer> count: counts.entrySet()) {
          Location l = count.getKey();
          Map<String,Object> m = MutableMap.<String,Object>of(
                  "name", l.getName(),
                  "leafEntityCount", count.getValue(),
                  "latitude", l.getLocationProperty("latitude"),
                  "longitude", l.getLocationProperty("longitude")
              );
          result.put(l.getId(), m);
      }
      return result;
  }

  @GET
  @Path("/{location}")
  @ApiOperation(value = "Fetch details about a location",
      responseClass = "brooklyn.rest.domain.LocationSummary",
      multiValueResponse = true)
  public LocationSummary get(
      @ApiParam(value = "Location id to fetch", required = true)
      @PathParam("location") Integer locationId) {
    return new LocationSummary(locationId.toString(), brooklyn().getLocationStore().get(locationId));
  }

  @POST
  @ApiOperation(value = "Create a new location", responseClass = "String")
  public Response create(
      @ApiParam(name = "locationSpec", value = "Location specification object", required = true)
      @Valid LocationSpec locationSpec) {
    int id = brooklyn().getLocationStore().put(locationSpec);
    return Response.created(URI.create("" + id)).build();
  }

  @DELETE
  @Path("/{location}")
  @ApiOperation(value = "Delete a location object by id")
  public void delete(
      @ApiParam(value = "Location id to delete", required = true)
      @PathParam("location") Integer locationId) {
      brooklyn().getLocationStore().remove(locationId);
  }

}
