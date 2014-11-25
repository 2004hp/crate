/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
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

package io.crate.operation.aggregation.impl;

import com.google.common.collect.ImmutableList;
import io.crate.breaker.RamAccountingContext;
import io.crate.breaker.SizeEstimator;
import io.crate.breaker.SizeEstimatorFactory;
import io.crate.metadata.FunctionIdent;
import io.crate.metadata.FunctionInfo;
import io.crate.operation.Input;
import io.crate.operation.aggregation.AggregationFunction;
import io.crate.operation.aggregation.VariableSizeAggregationState;
import io.crate.types.DataType;
import io.crate.types.DataTypes;
import org.elasticsearch.common.breaker.CircuitBreakingException;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;

import java.io.IOException;

public abstract class MaximumAggregation extends AggregationFunction<MaximumAggregation.MaximumAggState> {

    public static final String NAME = "max";

    private final FunctionInfo info;

    public static void register(AggregationImplModule mod) {
        for (final DataType dataType : DataTypes.PRIMITIVE_TYPES) {
            mod.register(
                    new MaximumAggregation(
                            new FunctionInfo(new FunctionIdent(NAME,
                                    ImmutableList.of(dataType)),
                                    dataType, FunctionInfo.Type.AGGREGATE
                            )
                    ) {
                        @Override
                        public MaximumAggState newState(RamAccountingContext ramAccountingContext) {
                            return new MaximumAggState(ramAccountingContext, SizeEstimatorFactory.create(dataType)) {

                                @Override
                                public void readFrom(StreamInput in) throws IOException {
                                    if (!in.readBoolean()) {
                                        setValue((Comparable) dataType.streamer().readValueFrom(in));
                                    }
                                }

                                @Override
                                public void writeTo(StreamOutput out) throws IOException {
                                    Object value = value();
                                    out.writeBoolean(value == null);
                                    if (value != null) {
                                        dataType.streamer().writeValueTo(out, value);
                                    }
                                }
                            };
                        }
                    }
            );
        }
    }

    MaximumAggregation(FunctionInfo info) {
        this.info = info;
    }

    @Override
    public FunctionInfo info() {
        return info;
    }

    @Override
    public boolean iterate(MaximumAggState state, Input... args) throws CircuitBreakingException {
        Object value = args[0].value();
        assert value == null || value instanceof Comparable;
        state.add((Comparable) value);
        return true;
    }

    public static abstract class MaximumAggState extends VariableSizeAggregationState<MaximumAggState> {

        private Comparable value = null;

        public MaximumAggState(RamAccountingContext ramAccountingContext, SizeEstimator sizeEstimator) {
            super(ramAccountingContext, sizeEstimator);
        }

        @Override
        public Object value() {
            return value;
        }

        @Override
        public void reduce(MaximumAggState other) throws CircuitBreakingException {
            if (other.value == null) {
                return;
            }
            if (value == null || compareTo(other) < 0) {
                addEstimatedSize(sizeEstimator.estimateSize(value, other.value));
                value = other.value;
            }
        }

        void add(Comparable otherValue) throws CircuitBreakingException {
            if (otherValue == null) {
                return;
            }
            if (value == null || compareValue(otherValue) < 0) {
                addEstimatedSize(sizeEstimator.estimateSize(value, otherValue));
                value = otherValue;
            }
        }

        public void setValue(Comparable value) throws CircuitBreakingException {
            addEstimatedSize(sizeEstimator.estimateSize(this.value, value));
            this.value = value;
        }

        @Override
        public int compareTo(MaximumAggState o) {
            if (o == null) return -1;
            return compareValue(o.value);
        }

        public int compareValue(Object otherValue) {
            if (value == null) return (otherValue == null ? 0 : -1);
            if (otherValue == null) return 1;

            return value.compareTo(otherValue);
        }

        @Override
        public String toString() {
            return "<MaximumAggState \"" + value + "\"";
        }
    }


}
