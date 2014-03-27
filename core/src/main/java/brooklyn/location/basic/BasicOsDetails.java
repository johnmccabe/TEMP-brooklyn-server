package brooklyn.location.basic;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import com.google.common.base.Objects;

import brooklyn.location.OsDetails;

@Immutable
public class BasicOsDetails implements OsDetails {

    final String name, arch, version;
    final boolean is64bit;

    /** Sets is64Bit according to value of arch parameter. */
    public BasicOsDetails(String name, String arch, String version) {
       this(name, arch, version, arch != null && arch.contains("64"));
    }

    public BasicOsDetails(String name, String arch, String version, boolean is64Bit) {
        this.name = name; this.arch = arch; this.version = version; this.is64bit = is64Bit;
    }
    
    @Nullable
    @Override
    public String getName() {
        return name;
    }

    @Nullable
    @Override
    public String getArch() {
        return arch;
    }

    @Nullable
    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public boolean isWindows() {
        //TODO confirm
        return getName()!=null && getName().toLowerCase().contains("microsoft");
    }

    @Override
    public boolean isLinux() {
        //TODO confirm
        return getName()!=null && getName().toLowerCase().contains("linux");
    }

    @Override
    public boolean isMac() {
        return getName()!=null && getName().equals(OsNames.MAC_OS_X);
    }

    @Override
    public boolean is64bit() {
        return is64bit;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(OsDetails.class)
                .omitNullValues()
                .add("name", name)
                .add("version", version)
                .add("arch", arch)
                .toString();
    }

    public static class OsNames {
        public static final String MAC_OS_X = "Mac OS X";
    }
    
    public static class OsArchs {
        public static final String X_86_64 = "x86_64";
//        public static final String X_86 = "x86";
//        // is this standard?  or do we ever need the above?
        public static final String I386 = "i386";
    }

    public static class OsVersions {
        public static final String MAC_10_5 = "10.5";
        public static final String MAC_10_6 = "10.6";
    }
    
    public static class Factory {
        public static OsDetails newLocalhostInstance() {
            return new BasicOsDetails(System.getProperty("os.name"), System.getProperty("os.arch"), System.getProperty("os.version"));
        }
        
        public static final OsDetails ANONYMOUS_LINUX = new BasicOsDetails("linux", OsArchs.I386, "unknown");
        public static final OsDetails ANONYMOUS_LINUX_64 = new BasicOsDetails("linux", OsArchs.X_86_64, "unknown");
    }
    
}
