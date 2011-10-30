package brooklyn.entity.nosql.gemfire

import brooklyn.entity.Effector
import brooklyn.entity.Entity
import brooklyn.entity.ParameterType
import brooklyn.entity.basic.AbstractService
import brooklyn.entity.basic.BasicParameterType
import brooklyn.entity.basic.EffectorWithExplicitImplementation
import brooklyn.event.adapter.legacy.OldHttpSensorAdapter;
import brooklyn.event.adapter.legacy.ValueProvider;
import brooklyn.event.basic.BasicAttributeSensor
import brooklyn.event.basic.BasicConfigKey
import brooklyn.location.basic.SshMachineLocation
import brooklyn.util.SshBasedAppSetup

import com.google.common.base.Charsets
import com.google.common.base.Splitter

class GemfireServer extends AbstractService {

    public static final BasicConfigKey<File> CONFIG_FILE = [ File, "gemfire.server.configFile", "Gemfire configuration file" ]
    public static final BasicConfigKey<File> JAR_FILE = [ File, "gemfire.server.jarFile", "Gemfire jar file" ]
    public static final BasicConfigKey<Integer> SUGGESTED_HUB_PORT = [ Integer, "gemfire.server.suggestedHubPort", "Gemfire gateway hub port", 11111 ]
    public static final BasicConfigKey<File> LICENSE = [ File, "gemfire.server.license", "Gemfire license file" ]
    public static final BasicConfigKey<Integer> WEB_CONTROLLER_PORT = [ Integer, "gemfire.server.controllerWebPort", "Gemfire controller web port", 8084 ]
    public static final BasicAttributeSensor<Integer> HUB_PORT = [ Integer, "gemfire.server.hubPort", "Gemfire gateway hub port" ]
    public static final BasicAttributeSensor<String> CONTROL_URL = [ String, "gemfire.server.controlUrl", "URL for perfoming management actions" ]
	public static final BasicAttributeSensor<Collection> REGION_LIST = new BasicAttributeSensor<Collection>(Collection.class, "gemfire.server.regions.list", 
		"List of fully-pathed regions on this gemfire server")

    public static final Effector<Void> ADD_GATEWAYS =
        new EffectorWithExplicitImplementation<GemfireServer, Void>("addGateways", Void.TYPE,
            Arrays.<ParameterType<?>>asList(new BasicParameterType<Collection>("gateways", Collection.class, "Gateways to add")),
            "Add gateways to this server, to replicate to/from other clusters") {
        public Void invokeEffector(GemfireServer entity, Map m) {
            entity.addGateways((Collection<GatewayConnectionDetails>) m.get("gateways"))
            return null
        }
    }

	public static final Effector<Void> REMOVE_GATEWAYS =
		new EffectorWithExplicitImplementation<GemfireServer, Void>("removeGateways", Void.TYPE,
			Arrays.<ParameterType<?>>asList(new BasicParameterType<Collection>("gateways", Collection.class, "Gateways to remove")),
			"Remove decomissioned gateways from this server") {
		public Void invokeEffector(GemfireServer entity, Map m) {
			entity.removeGateways((Collection<GatewayConnectionDetails>) m.get("gateways"))
			return null
		}
	}

	/** 
	 * Takes a collection of fully-qualified region paths (String), e.g. "/Seam-travel/Hotels/Ritz"
	 */
	public static final Effector<Void> ADD_REGIONS =
		new EffectorWithExplicitImplementation<GemfireServer, Void>("addRegions", Void.TYPE,
			Arrays.<ParameterType<?>>asList(new BasicParameterType<Collection>("regions", Collection.class, "Regions to add")),
            "Add regions to this server- will replicate and stay in sync if the region already exists elsewhere") {
		public Void invokeEffector(GemfireServer entity, Map m) {
			entity.addRegions((Collection<String>) m.get("regions"))
			return null
		}
	}

	/**
	* Takes a collection of fully-qualified region paths (String), e.g. "/Seam-travel/Hotels/Ritz"
	*/
	public static final Effector<Void> REMOVE_REGIONS =
		new EffectorWithExplicitImplementation<GemfireServer, Void>("removeRegions", Void.TYPE,
            Arrays.<ParameterType<?>> asList(new BasicParameterType<Collection>("regions", Collection.class, "Regions to remove")),
            "Destroy regions on this server. They will continue to exist elsewhere.") {
		public Void invokeEffector(GemfireServer entity, Map m) {
			entity.removeRegions((Collection<String>) m.get("regions"))
			return null
		}
	}

    private static final int CONTROL_PORT_VAL = 8084    
    transient OldHttpSensorAdapter httpAdapter

    public GemfireServer(Map properties=[:], Entity owner=null) {
        super(properties, owner)
    }

    @Override
    protected void initSensors() {
        int hubPort = getConfig(SUGGESTED_HUB_PORT)
        setAttribute(HUB_PORT, hubPort)
        setAttribute(CONTROL_URL, "http://${setup.machine.address.hostName}:"+CONTROL_PORT_VAL)
        
        httpAdapter = new OldHttpSensorAdapter(this)
        attributePoller.addSensor(SERVICE_UP, { computeNodeUp() } as ValueProvider)
		attributePoller.addSensor(REGION_LIST, { listRegions() } as ValueProvider)
    }
    
    public SshBasedAppSetup getSshBasedSetup(SshMachineLocation loc) {
        return GemfireSetup.newInstance(this, loc)
    }
    
    private boolean computeNodeUp() {
        String url = getAttribute(CONTROL_URL)
        ValueProvider<Integer> provider = httpAdapter.newStatusValueProvider(url)
        try {
            Integer statusCode = provider.compute()
            return (statusCode >= 200 && statusCode <= 299)
        } catch (IOException ioe) {
            log.debug "Attempt to connect to control URL threw IOEXception: ${ioe.message}"
            return false
        }
    }
	
	private Collection<String> listRegions() {
		String url = getAttribute(CONTROL_URL)+"/region/list"
		ValueProvider<String> provider = httpAdapter.newStringBodyProvider(url)
		try {
			return Splitter.on(",")
                    .trimResults()
                    .omitEmptyStrings()
                    .split(provider.compute())
                    .iterator().toList()
		} catch (IOException ioe) {
            log.debug "Attempt to list regions threw IOException: ${ioe.message}"
			return new String[0]
		}
	}

    public void addGateways(Collection<GatewayConnectionDetails> gateways) {
		int counter = 0
        gateways.each { GatewayConnectionDetails gateway ->
            String clusterId = gateway.clusterAbbreviatedName
            String endpointId = clusterId+"-"+(++counter)
            int port = gateway.port
            String hostname = gateway.host
            String controlUrl = getAttribute(CONTROL_URL)
            String url = "$controlUrl/gateway/add?id=$clusterId&endpointId=$endpointId&port=$port&host=$hostname"
            doHTTPRequest(url)
        }
    }
	
	public void removeGateways(Collection<GatewayConnectionDetails> gateways) {
		gateways.each { GatewayConnectionDetails gateway ->
			String clusterId = gateway.clusterAbbreviatedName
			String controlUrl = getAttribute(CONTROL_URL)
			String url = "$controlUrl/gateway/remove?id=$clusterId"
            doHTTPRequest(url)
		}
	}
	
	public void addRegions(Collection<String> regions) {
		regions.each { String region ->
			String controlUrl = getAttribute(CONTROL_URL)
			String url = "$controlUrl/region/add?path="+URLEncoder.encode(region, Charsets.UTF_8.toString())
            doHTTPRequest(url)
		}
	}
	
	public void removeRegions(Collection<String> regions) {
		regions.each { String region ->
			String controlUrl = getAttribute(CONTROL_URL)
			String url = "$controlUrl/region/remove?path="+URLEncoder.encode(region, Charsets.UTF_8.toString())
            doHTTPRequest(url)
		}
	}

    /**
     * Makes connection to URL in resource.
     * @return responseCode server's response code
     */
    private static int doHTTPRequest(String resource) {
        URL url = new URL(resource)
        HttpURLConnection connection = (HttpURLConnection) url.openConnection()
        connection.connect()
        int responseCode = connection.getResponseCode()
        if (responseCode < 200 || responseCode >= 300) {
            throw new IllegalStateException("Server responded with $responseCode on connection to $resource: " + connection.responseMessage)
        } else {
            return responseCode
        }
    }
}
