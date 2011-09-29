package brooklyn.util

import java.util.Map

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import brooklyn.entity.basic.Attributes
import brooklyn.entity.basic.EntityLocal
import brooklyn.entity.basic.JavaApp
import brooklyn.location.basic.SshMachineLocation
import brooklyn.util.internal.LanguageUtils

/**
 * Java application installation, configuration and startup using ssh.
 *
 * This class should be extended for use by entities that are implemented by a Java
 * application.
 *
 * TODO complete documentation
 */
public abstract class SshBasedJavaAppSetup extends SshBasedAppSetup {
    static final Logger log = LoggerFactory.getLogger(SshBasedJavaAppSetup.class)

    protected boolean jmxEnabled = true
    protected int jmxPort
    protected int rmiPort
    protected Map<String,Map<String,String>> environmentPropertyFiles = [:]
    protected Map<String,Map<String,String>> namedPropertyFiles = [:]
    protected Map<String,String> envVariablesToSet = [:]

    public SshBasedJavaAppSetup(EntityLocal entity, SshMachineLocation machine) {
        super(entity, machine)
    }

    public void setJmxEnabled(boolean val) {
        jmxEnabled = val
    }

    public void setJmxPort(int val) {
        jmxPort = val
    }

    public void setRmiPort(int val) {
        rmiPort = val
    }

    public void setEnvironmentPropertyFiles(Map<String,Map<String,String>> propertyFiles) {
        this.environmentPropertyFiles << propertyFiles
    }

    public void setNamedPropertyFiles(Map<String,Map<String,String>> propertyFiles) {
        this.namedPropertyFiles << propertyFiles
    }

    @Override
    protected void setEntityAttributes() {
        super.setEntityAttributes()
        entity.setAttribute(Attributes.JMX_PORT, jmxPort)
        entity.setAttribute(Attributes.RMI_PORT, rmiPort)
        entity.setAttribute(Attributes.JMX_USER)
        entity.setAttribute(Attributes.JMX_PASSWORD)
        entity.setAttribute(Attributes.JMX_CONTEXT)
    }

    @Override
    public void config() {
        super.config()
        envVariablesToSet = generateAndCopyPropertyFiles(environmentPropertyFiles)
    }

    private Map<String,String> generateAndCopyPropertyFiles(Map<String,Map<String,String>> propertyFiles) {
        Map<String,String> result = [:]
        
        // FIXME Store securely; may contain credentials!
        for (Map.Entry<String,Map<String,String>> entry in propertyFiles) {
            String name = entry.key
            Map<String,String> contents = entry.value

            Properties props = new Properties()
            for (Map.Entry<String,String> prop in contents) {
                props.setProperty(prop.key, prop.value)
            }
            
            File local = File.createTempFile(entity.id, ".properties")
            String file = LanguageUtils.newUid() + ".properties"
            local.deleteOnExit() // just in case
            FileOutputStream fos = new FileOutputStream(local)
            try {
                props.store(fos, "Auto-generated by Brooklyn; " + file)
                fos.flush()
                String remote = "${runDir}/${file}"
                machine.copyTo local, remote
                
                result.put(name, remote)
            } finally {
                fos.close()
                local.delete()
            }
        }
        
        return result
    }
    
    /**
     * Convenience method to generate Java environment options string.
     *
     * Converts the properties {@link Map} entries with a value to {@code -Dkey=value}
     * and entries where the value is null to {@code -Dkey}.
     */
    public static String toJavaDefinesString(Map properties) {
        StringBuffer options = []
        properties.each { key, value ->
	            options.append("-D").append(key)
	            if (value != null && value != "") {
                    // Quote the value if it's a string containing a space.
                    if (value instanceof String && value.indexOf(" ") >= 0)
                        options.append("=\'").append(value).append("\'")
                    else
                        options.append("=").append(value)
                }
	            options.append(" ")
	        }
        return options.toString().trim()
    }

    @Override
    public Map<String, String> getRunEnvironment() {
        return envVariablesToSet + [
            "JAVA_OPTS" : toJavaDefinesString(getJvmStartupProperties()),
        ]
    }

    /**
     * Returns the complete set of Java configuration options required by
     * the application.
     *
     * These should be formatted and passed to the JVM as the contents of
     * the {@code JAVA_OPTS} environment variable. The default set contains
     * only the options required to enable JMX. To add application specific
     * options, override the {@link #getJavaConfigOptions()} method.
     *
     * @see #toJavaDefinesString(Map)
     */
    protected Map getJvmStartupProperties() {
        entity.getConfig(JavaApp.JAVA_OPTIONS) + getJavaConfigOptions() + (jmxEnabled ? getJmxConfigOptions() : [:])
    }

    /**
     * Return extra Java configuration options required by the application.
     * 
     * This should be overridden; default is an empty {@link Map}.
     */
    protected Map getJavaConfigOptions() { return [:] }

    /**
     * Return the configuration properties required to enable JMX for a Java application.
     *
     * These should be set as properties in the {@code JAVA_OPTS} environment variable
     * when calling the run script for the application.
     *
     * TODO security!
     */
    protected Map getJmxConfigOptions() {
        [
          "com.sun.management.jmxremote" : "",
          "com.sun.management.jmxremote.port" : jmxPort,
          "com.sun.management.jmxremote.ssl" : false,
          "com.sun.management.jmxremote.authenticate" : false,
          "java.rmi.server.hostname" : machine.address.hostName,
        ]
    }
}
