package brooklyn.entity.webapp.jboss

import java.util.List
import java.util.Map

import brooklyn.entity.basic.Attributes
import brooklyn.entity.webapp.JavaWebApp
import brooklyn.location.basic.SshMachineLocation
import brooklyn.util.SshBasedJavaWebAppSetup

class JBoss7SshSetup extends SshBasedJavaWebAppSetup {

    public static final String DEFAULT_INSTALL_DIR = "$DEFAULT_INSTALL_BASEDIR/jboss"
    public static final String DEFAULT_VERSION = "7.0.0.Final"
    public static final int DEFAULT_FIRST_HTTP_PORT  = 8080
    public static final int DEFAULT_FIRST_MANAGEMENT_PORT  = 9990
    public static final String SERVER_TYPE = "standalone"
    public static final String DEPLOY_SUBDIR = "standalone/deployments"
    
    private final String brooklynConfig = "standalone-brooklyn.xml"
    
    private int managementPort
    
    public JBoss7SshSetup(JBoss7Server entity, SshMachineLocation machine) {
        super(entity, machine)
    }
    
    public static JBoss7SshSetup newInstance(JBoss7Server entity, SshMachineLocation machine) {
        
        // FIXME Don't call entity.getConfig(JBoss7Server.PROPERTY_FILES) until absolutely necessary.
        // Calling it here blocks until the val is available, which prevents us from downoading and installing
        // as7 asap...
        
        // Suggestions
        Integer suggestedHttpPort = entity.getConfig(JBoss7Server.HTTP_PORT.configKey)
        Integer suggestedManagementPort = entity.getConfig(JBoss7Server.MANAGEMENT_PORT.configKey)
        String suggestedInstallDir = entity.getConfig(JBoss7Server.SUGGESTED_INSTALL_DIR)
        String suggestedRunDir = entity.getConfig(JBoss7Server.SUGGESTED_RUN_DIR)
        Integer suggestedJmxPort = entity.getConfig(JBoss7Server.JMX_PORT.configKey)
        Map<String,Map<String,String>> propFilesToGenerate = entity.getConfig(JBoss7Server.PROPERTY_FILES) ?: [:]
        
        // Defaults if suggestions not given
        String installDir = suggestedInstallDir ?: "$DEFAULT_INSTALL_DIR/${DEFAULT_VERSION}/jboss-${DEFAULT_VERSION}"
        String runDir = suggestedRunDir ?: "$BROOKLYN_HOME_DIR/${entity.application.id}/jboss7-${entity.id}"
        String deployDir = "$runDir/$DEPLOY_SUBDIR"
        String logFileLocation = "$runDir/standalone/log/server.log"

        int httpPort = machine.obtainPort(toDesiredPortRange(suggestedHttpPort, DEFAULT_FIRST_HTTP_PORT))
        int managementPort = machine.obtainPort(toDesiredPortRange(suggestedManagementPort, DEFAULT_FIRST_MANAGEMENT_PORT))
        int jmxPort = machine.obtainPort(toDesiredPortRange(suggestedJmxPort))
        
        // Setup instance
        JBoss7SshSetup result = new JBoss7SshSetup(entity, machine)
        result.setVersion DEFAULT_VERSION
        result.setHttpPort httpPort
        result.setManagementPort managementPort
        result.setInstallDir installDir
        result.setDeployDir deployDir
        result.setRunDir runDir
        result.setEnvironmentPropertyFiles propFilesToGenerate
        result.setJmxEnabled false
        result.setLogFileLocation logFileLocation

        return result
    }
    
    public void setManagementPort(int val) {
        this.managementPort = val
    }
    
    @Override
    protected void setCustomAttributes() {
        entity.setAttribute(JBoss7Server.MANAGEMENT_PORT, managementPort)
    }
    
    @Override
    public List<String> getInstallScript() {
        String url = "http://download.jboss.org/jbossas/7.0/jboss-as-${version}/jboss-as-${version}.tar.gz"
        String saveAs  = "jboss-as-distribution-${version}"
        makeInstallScript([
            "curl -L \"${url}\" -o ${saveAs}",
            "tar xzfv ${saveAs}",
            "mv jboss-as-$version/* $installDir",
            "rm -r jboss-as-$version"
        ])
    }

    public List<String> getRunScript() {
        // script must be backgrounded otherwise it will never return.
        List<String> script = [
            "$installDir/bin/${SERVER_TYPE}.sh --server-config $brooklynConfig -Djboss.server.base.dir=$runDir/$SERVER_TYPE " + 
                "-Djboss.server.base.url=file://$runDir/$SERVER_TYPE " +
                "-Djava.net.preferIPv4Stack=true -Djava.net.preferIPv6Addresses=false" +
                " >> $runDir/console 2>&1 </dev/null &",
        ]
        return script
    }
    
    @Override
    public Map<String, String> getRunEnvironment() {
        return super.getRunEnvironment() + 
                ["JAVA_OPTS" : toJavaDefinesString(getJvmStartupProperties())+" -Xms200m -Xmx800m -XX:MaxPermSize=400m"]
    }
    
    /** @see SshBasedJavaAppSetup#getCheckRunningScript() */
    public List<String> getCheckRunningScript() {
        return [
            "ps aux | grep '${entity.id}' | grep -v grep | grep -v ${SERVER_TYPE}.sh "
        ]
    }

    // TODO: Too much sed! The last one is especially nasty.
    @Override
    public List<String> getConfigScript() {
        List<String> script = [
            "mkdir -p ${runDir}",
            "cd ${runDir}",
            "cp -r ${installDir}/${SERVER_TYPE} ${runDir}",
            "cd ${runDir}/${SERVER_TYPE}/configuration/",
            "cp standalone.xml $brooklynConfig",
            "sed -i.bk 's/8080/${httpPort}/' $brooklynConfig",
            "sed -i.bk 's/9990/${managementPort}/' $brooklynConfig",
            "sed -i.bk 's/1090/${jmxPort}/' $brooklynConfig",
            "sed -i.bk 's/inet-address value=\"127.0.0.1\"/any-address/' $brooklynConfig",
            "sed -i.bk 's/\\(path=\"deployments\"\\)/\\1 deployment-timeout=\"600\"/' $brooklynConfig"
        ]
        return script
    }

    @Override
    public List<String> getShutdownScript() {
        return ["ps aux | grep '${entity.id}' | grep -v grep | awk '{ print \$2 }' | xargs kill -9"]
    }
    
    @Override
    protected void postShutdown() {
        machine.releasePort(jmxPort);
        machine.releasePort(httpPort);
        machine.releasePort(managementPort);
    }
    
    
}
