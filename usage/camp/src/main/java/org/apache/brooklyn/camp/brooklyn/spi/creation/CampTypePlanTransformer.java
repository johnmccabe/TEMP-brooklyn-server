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
package org.apache.brooklyn.camp.brooklyn.spi.creation;

import java.util.List;
import java.util.Map;

import org.apache.brooklyn.api.internal.AbstractBrooklynObjectSpec;
import org.apache.brooklyn.api.typereg.RegisteredType;
import org.apache.brooklyn.api.typereg.RegisteredType.TypeImplementationPlan;
import org.apache.brooklyn.api.typereg.RegisteredTypeConstraint;
import org.apache.brooklyn.core.typereg.AbstractCustomImplementationPlan;
import org.apache.brooklyn.core.typereg.AbstractTypePlanTransformer;
import org.apache.brooklyn.core.typereg.BasicTypeImplementationPlan;
import org.apache.brooklyn.core.typereg.RegisteredTypes;
import org.apache.brooklyn.util.guava.Maybe;

import com.google.common.collect.ImmutableList;

public class CampTypePlanTransformer extends AbstractTypePlanTransformer {

    private static final List<String> FORMATS = ImmutableList.of("brooklyn-camp", "camp", "brooklyn");
    
    public CampTypePlanTransformer() {
        super(FORMATS.get(0), "OASIS CAMP / Brooklyn", "The Apache Brooklyn implementation of the OASIS CAMP blueprint plan format and extensions");
    }

    @Override
    protected double scoreForNullFormat(Object planData, RegisteredType type, RegisteredTypeConstraint context) {
        Maybe<Map<Object, Object>> plan = RegisteredTypes.getAsYamlMap(planData);
        if (plan.isAbsent()) return 0;
        if (plan.get().containsKey("services")) return 0.8;
        return 0;
    }

    @Override
    protected double scoreForNonmatchingNonnullFormat(String planFormat, Object planData, RegisteredType type, RegisteredTypeConstraint context) {
        if (FORMATS.contains(planFormat.toLowerCase())) return 0.9;
        return 0;
    }

    @Override
    protected AbstractBrooklynObjectSpec<?, ?> createSpec(RegisteredType type, RegisteredTypeConstraint context) throws Exception {
        // TODO cache
        return new CampResolver(mgmt, type, context).createSpec();
    }

    @Override
    protected Object createBean(RegisteredType type, RegisteredTypeConstraint context) throws Exception {
        // beans not supported by this?
        return null;
    }

    @Override
    public double scoreForTypeDefinition(String formatCode, Object catalogData) {
        // TODO catalog parsing
        return 0;
    }

    @Override
    public List<RegisteredType> createFromTypeDefinition(String formatCode, Object catalogData) {
        // TODO catalog parsing
        return null;
    }

    public static class CampTypeImplementationPlan extends AbstractCustomImplementationPlan<String> {
        public CampTypeImplementationPlan(TypeImplementationPlan otherPlan) {
            super(FORMATS.get(0), String.class, otherPlan);
        }
        public CampTypeImplementationPlan(String planData) {
            this(new BasicTypeImplementationPlan(FORMATS.get(0), planData));
        }
    }
}
