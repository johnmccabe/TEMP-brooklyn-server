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
package brooklyn.rest.resources;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Timer;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.BrooklynVersion;
import brooklyn.entity.Application;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.EntityLocal;
import brooklyn.entity.basic.StartableApplication;
import brooklyn.management.Task;
import brooklyn.management.entitlement.EntitlementContext;
import brooklyn.management.entitlement.Entitlements;
import brooklyn.management.ha.ManagementPlaneSyncRecord;
import brooklyn.management.internal.ManagementContextInternal;
import brooklyn.rest.api.ServerApi;
import brooklyn.rest.domain.HighAvailabilitySummary;
import brooklyn.rest.domain.VersionSummary;
import brooklyn.rest.transform.HighAvailabilityTransformer;
import brooklyn.util.ResourceUtils;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.time.CountdownTimer;
import brooklyn.util.time.Duration;
import brooklyn.util.time.Time;

public class ServerResource extends AbstractBrooklynRestResource implements ServerApi {

    private static final Logger log = LoggerFactory.getLogger(ServerResource.class);

    private static final String BUILD_SHA_1_PROPERTY = "git-sha-1";
    private static final String BUILD_BRANCH_PROPERTY = "git-branch-name";

    @Override
    public void reloadBrooklynProperties() {
        brooklyn().reloadBrooklynProperties();
    }

    @Override
    public void shutdown(final boolean stopAppsFirst, final long delayMillis, String httpReturnTimeout) {
        log.info("REST call to shutdown server, stopAppsFirst="+stopAppsFirst+", delayMillis="+delayMillis);

        Duration httpReturnTimeoutDuration = Duration.parse(httpReturnTimeout);
        final boolean shouldBlock = (httpReturnTimeoutDuration != null);
        final AtomicBoolean completed = new AtomicBoolean();

        new Thread() {
            public void run() {
                Duration delayBeforeSystemExit = Duration.millis(delayMillis);
                CountdownTimer timer = delayBeforeSystemExit.countdownTimer();

                if (stopAppsFirst) {
                    List<Task<?>> stoppers = new ArrayList<Task<?>>();
                    for (Application app: mgmt().getApplications()) {
                        if (app instanceof StartableApplication)
                            stoppers.add(Entities.invokeEffector((EntityLocal)app, app, StartableApplication.STOP));
                    }
                    for (Task<?> t: stoppers) {
                        t.blockUntilEnded();
                        if (t.isError()) {
                            log.warn("Error stopping application "+t+" during shutdown (ignoring)\n"+t.getStatusDetail(true));
                        }
                    }
                }

                ((ManagementContextInternal)mgmt()).terminate(); 
                timer.waitForExpiryUnchecked();

                synchronized (completed) {
                    completed.set(true);
                    completed.notifyAll();
                }

                if (shouldBlock) {
                    //give the http request a chance to complete gracefully
                    Time.sleep(Duration.FIVE_SECONDS);
                }

                System.exit(0);
            }
        }.start();

        if (shouldBlock) {
            synchronized (completed) {
                if (!completed.get()) {
                    try {
                        completed.wait(httpReturnTimeoutDuration.toMilliseconds());
                    } catch (InterruptedException e) {
                        throw Exceptions.propagate(e);
                    }
                }
            }
        }
    }

    @Override
    public VersionSummary getVersion() {
        InputStream input = ResourceUtils.create().getResourceFromUrl("classpath://build-metadata.properties");
        Properties properties = new Properties();
        String gitSha1 = null, gitBranch = null;
        try {
            properties.load(input);
            gitSha1 = properties.getProperty(BUILD_SHA_1_PROPERTY);
            gitBranch = properties.getProperty(BUILD_BRANCH_PROPERTY);
        } catch (IOException e) {
            log.error("Failed to load build-metadata.properties", e);
        }
        return new VersionSummary(BrooklynVersion.get(), gitSha1, gitBranch);
    }

    @Override
    public String getStatus() {
        return mgmt().getHighAvailabilityManager().getNodeState().toString();
    }

    @Override
    public HighAvailabilitySummary getHighAvailability() {
        ManagementPlaneSyncRecord memento = mgmt().getHighAvailabilityManager().getManagementPlaneSyncState();
        return HighAvailabilityTransformer.highAvailabilitySummary(mgmt().getManagementNodeId(), memento);
    }

    @Override
    public String getUser() {
        EntitlementContext entitlementContext = Entitlements.getEntitlementContext();
        if (entitlementContext!=null && entitlementContext.user()!=null){
            return entitlementContext.user();
        } else {
            return null; //User can be null if no authentication was requested
        }
    }
}
