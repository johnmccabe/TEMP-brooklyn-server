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
package brooklyn.entity.basic;

import org.apache.brooklyn.api.entity.proxying.ImplementedBy;
import org.apache.brooklyn.core.util.flags.SetFromFlag;

import brooklyn.config.ConfigKey;
import brooklyn.event.basic.BasicConfigKey;

@ImplementedBy(BasicGroupImpl.class)
public interface BasicGroup extends AbstractGroup {

    @SetFromFlag("childrenAsMembers")
    /** @deprecated since 0.7.0 use {@link Group#addMemberChild} */
    @Deprecated
    ConfigKey<Boolean> CHILDREN_AS_MEMBERS = new BasicConfigKey<Boolean>(
            Boolean.class, "brooklyn.BasicGroup.childrenAsMembers", 
            "Whether children are automatically added as group members", false);

}
