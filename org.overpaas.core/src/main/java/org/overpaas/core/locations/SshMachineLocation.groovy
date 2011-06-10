package org.overpaas.core.locations;

import java.util.Map

import org.overpaas.core.decorators.Location;
import org.overpaas.core.decorators.OverpaasEntity;
import org.overpaas.util.SshJschTool

public class SshMachineLocation implements Location {

	String name
	String user = null
	String host

	@Override
	public String toString() {
		return name?name+"["+host+"]":host;
	}
		
	/** these properties are separate to the entity hierarchy properties,
	 * used by certain types of entities as documented in their setup
	 * (e.g. JMX port) 
	 */
	Map properties=[:]
	public Map getProperties() { properties }

	/** convenience for running a script, returning the result code */
	public int run(Map props=[:], String command) {
		assert host : "host must be specified for $this"
		
		if (!user) user=System.getProperty "user.name"
		def t = new SshJschTool(user:user, host:host);
		t.connect()
		int result = t.execShell props, command
		t.disconnect()
		result
		
//		ExecUtils.execBlocking "ssh", (user?user+"@":"")+host, command
	}
	
	/*
	 * TODO OS-X failure, if no recent command line ssh
ssh_askpass: exec(/usr/libexec/ssh-askpass): No such file or directory
Permission denied, please try again.
ssh_askpass: exec(/usr/libexec/ssh-askpass): No such file or directory
Received disconnect from ::1: 2: Too many authentication failures for alex 
	 */
	
	public static abstract class SshBasedJavaAppSetup {
		
		String overpaasBaseDir = "/tmp/overpaas"
		String installsBaseDir = overpaasBaseDir+"/installs"

		/** convenience to generate string -Dprop1=val1 -Dprop2=val2 for use with java */		
		public static String toJavaDefinesString(Map m) {
			StringBuffer sb = []
			m.each { sb.append("-D"); sb.append(it.key); if (sb.value!='') { sb.append('=\''); sb.append(it.value); sb.append('\' ') } }
			return sb.toString().trim()
		}
		/** convenience to record a value on the location to ensure each instance gets a unique value */
		protected int getNextValue(String field, int initial) {
			def v = entity.properties[field]
			if (!v) {
				println "retrieving "+field+", "+entity.location.properties
				synchronized (entity.location) {
					v = entity.location.properties["next_"+field] ?: initial
					entity.location.properties["next_"+field] = (v+1)
				}
				println "retrieved "+field+", "+entity.location.properties
				entity.properties[field] = v
			}
			v
		}
		public Map getJvmStartupProperties() {
			[:]+getJmxConfigOptions()
		}
		public int getJmxPort() {
			println "setting jmxHost on $entity as "+entity.location.host
			entity.properties.jmxHost = entity.location.host
			getNextValue("jmxPort", 10100)
		}
		public Map getJmxConfigOptions() {
			//TODO security!
			[ 'com.sun.management.jmxremote':'',
			  'com.sun.management.jmxremote.port':getJmxPort(),
			  'com.sun.management.jmxremote.ssl':false,
			  'com.sun.management.jmxremote.authenticate':false
			]
		}
		protected String makeInstallScript(String ...lines) { 
			String result = """\
if [ -f $installDir/../INSTALL_COMPLETION_DATE ] ; then echo software is already installed ; exit ; fi
mkdir -p $installDir && \\
cd $installDir/.. && \\
""";
			lines.each { result += it + "&& \\\n" }
			result += """\
date > INSTALL_COMPLETION_DATE
exit
""" 
		}
	
		public String getInstallScript() { null }
		public abstract String getRunScript();
		
		public void start(SshMachineLocation loc) {
			synchronized (getClass()) {
				String s = getInstallScript()
				if (s) {
					int result = loc.run(out: System.out, s)
					if (result) throw new IllegalStateException("failed to start $entity (exit code $result)")
				}
			}

			def result = loc.run(out: System.out, getRunScript())
			if (result) throw new IllegalStateException("failed to start $entity (exit code $result)")
		}

		OverpaasEntity entity
		String appBaseDir

		public SshBasedJavaAppSetup(OverpaasEntity entity) {
			this.entity = entity
			appBaseDir = overpaasBaseDir + "/" + "app-"+entity.getApplication()?.id
		}			
	}

}
