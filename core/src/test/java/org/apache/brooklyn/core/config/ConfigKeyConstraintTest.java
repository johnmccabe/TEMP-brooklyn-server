/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.brooklyn.core.config;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.fail;

import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.entity.ImplementedBy;
import org.apache.brooklyn.api.policy.Policy;
import org.apache.brooklyn.api.policy.PolicySpec;
import org.apache.brooklyn.api.sensor.EnricherSpec;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.enricher.AbstractEnricher;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.policy.AbstractPolicy;
import org.apache.brooklyn.core.test.BrooklynAppUnitTestSupport;
import org.apache.brooklyn.core.test.entity.TestEntity;
import org.apache.brooklyn.core.test.entity.TestEntityImpl;
import org.apache.brooklyn.core.test.policy.TestPolicy;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.net.Networking;
import org.testng.annotations.Test;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Range;

public class ConfigKeyConstraintTest extends BrooklynAppUnitTestSupport {

    @ImplementedBy(EntityWithNonNullConstraintImpl.class)
    public static interface EntityWithNonNullConstraint extends TestEntity {
        ConfigKey<Object> NON_NULL_CONFIG = ConfigKeys.builder(Object.class)
                .name("test.conf.non-null.without-default")
                .description("Configuration key that must not be null")
                .constraint(Predicates.notNull())
                .build();
    }

    @ImplementedBy(EntityWithNonNullConstraintWithNonNullDefaultImpl.class)
    public static interface EntityWithNonNullConstraintWithNonNullDefault extends TestEntity {
        ConfigKey<Object> NON_NULL_WITH_DEFAULT = ConfigKeys.builder(Object.class)
                .name("test.conf.non-null.with-default")
                .description("Configuration key that must not be null")
                .defaultValue(new Object())
                .constraint(Predicates.notNull())
                .build();
    }

    @ImplementedBy(EntityRequiringConfigKeyInRangeImpl.class)
    public static interface EntityRequiringConfigKeyInRange extends TestEntity {
        ConfigKey<Integer> RANGE = ConfigKeys.builder(Integer.class)
                .name("test.conf.range")
                .description("Configuration key that must not be between zero and nine")
                .defaultValue(0)
                .constraint(Range.closed(0, 9))
                .build();
    }

    @ImplementedBy(EntityProvidingDefaultValueForConfigKeyInRangeImpl.class)
    public static interface EntityProvidingDefaultValueForConfigKeyInRange extends EntityRequiringConfigKeyInRange {
        ConfigKey<Integer> REVISED_RANGE = ConfigKeys.newConfigKeyWithDefault(RANGE, -1);
    }

    public static class EntityWithNonNullConstraintImpl extends TestEntityImpl implements EntityWithNonNullConstraint {
    }

    public static class EntityWithNonNullConstraintWithNonNullDefaultImpl extends TestEntityImpl implements EntityWithNonNullConstraintWithNonNullDefault {
    }

    public static class EntityRequiringConfigKeyInRangeImpl extends TestEntityImpl implements EntityRequiringConfigKeyInRange {
    }

    public static class EntityProvidingDefaultValueForConfigKeyInRangeImpl extends TestEntityImpl implements EntityProvidingDefaultValueForConfigKeyInRange {
    }

    public static class PolicyWithConfigConstraint extends AbstractPolicy {
        public static final ConfigKey<Object> NON_NULL_CONFIG = ConfigKeys.builder(Object.class)
                .name("test.policy.non-null")
                .description("Configuration key that must not be null")
                .constraint(Predicates.notNull())
                .build();
    }

    public static class EnricherWithConfigConstraint extends AbstractEnricher {
        public static final ConfigKey<String> PATTERN = ConfigKeys.builder(String.class)
                .name("test.enricher.regex")
                .description("Must match a valid IPv4 address")
                .constraint(Predicates.containsPattern(Networking.VALID_IP_ADDRESS_REGEX))
                .build();
    }

    @Test
    public void testExceptionWhenEntityHasNullConfig() {
        try {
            app.createAndManageChild(EntitySpec.create(EntityWithNonNullConstraint.class));
            fail("Expected exception when managing entity with missing config");
        } catch (Exception e) {
            Throwable t = Exceptions.getFirstThrowableOfType(e, ConstraintViolationException.class);
            assertNotNull(t);
        }
    }

    @Test
    public void testNoExceptionWhenEntityHasValueForRequiredConfig() {
        app.createAndManageChild(EntitySpec.create(EntityWithNonNullConstraint.class)
                .configure(EntityWithNonNullConstraint.NON_NULL_CONFIG, new Object()));
    }

    @Test
    public void testNoExceptionWhenDefaultValueIsValid() {
        app.createAndManageChild(EntitySpec.create(EntityRequiringConfigKeyInRange.class));
    }

    @Test
    public void testExceptionWhenSubclassSetsInvalidDefaultValue() {
        try {
            app.createAndManageChild(EntitySpec.create(EntityProvidingDefaultValueForConfigKeyInRange.class));
            fail("Expected exception when managing entity setting invalid default value");
        } catch (Exception e) {
            Throwable t = Exceptions.getFirstThrowableOfType(e, ConstraintViolationException.class);
            assertNotNull(t);
        }
    }

    @Test
    public void testExceptionIsThrownWhenUserNullsConfigWithNonNullDefault() {
        try {
            app.createAndManageChild(EntitySpec.create(EntityWithNonNullConstraintWithNonNullDefault.class)
                    .configure(EntityWithNonNullConstraintWithNonNullDefault.NON_NULL_WITH_DEFAULT, (Object) null));
            fail("Expected exception when config key set to null");
        } catch (Exception e) {
            Throwable t = Exceptions.getFirstThrowableOfType(e, ConstraintViolationException.class);
            assertNotNull(t);
        }
    }

    @Test
    public void testExceptionWhenValueSetByName() {
        try {
            app.createAndManageChild(EntitySpec.create(EntityRequiringConfigKeyInRange.class)
                    .configure(ImmutableMap.of("test.conf.range", -1)));
            fail("Expected exception when managing entity with invalid config");
        } catch (Exception e) {
            Throwable t = Exceptions.getFirstThrowableOfType(e, ConstraintViolationException.class);
            assertNotNull(t);
        }
    }

    @Test
    public void testExceptionWhenAppGrandchildHasInvalidConfig() {
        app.start(ImmutableList.of(app.newSimulatedLocation()));
        TestEntity testEntity = app.addChild(EntitySpec.create(TestEntity.class));
        testEntity.addChild(EntitySpec.create(EntityRequiringConfigKeyInRange.class)
                .configure(EntityRequiringConfigKeyInRange.RANGE, -1));
        try {
            Entities.manage(testEntity);
            fail("Expected exception when managing child with invalid config");
        } catch (Exception e) {
            Throwable t = Exceptions.getFirstThrowableOfType(e, ConstraintViolationException.class);
            assertNotNull(t);
        }
    }

    // Test fails because config keys that are not on an object's interfaces cannot be checked automatically.
    @Test(enabled = false)
    public void testExceptionWhenPolicyHasNullForeignConfig() {
        Policy p = mgmt.getEntityManager().createPolicy(PolicySpec.create(TestPolicy.class)
                .configure(EntityWithNonNullConstraint.NON_NULL_CONFIG, (Object) null));
        try {
            ConfigConstraints.assertValid(p);
            fail("Expected exception when validating policy with missing config");
        } catch (Exception e) {
            Throwable t = Exceptions.getFirstThrowableOfType(e, ConstraintViolationException.class);
            assertNotNull(t);
        }
    }

    @Test
    public void testExceptionWhenPolicyHasInvalidConfig() {
        try {
            mgmt.getEntityManager().createPolicy(PolicySpec.create(PolicyWithConfigConstraint.class)
                    .configure(PolicyWithConfigConstraint.NON_NULL_CONFIG, (Object) null));
            fail("Expected exception when creating policy with missing config");
        } catch (Exception e) {
            Throwable t = Exceptions.getFirstThrowableOfType(e, ConstraintViolationException.class);
            assertNotNull(t);
        }
    }

    @Test
    public void testExceptionWhenEnricherHasInvalidConfig() {
        try {
            mgmt.getEntityManager().createEnricher(EnricherSpec.create(EnricherWithConfigConstraint.class)
                    .configure(EnricherWithConfigConstraint.PATTERN, "123.123.256.10"));
            fail("Expected exception when creating enricher with invalid config");
        } catch (Exception e) {
            Throwable t = Exceptions.getFirstThrowableOfType(e, ConstraintViolationException.class);
            assertNotNull(t, "Exception was: " + Exceptions.collapseText(e));
        }
    }

    @Test
    public void testDefaultValueDoesNotNeedToObeyConstraint() {
        ConfigKeys.builder(String.class)
                .name("foo")
                .defaultValue("a")
                .constraint(Predicates.equalTo("b"))
                .build();
    }

    @Test
    public void testIsValidWithBadlyBehavedPredicate() {
        ConfigKey<String> key = ConfigKeys.builder(String.class)
                .name("foo")
                .constraint(new Predicate<String>() {
                    @Override
                    public boolean apply(String input) {
                        throw new RuntimeException("It's my day off");
                    }
                })
                .build();
        assertFalse(key.isValueValid("abc"));
    }

}
