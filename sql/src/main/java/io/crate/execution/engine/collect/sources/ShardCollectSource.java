/*
 * Licensed to Crate.IO GmbH ("Crate") under one or more contributor
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

package io.crate.execution.engine.collect.sources;

import com.carrotsearch.hppc.IntIndexedContainer;
import com.carrotsearch.hppc.cursors.IntCursor;
import com.google.common.base.Suppliers;
import com.google.common.collect.Iterables;
import io.crate.analyze.OrderBy;
import io.crate.blob.v2.BlobIndicesService;
import io.crate.breaker.RowAccountingWithEstimators;
import io.crate.data.AsyncCompositeBatchIterator;
import io.crate.data.BatchIterator;
import io.crate.data.Buckets;
import io.crate.data.CompositeBatchIterator;
import io.crate.data.InMemoryBatchIterator;
import io.crate.data.Row;
import io.crate.data.SentinelRow;
import io.crate.exceptions.UnhandledServerException;
import io.crate.execution.TransportActionProvider;
import io.crate.execution.dsl.phases.CollectPhase;
import io.crate.execution.dsl.phases.RoutedCollectPhase;
import io.crate.execution.dsl.projection.Projections;
import io.crate.execution.engine.collect.CollectTask;
import io.crate.execution.engine.collect.RemoteCollectorFactory;
import io.crate.execution.engine.collect.ShardCollectorProvider;
import io.crate.execution.engine.collect.collectors.OrderedDocCollector;
import io.crate.execution.engine.collect.collectors.OrderedLuceneBatchIteratorFactory;
import io.crate.execution.engine.pipeline.ProjectionToProjectorVisitor;
import io.crate.execution.engine.pipeline.ProjectorFactory;
import io.crate.execution.engine.pipeline.Projectors;
import io.crate.execution.engine.sort.OrderingByPosition;
import io.crate.execution.jobs.NodeJobsCounter;
import io.crate.execution.jobs.SharedShardContext;
import io.crate.execution.jobs.SharedShardContexts;
import io.crate.execution.support.ThreadPools;
import io.crate.expression.InputFactory;
import io.crate.expression.eval.EvaluatingNormalizer;
import io.crate.expression.reference.StaticTableReferenceResolver;
import io.crate.expression.symbol.Symbols;
import io.crate.lucene.LuceneQueryBuilder;
import io.crate.metadata.Functions;
import io.crate.metadata.IndexParts;
import io.crate.metadata.MapBackedRefResolver;
import io.crate.metadata.RowGranularity;
import io.crate.metadata.Schemas;
import io.crate.metadata.doc.DocSysColumns;
import io.crate.metadata.shard.unassigned.UnassignedShard;
import io.crate.metadata.sys.SysShardsTableInfo;
import io.crate.planner.consumer.OrderByPositionVisitor;
import io.crate.plugin.IndexEventListenerProxy;
import io.crate.types.DataType;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.shard.IllegalIndexShardStateException;
import org.elasticsearch.index.shard.IndexEventListener;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.shard.ShardNotFoundException;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static io.crate.execution.support.ThreadPools.numIdleThreads;

/**
 * Used to create BatchIterators to gather documents stored within shards or to gather information about shards themselves.
 *
 * <p>
 *     The data is always exposed as a single BachIterator.
 * </p>
 *
 * <h2>Concurrent consumption</h2>
 *
 * <p>
 *     In order to parallelize the consumption of multiple *inner* BatchIterators for certain operations
 *     (e.g. Aggregations, Group By..), a CompositeBatchIterator is used that loads all sources concurrently.
 * </p>
 * <p>
 *     This might look as follows:
 * </p>
 *
 * <pre>
 *     shard/searcher                        shard/searcher
 *       |                                         |
 *    LuceneBatchIterator                  LuceneBatchIterator
 *       |                                         |
 *    CollectingBatchIterator            CollectingBatchIterator
 *       \                                        /
 *        \_____________                _________/
 *                      \              /
 *                    AsyncCompositeBatchIterator
 *                     (with concurrent/ loadNextBatch of sources)
 * </pre>
 *
 * In other cases multiple shards are simply processed sequentially by concatenating the BatchIterators
 */
@Singleton
public class ShardCollectSource extends AbstractComponent implements CollectSource {

    private final IndicesService indicesService;
    private final ClusterService clusterService;
    private final RemoteCollectorFactory remoteCollectorFactory;
    private final SystemCollectSource systemCollectSource;
    private final Executor executor;
    private final EvaluatingNormalizer nodeNormalizer;
    private final ProjectorFactory sharedProjectorFactory;

    private final Map<ShardId, Supplier<ShardCollectorProvider>> shards = new ConcurrentHashMap<>();
    private final ShardCollectorProviderFactory shardCollectorProviderFactory;
    private final StaticTableReferenceResolver<UnassignedShard> unassignedShardReferenceResolver;
    private final IntSupplier availableThreads;

    @Inject
    public ShardCollectSource(Settings settings,
                              Schemas schemas,
                              IndicesService indicesService,
                              Functions functions,
                              ClusterService clusterService,
                              NodeJobsCounter nodeJobsCounter,
                              LuceneQueryBuilder luceneQueryBuilder,
                              ThreadPool threadPool,
                              TransportActionProvider transportActionProvider,
                              RemoteCollectorFactory remoteCollectorFactory,
                              SystemCollectSource systemCollectSource,
                              IndexEventListenerProxy indexEventListenerProxy,
                              BlobIndicesService blobIndicesService,
                              BigArrays bigArrays) {
        super(settings);
        this.unassignedShardReferenceResolver = new StaticTableReferenceResolver<>(
            SysShardsTableInfo.unassignedShardsExpressions());
        this.indicesService = indicesService;
        this.clusterService = clusterService;
        this.remoteCollectorFactory = remoteCollectorFactory;
        this.systemCollectSource = systemCollectSource;
        ThreadPoolExecutor executor = (ThreadPoolExecutor) threadPool.executor(ThreadPool.Names.SEARCH);
        this.availableThreads = numIdleThreads(executor, EsExecutors.numberOfProcessors(settings));
        this.executor = ThreadPools.fallbackOnRejection(executor);
        this.shardCollectorProviderFactory = new ShardCollectorProviderFactory(
            clusterService,
            settings,
            schemas,
            threadPool,
            transportActionProvider,
            blobIndicesService,
            functions,
            luceneQueryBuilder,
            nodeJobsCounter,
            bigArrays);
        nodeNormalizer = new EvaluatingNormalizer(
            functions,
            RowGranularity.DOC,
            new MapBackedRefResolver(Collections.emptyMap()),
            null);

        sharedProjectorFactory = new ProjectionToProjectorVisitor(
            clusterService,
            nodeJobsCounter,
            functions,
            threadPool,
            settings,
            transportActionProvider,
            new InputFactory(functions),
            nodeNormalizer,
            systemCollectSource::getRowUpdater,
            systemCollectSource::tableDefinition,
            bigArrays
        );

        indexEventListenerProxy.addLast(new LifecycleListener());
    }

    public ProjectorFactory getProjectorFactory(ShardId shardId) {
        ShardCollectorProvider collectorProvider = getCollectorProviderSafe(shardId);
        return collectorProvider.getProjectorFactory();
    }

    private class LifecycleListener implements IndexEventListener {

        @Override
        public void afterIndexShardCreated(IndexShard indexShard) {
            logger.debug("creating shard in {} {} {}", ShardCollectSource.this, indexShard.shardId(), shards.size());
            assert !shards.containsKey(indexShard.shardId()) : "shard entry already exists upon add";

            /* The creation of a ShardCollectorProvider accesses the clusterState, which leads to an
             * assertionError if accessed within a ClusterState-Update thread.
             *
             * So we wrap the creation in a supplier to create the providers lazy
             */

            Supplier<ShardCollectorProvider> providerSupplier = Suppliers.memoize(() ->
                shardCollectorProviderFactory.create(indexShard)
            );
            shards.put(indexShard.shardId(), providerSupplier);

        }

        @Override
        public void beforeIndexShardClosed(ShardId shardId, @Nullable IndexShard indexShard, Settings indexSettings) {
            logger.debug("removing shard upon close in {} shard={} numShards={}", ShardCollectSource.this, shardId, shards.size());
            assert shards.containsKey(shardId) : "shard entry missing upon close";
            shards.remove(shardId);
        }

        @Override
        public void beforeIndexShardDeleted(ShardId shardId, Settings indexSettings) {
            if (shards.remove(shardId) != null) {
                logger.debug("removed shard upon delete in {} shard={} remainingShards={}", ShardCollectSource.this, shardId, shards.size());
            } else {
                logger.debug("shard not found upon delete in {} shard={} remainingShards={}", ShardCollectSource.this, shardId, shards.size());
            }
        }
    }


    @Override
    public BatchIterator<Row> getIterator(CollectPhase phase, CollectTask collectTask, boolean supportMoveToStart) {
        RoutedCollectPhase collectPhase = (RoutedCollectPhase) phase;
        RoutedCollectPhase normalizedPhase = collectPhase.normalize(nodeNormalizer, null);
        String localNodeId = clusterService.localNode().getId();

        Projectors projectors = new Projectors(
            normalizedPhase.projections(),
            normalizedPhase.jobId(),
            collectTask.queryPhaseRamAccountingContext(),
            sharedProjectorFactory
        );
        boolean requireMoveToStartSupport = supportMoveToStart && !projectors.providesIndependentScroll();

        if (normalizedPhase.maxRowGranularity() == RowGranularity.SHARD) {
            return projectors.wrap(InMemoryBatchIterator.of(
                getShardsIterator(collectPhase, normalizedPhase, localNodeId), SentinelRow.SENTINEL));
        }
        OrderBy orderBy = normalizedPhase.orderBy();
        if (normalizedPhase.maxRowGranularity() == RowGranularity.DOC && orderBy != null) {
            return projectors.wrap(createMultiShardScoreDocCollector(
                normalizedPhase,
                requireMoveToStartSupport,
                collectTask,
                localNodeId
            ));
        }

        boolean hasShardProjections = Projections.hasAnyShardProjections(normalizedPhase.projections());
        Map<String, IntIndexedContainer> indexShards = normalizedPhase.routing().locations().get(localNodeId);
        List<BatchIterator<Row>> iterators = indexShards == null
            ? Collections.emptyList()
            : getIterators(collectTask, normalizedPhase, requireMoveToStartSupport, indexShards);

        final BatchIterator<Row> result;
        switch (iterators.size()) {
            case 0:
                result = InMemoryBatchIterator.empty(SentinelRow.SENTINEL);
                break;

            case 1:
                result = iterators.get(0);
                break;

            default:
                if (hasShardProjections) {
                    // use AsyncCompositeBatchIterator for multi-threaded loadNextBatch
                    // in order to process shard-based projections concurrently

                    //noinspection unchecked
                    result = new AsyncCompositeBatchIterator<>(
                        executor, availableThreads, iterators.toArray(new BatchIterator[0]));
                } else {
                    //noinspection unchecked
                    result = new CompositeBatchIterator<>(iterators.toArray(new BatchIterator[0]));
                }
        }
        return projectors.wrap(result);
    }

    private BatchIterator<Row> createMultiShardScoreDocCollector(RoutedCollectPhase collectPhase,
                                                                 boolean supportMoveToStart,
                                                                 CollectTask collectTask,
                                                                 String localNodeId) {

        Map<String, Map<String, IntIndexedContainer>> locations = collectPhase.routing().locations();
        SharedShardContexts sharedShardContexts = collectTask.sharedShardContexts();
        Map<String, IntIndexedContainer> indexShards = locations.get(localNodeId);
        List<OrderedDocCollector> orderedDocCollectors = new ArrayList<>();
        MetaData metaData = clusterService.state().metaData();
        for (Map.Entry<String, IntIndexedContainer> entry : indexShards.entrySet()) {
            String indexName = entry.getKey();
            Index index = metaData.index(indexName).getIndex();

            for (IntCursor shard : entry.getValue()) {
                ShardId shardId = new ShardId(index, shard.value);
                SharedShardContext context = sharedShardContexts.getOrCreateContext(shardId);

                try {
                    ShardCollectorProvider shardCollectorProvider = getCollectorProviderSafe(shardId);
                    orderedDocCollectors.add(shardCollectorProvider.getOrderedCollector(
                        collectPhase,
                        context,
                        collectTask,
                        supportMoveToStart)
                    );
                } catch (ShardNotFoundException | IllegalIndexShardStateException e) {
                    throw e;
                } catch (IndexNotFoundException e) {
                    if (IndexParts.isPartitioned(indexName)) {
                        break;
                    }
                    throw e;
                } catch (Throwable t) {
                    throw new UnhandledServerException(t);
                }
            }
        }
        List<DataType> columnTypes = Symbols.typeView(collectPhase.toCollect());

        OrderBy orderBy = collectPhase.orderBy();
        assert orderBy != null : "orderBy must not be null";
        return OrderedLuceneBatchIteratorFactory.newInstance(
            orderedDocCollectors,
            OrderingByPosition.rowOrdering(
                OrderByPositionVisitor.orderByPositions(orderBy.orderBySymbols(), collectPhase.toCollect()),
                orderBy.reverseFlags(),
                orderBy.nullsFirst()
            ),
            new RowAccountingWithEstimators(columnTypes, collectTask.queryPhaseRamAccountingContext()),
            executor,
            availableThreads,
            supportMoveToStart
        );
    }

    private ShardCollectorProvider getCollectorProviderSafe(ShardId shardId) {
        Supplier<ShardCollectorProvider> supplier = shards.get(shardId);
        if (supplier == null) {
            throw new ShardNotFoundException(shardId);
        }
        ShardCollectorProvider shardCollectorProvider = supplier.get();
        if (shardCollectorProvider == null) {
            throw new ShardNotFoundException(shardId);
        }
        return shardCollectorProvider;
    }

    private List<BatchIterator<Row>> getIterators(CollectTask collectTask,
                                                  RoutedCollectPhase collectPhase,
                                                  boolean requiresScroll,
                                                  Map<String, IntIndexedContainer> indexShards) {

        MetaData metaData = clusterService.state().metaData();
        List<BatchIterator<Row>> iterators = new ArrayList<>();
        for (Map.Entry<String, IntIndexedContainer> entry : indexShards.entrySet()) {
            String indexName = entry.getKey();
            IndexMetaData indexMD = metaData.index(indexName);
            if (indexMD == null) {
                if (IndexParts.isPartitioned(indexName)) {
                    continue;
                }
                throw new IndexNotFoundException(indexName);
            }
            Index index = indexMD.getIndex();
            try {
                indicesService.indexServiceSafe(index);
            } catch (IndexNotFoundException e) {
                if (IndexParts.isPartitioned(indexName)) {
                    continue;
                }
                throw e;
            }
            for (IntCursor shardCursor: entry.getValue()) {
                ShardId shardId = new ShardId(index, shardCursor.value);
                try {
                    ShardCollectorProvider shardCollectorProvider = getCollectorProviderSafe(shardId);
                    BatchIterator<Row> iterator = shardCollectorProvider.getIterator(
                        collectPhase,
                        requiresScroll,
                        collectTask
                    );
                    iterators.add(iterator);
                } catch (ShardNotFoundException | IllegalIndexShardStateException e) {
                    // If toCollect contains a docId it means that this is a QueryThenFetch operation.
                    // In such a case RemoteCollect cannot be used because on that node the FetchTask is missing
                    // and the reader required in the fetchPhase would be missing.
                    if (Symbols.containsColumn(collectPhase.toCollect(), DocSysColumns.FETCHID)) {
                        throw e;
                    }
                    iterators.add(remoteCollectorFactory.createCollector(shardId, collectPhase, collectTask, shardCollectorProviderFactory));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } catch (IndexNotFoundException e) {
                    // Prevent wrapping this to not break retry-detection
                    throw e;
                } catch (Exception e) {
                    throw new UnhandledServerException(e);
                }
            }
        }
        return iterators;
    }

    private Iterable<Row> getShardsIterator(RoutedCollectPhase collectPhase,
                                            RoutedCollectPhase normalizedPhase,
                                            String localNodeId) {
        Map<String, Map<String, IntIndexedContainer>> locations = collectPhase.routing().locations();
        List<UnassignedShard> unassignedShards = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        Map<String, IntIndexedContainer> indexShardsMap = locations.get(localNodeId);
        MetaData metaData = clusterService.state().metaData();

        for (Map.Entry<String, IntIndexedContainer> indexShards : indexShardsMap.entrySet()) {
            String indexName = indexShards.getKey();
            IndexMetaData indexMetaData = metaData.index(indexName);
            if (indexMetaData == null) {
                continue;
            }
            Index index = indexMetaData.getIndex();
            IntIndexedContainer shards = indexShards.getValue();
            IndexService indexService = indicesService.indexService(index);
            if (indexService == null) {
                for (IntCursor shard : shards) {
                    unassignedShards.add(toUnassignedShard(index.getName(), UnassignedShard.markAssigned(shard.value)));
                }
                continue;
            }
            for (IntCursor shard : shards) {
                if (UnassignedShard.isUnassigned(shard.value)) {
                    unassignedShards.add(toUnassignedShard(index.getName(), UnassignedShard.markAssigned(shard.value)));
                    continue;
                }
                ShardId shardId = new ShardId(index, shard.value);
                try {
                    ShardCollectorProvider shardCollectorProvider = getCollectorProviderSafe(shardId);
                    Object[] row = shardCollectorProvider.getRowForShard(normalizedPhase);
                    if (row != null) {
                        rows.add(row);
                    }
                } catch (ShardNotFoundException | IllegalIndexShardStateException e) {
                    unassignedShards.add(toUnassignedShard(index.getName(), shard.value));
                } catch (Throwable t) {
                    throw new UnhandledServerException(t);
                }
            }
        }
        if (!unassignedShards.isEmpty()) {
            // since unassigned shards aren't really on any node we use the collectPhase which is NOT normalized here
            // because otherwise if _node was also selected it would contain something which is wrong
            for (Row row :
                systemCollectSource.toRowsIterableTransformation(collectPhase, unassignedShardReferenceResolver, false)
                    .apply(unassignedShards)) {
                rows.add(row.materialize());
            }
        }
        if (collectPhase.orderBy() != null) {
            rows.sort(OrderingByPosition.arrayOrdering(collectPhase).reverse());
        }
        return Iterables.transform(rows, Buckets.arrayToSharedRow()::apply);
    }

    private UnassignedShard toUnassignedShard(String indexName, int shardId) {
        return new UnassignedShard(shardId, indexName, clusterService, false, ShardRoutingState.UNASSIGNED);
    }
}
