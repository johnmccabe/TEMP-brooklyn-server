package brooklyn.location.basic

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import brooklyn.location.Location

/** For use in testing. */
//FIXME create as 'MockLocation' ? 
public class GeneralPurposeLocation extends AbstractLocation {
    private static final long serialVersionUID = -6233729266488652570L;

    GeneralPurposeLocation(Map properties = [:]) {
        super(properties)
    }
}
