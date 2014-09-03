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
package brooklyn.entity;

import brooklyn.basic.BrooklynObject;
import brooklyn.entity.rebind.RebindSupport;
import brooklyn.entity.rebind.Rebindable;
import brooklyn.entity.trait.Configurable;
import brooklyn.mementos.FeedMemento;
import brooklyn.policy.EntityAdjunct;

import com.google.common.annotations.Beta;

/** 
 * A sensor feed.
 * These generally poll or subscribe to get sensor values for an entity.
 * They make it easy to poll over http, jmx, etc.
 * 
 * Assumes:
 *   <ul>
 *     <li>There will not be concurrent calls to start and stop.
 *     <li>There will only be one call to start and that will be done immediately after construction,
 *         in the same thread.
 *     <li>Once stopped, the feed will not be re-started.
 *   </ul>
 */
@Beta
public interface Feed extends EntityAdjunct, Rebindable {

    /** 
     * True if everything has been _started_ (or it is starting) but not stopped,
     * even if it is suspended; see also {@link #isActive()}
     */
    boolean isActivated();
    
    /** 
     * @eturn true iff the feed is running
     */
    boolean isActive();
    
    void start();

    /** suspends this feed (stops the poller, or indicates that the feed should start in a state where the poller is stopped) */
    void suspend();

    boolean isSuspended();

    /** resumes this feed if it has been suspended and not stopped */
    void resume();
    
    void stop();

    /**
     * This method will likely move out of this interface, into somewhere internal; users should not call this directly.  
     */
    @Override
    RebindSupport<FeedMemento> getRebindSupport();
}
