/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package brooklyn.event.basic;

import java.util.ConcurrentModificationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.Entity;
import brooklyn.event.Sensor;
import brooklyn.event.SensorEvent;

import com.google.common.base.Objects;

/**
 * A {@link SensorEvent} containing data from a {@link Sensor} generated by an {@link Entity}.
 */
public class BasicSensorEvent<T> implements SensorEvent<T> {
    
    private static final Logger log = LoggerFactory.getLogger(BasicSensorEvent.class);
    
    private final Sensor<T> sensor;
    private final Entity source;
    private final T value;
    private final long timestamp;
    
    public T getValue() { return value; }

    public Sensor<T> getSensor() { return sensor; }

    public Entity getSource() { return source; }

    public long getTimestamp() { return timestamp; }

    /** arguments should not be null (except in certain limited testing situations) */
    public BasicSensorEvent(Sensor<T> sensor, Entity source, T value) {
        this(sensor, source, value, 0);
    }
    
    public BasicSensorEvent(Sensor<T> sensor, Entity source, T value, long timestamp) {
        this.sensor = sensor;
        this.source = source;
        this.value = value;

        if (timestamp > 0) {
            this.timestamp = timestamp;
        } else {
            this.timestamp = System.currentTimeMillis();
        }
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(sensor, source, value);
    }   

    /**
     * Any SensorEvents are equal if their sensor, source and value are equal.
     * Ignore timestamp for ease of use in unit tests.   
     */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SensorEvent)) return false;
        SensorEvent<?> other = (SensorEvent<?>) o;
        return Objects.equal(sensor, other.getSensor()) && Objects.equal(source, other.getSource()) &&
                Objects.equal(value, other.getValue());
    }
    
    @Override
    public String toString() {
        try {
            return source+"."+sensor+"="+value+" @ "+timestamp;
        } catch (ConcurrentModificationException e) {
            // TODO occasional CME observed on shutdown, wrt map, e.g. in UrlMappingTest
            // transformations should set a copy of the map; see e.g. in ServiceStateLogic.updateMapSensor
            String result = getClass()+":"+source+"."+sensor+"@"+timestamp;
            log.warn("Error creating string for " + result + " (ignoring): " + e);
            if (log.isDebugEnabled())
                log.debug("Trace for error creating string for " + result + " (ignoring): " + e, e);
            return result;
        }
    }
}
