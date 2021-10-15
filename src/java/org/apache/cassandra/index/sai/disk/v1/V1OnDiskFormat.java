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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.index.sai.disk.v1;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Gauge;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.compaction.OperationType;
import org.apache.cassandra.db.lifecycle.LifecycleNewTracker;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.marshal.AsciiType;
import org.apache.cassandra.db.marshal.BooleanType;
import org.apache.cassandra.db.marshal.CompositeType;
import org.apache.cassandra.db.marshal.UTF8Type;
import org.apache.cassandra.index.sai.IndexContext;
import org.apache.cassandra.index.sai.SSTableContext;
import org.apache.cassandra.index.sai.StorageAttachedIndex;
import org.apache.cassandra.index.sai.disk.PerIndexWriter;
import org.apache.cassandra.index.sai.disk.IndexOnDiskMetadata;
import org.apache.cassandra.index.sai.disk.PerIndexFiles;
import org.apache.cassandra.index.sai.disk.PerSSTableWriter;
import org.apache.cassandra.index.sai.disk.PrimaryKeyMap;
import org.apache.cassandra.index.sai.disk.SearchableIndex;
import org.apache.cassandra.index.sai.disk.format.IndexComponent;
import org.apache.cassandra.index.sai.disk.format.IndexDescriptor;
import org.apache.cassandra.index.sai.disk.format.IndexFeatureSet;
import org.apache.cassandra.index.sai.disk.format.OnDiskFormat;
import org.apache.cassandra.index.sai.memory.RowMapping;
import org.apache.cassandra.index.sai.metrics.AbstractMetrics;
import org.apache.cassandra.index.sai.utils.NamedMemoryLimiter;
import org.apache.cassandra.index.sai.utils.SAICodecUtils;
import org.apache.cassandra.index.sai.utils.TypeOperations;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.io.util.FileHandle;
import org.apache.cassandra.metrics.CassandraMetricsRegistry;
import org.apache.cassandra.metrics.DefaultNameFactory;
import org.apache.lucene.store.IndexInput;

import static org.apache.cassandra.utils.FBUtilities.prettyPrintMemory;

public class V1OnDiskFormat implements OnDiskFormat
{
    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    public static final EnumSet<IndexComponent> PER_SSTABLE_COMPONENTS = EnumSet.of(IndexComponent.GROUP_COMPLETION_MARKER,
                                                                                    IndexComponent.GROUP_META,
                                                                                    IndexComponent.TOKEN_VALUES,
                                                                                    IndexComponent.OFFSETS_VALUES);
    public static final EnumSet<IndexComponent> LITERAL_COMPONENTS = EnumSet.of(IndexComponent.COLUMN_COMPLETION_MARKER,
                                                                                IndexComponent.META,
                                                                                IndexComponent.TERMS_DATA,
                                                                                IndexComponent.POSTING_LISTS);
    public static final EnumSet<IndexComponent> NUMERIC_COMPONENTS = EnumSet.of(IndexComponent.COLUMN_COMPLETION_MARKER,
                                                                                IndexComponent.META,
                                                                                IndexComponent.KD_TREE,
                                                                                IndexComponent.KD_TREE_POSTING_LISTS);

    /**
     * Global limit on heap consumed by all index segment building that occurs outside the context of Memtable flush.
     *
     * Note that to avoid flushing extremely small index segments, a segment is only flushed when
     * both the global size of all building segments has breached the limit and the size of the
     * segment in question reaches (segment_write_buffer_space_mb / # currently building column indexes).
     *
     * ex. If there is only one column index building, it can buffer up to segment_write_buffer_space_mb.
     *
     * ex. If there is one column index building per table across 8 compactors, each index will be
     *     eligible to flush once it reaches (segment_write_buffer_space_mb / 8) MBs.
     */
    public static final long SEGMENT_BUILD_MEMORY_LIMIT = Long.getLong("cassandra.test.sai.segment_build_memory_limit",
                                                                       1024L * 1024L * (long) DatabaseDescriptor.getSAISegmentWriteBufferSpace());

    public static final NamedMemoryLimiter SEGMENT_BUILD_MEMORY_LIMITER =
    new NamedMemoryLimiter(SEGMENT_BUILD_MEMORY_LIMIT, "SSTable-attached Index Segment Builder");

    static
    {
        CassandraMetricsRegistry.MetricName bufferSpaceUsed = DefaultNameFactory.createMetricName(AbstractMetrics.TYPE, "SegmentBufferSpaceUsedBytes", null);
        CassandraMetricsRegistry.Metrics.register(bufferSpaceUsed, (Gauge<Long>) SEGMENT_BUILD_MEMORY_LIMITER::currentBytesUsed);

        CassandraMetricsRegistry.MetricName bufferSpaceLimit = DefaultNameFactory.createMetricName(AbstractMetrics.TYPE, "SegmentBufferSpaceLimitBytes", null);
        CassandraMetricsRegistry.Metrics.register(bufferSpaceLimit, (Gauge<Long>) () -> SEGMENT_BUILD_MEMORY_LIMIT);

        // Note: The active builder count starts at 1 to avoid dividing by zero.
        CassandraMetricsRegistry.MetricName buildsInProgress = DefaultNameFactory.createMetricName(AbstractMetrics.TYPE, "ColumnIndexBuildsInProgress", null);
        CassandraMetricsRegistry.Metrics.register(buildsInProgress, (Gauge<Long>) () -> SegmentBuilder.ACTIVE_BUILDER_COUNT.get() - 1);
    }

    public static final V1OnDiskFormat instance = new V1OnDiskFormat();

    private static final IndexFeatureSet v1IndexFeatureSet = new IndexFeatureSet()
    {
        @Override
        public boolean isRowAware()
        {
            return false;
        }

        @Override
        public boolean usesNonStandardEncoding()
        {
            return true;
        }

        @Override
        public boolean supportsRounding()
        {
            return true;
        }
    };

    protected V1OnDiskFormat()
    {}

    @Override
    public IndexFeatureSet indexFeatureSet()
    {
        return v1IndexFeatureSet;
    }

    @Override
    public boolean isPerSSTableBuildComplete(IndexDescriptor indexDescriptor)
    {
        return indexDescriptor.hasComponent(IndexComponent.GROUP_COMPLETION_MARKER);
    }

    @Override
    public boolean isPerIndexBuildComplete(IndexDescriptor indexDescriptor, IndexContext indexContext)
    {
        return indexDescriptor.hasComponent(IndexComponent.GROUP_COMPLETION_MARKER) &&
               indexDescriptor.hasComponent(IndexComponent.COLUMN_COMPLETION_MARKER, indexContext);
    }

    @Override
    public boolean isBuildCompletionMarker(IndexComponent indexComponent)
    {
        return indexComponent == IndexComponent.GROUP_COMPLETION_MARKER ||
               indexComponent == IndexComponent.COLUMN_COMPLETION_MARKER;
    }

    @Override
    public boolean isEncryptable(IndexComponent indexComponent)
    {
        return indexComponent == IndexComponent.TERMS_DATA;
    }

    @Override
    public PrimaryKeyMap.Factory newPrimaryKeyMapFactory(IndexDescriptor indexDescriptor, SSTableReader sstable) throws IOException
    {
        return new V1PrimaryKeyMap.V1PrimaryKeyMapFactory(indexDescriptor, sstable);
    }

    @Override
    public SearchableIndex newSearchableIndex(SSTableContext sstableContext, IndexContext indexContext)
    {
        return new V1SearchableIndex(sstableContext, indexContext);
    }

    @Override
    public PerSSTableWriter newPerSSTableWriter(IndexDescriptor indexDescriptor) throws IOException
    {
        return new SSTableComponentsWriter(indexDescriptor);
    }

    @Override
    public PerIndexWriter newPerIndexWriter(StorageAttachedIndex index,
                                            IndexDescriptor indexDescriptor,
                                            LifecycleNewTracker tracker,
                                            RowMapping rowMapping)
    {
        // If we're not flushing or we haven't yet started the initialization build, flush from SSTable contents.
        if (tracker.opType() != OperationType.FLUSH || !index.isInitBuildStarted())
        {
            NamedMemoryLimiter limiter = SEGMENT_BUILD_MEMORY_LIMITER;
            logger.info(index.getIndexContext().logMessage("Starting a compaction index build. Global segment memory usage: {}"), prettyPrintMemory(limiter.currentBytesUsed()));

            return new SSTableIndexWriter(indexDescriptor, index.getIndexContext(), limiter, index.isIndexValid());
        }

        return new MemtableIndexWriter(index.getIndexContext().getPendingMemtableIndex(tracker), indexDescriptor, index.getIndexContext(), rowMapping);
    }

    @Override
    public IndexOnDiskMetadata.IndexMetadataSerializer newIndexMetadataSerializer()
    {
        return V1IndexOnDiskMetadata.serializer;
    }

    @Override
    public void validatePerSSTableComponent(IndexDescriptor indexDescriptor, IndexComponent indexComponent, boolean checksum) throws IOException
    {
        if (!isBuildCompletionMarker(indexComponent))
        {
            try (IndexInput input = indexDescriptor.openPerSSTableInput(indexComponent))
            {
                if (checksum)
                    SAICodecUtils.validateChecksum(input);
                else
                    SAICodecUtils.validate(input);
            }
            catch (IOException e)
            {
                if (logger.isDebugEnabled())
                {
                    logger.debug(indexDescriptor.logMessage("{} failed for index component {} on SSTable {}"),
                                 (checksum ? "Checksum validation" : "Validation"),
                                 indexComponent,
                                 indexDescriptor.descriptor);
                }
                throw e;
            }
        }
    }

    @Override
    public void validatePerIndexComponent(IndexDescriptor indexDescriptor, IndexComponent indexComponent, IndexContext indexContext, boolean checksum) throws IOException
    {
        if (!isBuildCompletionMarker(indexComponent))
        {
            try (IndexInput input = indexDescriptor.openPerIndexInput(indexComponent, indexContext))
            {
                if (checksum)
                    SAICodecUtils.validateChecksum(input);
                else
                    SAICodecUtils.validate(input);
            }
            catch (IOException e)
            {
                if (logger.isDebugEnabled())
                {
                    logger.debug(indexDescriptor.logMessage("{} failed for index component {} on SSTable {}"),
                                 (checksum ? "Checksum validation" : "Validation"),
                                 indexComponent,
                                 indexDescriptor.descriptor);
                }
                throw e;
            }
        }
    }

    @Override
    public Set<IndexComponent> perSSTableComponents()
    {
        return PER_SSTABLE_COMPONENTS;
    }

    @Override
    public Set<IndexComponent> perIndexComponents(IndexContext indexContext)
    {
        return isLiteral(indexContext.getValidator()) ? LITERAL_COMPONENTS : NUMERIC_COMPONENTS;
    }

    @Override
    public PerIndexFiles newPerIndexFiles(IndexDescriptor indexDescriptor, IndexContext indexContext, boolean temporary)
    {
        return new PerIndexFiles(indexDescriptor, indexContext, temporary)
        {
            @Override
            protected Map<IndexComponent, FileHandle> populate(IndexDescriptor indexDescriptor, IndexContext indexContext, boolean temporary)
            {
                Map<IndexComponent, FileHandle> files = new EnumMap<>(IndexComponent.class);
                if (isLiteral(indexContext.getValidator()))
                {
                    files.put(IndexComponent.POSTING_LISTS, indexDescriptor.createPerIndexFileHandle(IndexComponent.POSTING_LISTS, indexContext, temporary));
                    files.put(IndexComponent.TERMS_DATA, indexDescriptor.createPerIndexFileHandle(IndexComponent.TERMS_DATA, indexContext, temporary));
                }
                else
                {
                    files.put(IndexComponent.KD_TREE, indexDescriptor.createPerIndexFileHandle(IndexComponent.KD_TREE, indexContext, temporary));
                    files.put(IndexComponent.KD_TREE_POSTING_LISTS, indexDescriptor.createPerIndexFileHandle(IndexComponent.KD_TREE_POSTING_LISTS, indexContext, temporary));
                }
                return files;
            }
        };
    }

    @Override
    public int openFilesPerSSTable()
    {
        return 2;
    }

    @Override
    public int openFilesPerIndex(IndexContext indexContext)
    {
        // For the V1 format there are always 2 open files per index - index (kdtree or terms) + postings
        return 2;
    }

    private boolean isLiteral(AbstractType<?> type)
    {
        AbstractType<?> baseType = org.apache.cassandra.index.sai.utils.TypeUtil.instance.baseType(type);
        return baseType instanceof UTF8Type || baseType instanceof AsciiType || baseType instanceof BooleanType ||
               baseType instanceof CompositeType || (!baseType.subTypes().isEmpty() && !baseType.isMultiCell());
    }
}