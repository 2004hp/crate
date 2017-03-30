/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.operation.collect.collectors;

import com.google.common.util.concurrent.MoreExecutors;
import io.crate.analyze.OrderBy;
import io.crate.data.BatchIterator;
import io.crate.data.BatchRowVisitor;
import io.crate.data.Input;
import io.crate.data.LimitingBatchIterator;
import io.crate.lucene.FieldTypeLookup;
import io.crate.metadata.Reference;
import io.crate.metadata.ReferenceIdent;
import io.crate.metadata.RowGranularity;
import io.crate.metadata.TableIdent;
import io.crate.operation.projectors.sorting.OrderingByPosition;
import io.crate.operation.reference.doc.lucene.CollectorContext;
import io.crate.operation.reference.doc.lucene.LuceneCollectorExpression;
import io.crate.operation.reference.doc.lucene.OrderByCollectorExpression;
import io.crate.types.DataTypes;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.*;
import org.apache.lucene.store.RAMDirectory;
import org.elasticsearch.index.fielddata.IndexFieldDataService;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.core.IntegerFieldMapper;
import org.elasticsearch.index.shard.ShardId;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.mockito.Mockito.mock;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
public class OrderedLuceneBatchIteratorBenchmark {

    private String columnName;
    private IndexSearcher indexSearcher;
    private boolean[] reverseFlags = new boolean[]{true};
    private Boolean[] nullsFirst = new Boolean[]{null};
    private FieldTypeLookup fieldTypeLookup;
    private Reference reference;
    private OrderBy orderBy;
    private CollectorContext collectorContext;
    private ShardId dummyShardId;

    @Setup
    public void createLuceneBatchIterator() throws Exception {
        IndexWriter iw = new IndexWriter(
            new RAMDirectory(), new IndexWriterConfig(new StandardAnalyzer())
        );
        dummyShardId = new ShardId("dummy", 1);
        columnName = "x";
        for (int i = 0; i < 10_000_000; i++) {
            Document doc = new Document();
            doc.add(new NumericDocValuesField(columnName, i));
            iw.addDocument(doc);
        }
        iw.commit();
        iw.forceMerge(1, true);
        indexSearcher = new IndexSearcher(DirectoryReader.open(iw, true));
        collectorContext = new CollectorContext(
            mock(IndexFieldDataService.class),
            new CollectorFieldsVisitor(0)
        );
        fieldTypeLookup = column -> {
            IntegerFieldMapper.IntegerFieldType integerFieldType = new IntegerFieldMapper.IntegerFieldType();
            integerFieldType.setNames(new MappedFieldType.Names(column));
            return integerFieldType;
        };
        reference = new Reference(
            new ReferenceIdent(new TableIdent(null, "dummyTable"), columnName), RowGranularity.DOC, DataTypes.INTEGER);
        orderBy = new OrderBy(
            Collections.singletonList(reference),
            reverseFlags,
            nullsFirst
        );
    }

    @Benchmark
    public void measureLoadAndConsumeOrderedLuceneBatchIterator(Blackhole blackhole) {
        BatchIterator it = OrderedLuceneBatchIteratorFactory.newInstance(
            Collections.singletonList(createOrderedCollector(indexSearcher, columnName, 10_000_000)),
            1,
            OrderingByPosition.rowOrdering(new int[]{0}, reverseFlags, nullsFirst),
            MoreExecutors.directExecutor(),
            false
        );
        while (!it.allLoaded()) {
            it.loadNextBatch().toCompletableFuture().join();
        }
        Input<?> input = it.rowData().get(0);
        while (it.moveNext()) {
            blackhole.consume(input.value());
        }
    }

    @Benchmark
    public Long measureLoadAndConsumeOrderedLuceneBatchIteratorCounting() throws Exception {
        BatchIterator it = OrderedLuceneBatchIteratorFactory.newInstance(
            Collections.singletonList(createOrderedCollector(indexSearcher, columnName, 10_000_000)),
            1,
            OrderingByPosition.rowOrdering(new int[]{0}, reverseFlags, nullsFirst),
            MoreExecutors.directExecutor(),
            false
        );
        return BatchRowVisitor.visitRows(it, Collectors.counting()).get(30, TimeUnit.SECONDS);
    }

    @Benchmark
    public Long measureLoadAndConsumeOrderedLuceneBatchIteratorCountingLimit100() throws Exception {
        BatchIterator it = OrderedLuceneBatchIteratorFactory.newInstance(
            Collections.singletonList(createOrderedCollector(indexSearcher, columnName, 100)),
            1,
            OrderingByPosition.rowOrdering(new int[]{0}, reverseFlags, nullsFirst),
            MoreExecutors.directExecutor(),
            false
        );
        it = LimitingBatchIterator.newInstance(it, 100);
        return BatchRowVisitor.visitRows(it, Collectors.counting()).get(30, TimeUnit.SECONDS);
    }

    private LuceneOrderedDocCollector createOrderedCollector(IndexSearcher searcher,
                                                             String sortByColumnName,
                                                             int batchSize) {
        List<LuceneCollectorExpression<?>> expressions = Collections.singletonList(
            new OrderByCollectorExpression(reference, orderBy));
        return new LuceneOrderedDocCollector(
            dummyShardId,
            searcher,
            new MatchAllDocsQuery(),
            null,
            false,
            batchSize,
            fieldTypeLookup,
            collectorContext,
            orderBy,
            new Sort(new SortedNumericSortField(sortByColumnName, SortField.Type.INT, reverseFlags[0])),
            expressions,
            expressions
        );
    }

}
