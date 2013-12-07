package brooklyn.util.task.ssh;

import java.util.Map;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.config.ConfigKey;
import brooklyn.config.ConfigUtils;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.BrooklynTasks;
import brooklyn.entity.basic.ConfigKeys;
import brooklyn.location.Location;
import brooklyn.location.basic.AbstractLocation;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.management.ManagementContext;
import brooklyn.management.Task;
import brooklyn.util.config.ConfigBag;
import brooklyn.util.internal.ssh.SshTool;
import brooklyn.util.ssh.BashCommands;
import brooklyn.util.stream.Streams;
import brooklyn.util.task.Tasks;
import brooklyn.util.task.ssh.internal.PlainSshExecTaskFactory;
import brooklyn.util.task.system.ProcessTaskFactory;
import brooklyn.util.task.system.ProcessTaskWrapper;
import brooklyn.util.text.Identifiers;
import brooklyn.util.text.Strings;

import com.google.common.annotations.Beta;
import com.google.common.base.Function;
import com.google.common.collect.Maps;

/**
 * Conveniences for generating {@link Task} instances to perform SSH activities on an {@link SshMachineLocation}.
 * <p>
 * To infer the {@link SshMachineLocation} and take properties from entities and global management context the
 * {@link SshEffectorTasks} should be preferred over this class.
 *  
 * @see SshEffectorTasks
 * @since 0.6.0
 */
@Beta
public class SshTasks {

    private static final Logger log = LoggerFactory.getLogger(SshTasks.class);
        
    public static ProcessTaskFactory<Integer> newSshExecTaskFactory(SshMachineLocation machine, String ...commands) {
        return newSshExecTaskFactory(machine, true, commands);
    }
    
    public static ProcessTaskFactory<Integer> newSshExecTaskFactory(SshMachineLocation machine, final boolean useMachineConfig, String ...commands) {
        return new PlainSshExecTaskFactory<Integer>(machine, commands) {
            {
                if (useMachineConfig)
                    config.putIfAbsent(getSshFlags(machine));
            }
        };
    }

    public static SshPutTaskFactory newSshPutTaskFactory(SshMachineLocation machine, String remoteFile) {
        return newSshPutTaskFactory(machine, true, remoteFile);
    }
    
    public static SshPutTaskFactory newSshPutTaskFactory(SshMachineLocation machine, final boolean useMachineConfig, String remoteFile) {
        return new SshPutTaskFactory(machine, remoteFile) {
            {
                if (useMachineConfig)
                    config.putIfAbsent(getSshFlags(machine));
            }
        };
    }

    public static SshFetchTaskFactory newSshFetchTaskFactory(SshMachineLocation machine, String remoteFile) {
        return newSshFetchTaskFactory(machine, true, remoteFile);
    }
    
    public static SshFetchTaskFactory newSshFetchTaskFactory(SshMachineLocation machine, final boolean useMachineConfig, String remoteFile) {
        return new SshFetchTaskFactory(machine, remoteFile) {
            {
                if (useMachineConfig)
                    config.putIfAbsent(getSshFlags(machine));
            }
        };
    }

    private static Map<String, Object> getSshFlags(Location location) {
        ConfigBag allConfig = ConfigBag.newInstance();
        
        if (location instanceof AbstractLocation) {
            ManagementContext mgmt = ((AbstractLocation)location).getManagementContext();
            if (mgmt!=null)
                allConfig.putAll(mgmt.getConfig().getAllConfig());
        }
        
        allConfig.putAll(location.getAllConfig(true));
        
        Map<String, Object> result = Maps.newLinkedHashMap();
        for (String keyS : allConfig.getAllConfigRaw().keySet()) {
            ConfigKey<?> key = ConfigKeys.newConfigKey(Object.class, keyS);
            if (key.getName().startsWith(SshTool.BROOKLYN_CONFIG_KEY_PREFIX)) {
                result.put(ConfigUtils.unprefixedKey(SshTool.BROOKLYN_CONFIG_KEY_PREFIX, key).getName(), allConfig.get(key));
            }
        }
        return result;
    }

    /** creates a task which returns modifies sudoers to ensure non-tty access is permitted;
     * also gives nice warnings if sudo is not permitted */
    public static ProcessTaskFactory<Boolean> dontRequireTtyForSudo(SshMachineLocation machine, final boolean requireSuccess) {
        final String id = Identifiers.makeRandomId(6);
        return newSshExecTaskFactory(machine, 
                BashCommands.dontRequireTtyForSudo(),
                // strange quotes are to ensure we don't match against echoed stdin
                BashCommands.sudo("echo \"sudo\"-is-working-"+id))
            .summary("setting up sudo")
            .configure(SshTool.PROP_ALLOCATE_PTY, true)
            .allowingNonZeroExitCode()
            .returning(new Function<ProcessTaskWrapper<?>,Boolean>() { public Boolean apply(ProcessTaskWrapper<?> task) {
                if (task.getExitCode()==0 && task.getStdout().contains("sudo-is-working-"+id)) return true;
                Entity entity = BrooklynTasks.getTargetOrContextEntity(Tasks.current());
                log.warn("Error setting up sudo for "+task.getMachine().getUser()+"@"+task.getMachine().getAddress().getHostName()+" "+
                        " (exit code "+task.getExitCode()+(entity!=null ? ", entity "+entity : "")+")");
                Streams.logStreamTail(log, "STDERR of sudo setup problem", Streams.byteArrayOfString(task.getStderr()), 1024);
                if (requireSuccess) {
                    throw new IllegalStateException("Passwordless sudo is required for "+task.getMachine().getUser()+"@"+task.getMachine().getAddress().getHostName()+
                            (entity!=null ? " ("+entity+")" : ""));
                }
                return true; 
            } });
    }

    /** Function for use in {@link ProcessTaskFactory#returning(Function)} which logs all information, optionally requires zero exit code, 
     * and then returns stdout */
    public static Function<ProcessTaskWrapper<?>, String> returningStdoutLoggingInfo(final Logger logger, final boolean requireZero) {
        return new Function<ProcessTaskWrapper<?>, String>() {
          public String apply(@Nullable ProcessTaskWrapper<?> input) {
            if (logger!=null) logger.info(input+" COMMANDS:\n"+Strings.join(input.getCommands(),"\n"));
            if (logger!=null) logger.info(input+" STDOUT:\n"+input.getStdout());
            if (logger!=null) logger.info(input+" STDERR:\n"+input.getStderr());
            if (requireZero && input.getExitCode()!=0) 
                throw new IllegalStateException("non-zero exit code in "+input.getSummary()+": see log for more details!");
            return input.getStdout();
          }
        };
    }

}
