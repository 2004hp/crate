/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.metadata;

import com.google.common.collect.ImmutableList;
import io.crate.operation.reference.partitioned.PartitionExpression;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PartitionReferenceResolverTest {

    @Test
    public void testClusterExpressionsNotAllowed() throws Exception {
        ReferenceResolver fallbackRefResolver = mock(ReferenceResolver.class);
        ReferenceIdent ident = new ReferenceIdent(new TableIdent("doc", "foo"), "bar");
        when(fallbackRefResolver.getImplementation(ident)).thenReturn(new ReferenceImplementation() {
            @Override
            public ReferenceInfo info() {
                return null;
            }

            @Override
            public ReferenceImplementation getChildImplementation(String name) {
                return null;
            }
        });
        PartitionReferenceResolver referenceResolver = new PartitionReferenceResolver(
                fallbackRefResolver,
                ImmutableList.<PartitionExpression>of()
        );
        try {
            referenceResolver.getImplementation(ident);
            fail();
        } catch (AssertionError e) {
            assertThat(e.getMessage(), is("granularity < PARTITION should have been resolved already"));
        }
    }
}