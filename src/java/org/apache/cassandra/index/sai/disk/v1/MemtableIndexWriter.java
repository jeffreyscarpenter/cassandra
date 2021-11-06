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
import java.util.Collections;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Stopwatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.carrotsearch.hppc.LongArrayList;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.rows.Cell;
import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.index.sai.IndexContext;
import org.apache.cassandra.index.sai.disk.MemtableTermsIterator;
import org.apache.cassandra.index.sai.disk.PerIndexWriter;
import org.apache.cassandra.index.sai.disk.format.IndexComponent;
import org.apache.cassandra.index.sai.disk.format.IndexDescriptor;
import org.apache.cassandra.index.sai.disk.v1.kdtree.ImmutableOneDimPointValues;
import org.apache.cassandra.index.sai.disk.v1.kdtree.NumericIndexWriter;
import org.apache.cassandra.index.sai.disk.v1.postings.PackedLongsPostingList;
import org.apache.cassandra.index.sai.disk.v1.postings.PostingsWriter;
import org.apache.cassandra.index.sai.disk.v1.trie.InvertedIndexWriter;
import org.apache.cassandra.index.sai.memory.MemtableIndex;
import org.apache.cassandra.index.sai.memory.RowMapping;
import org.apache.cassandra.index.sai.utils.PrimaryKey;
import org.apache.cassandra.index.sai.utils.TypeUtil;
import org.apache.cassandra.utils.Pair;
import org.apache.cassandra.utils.bytecomparable.ByteComparable;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.packed.PackedInts;
import org.apache.lucene.util.packed.PackedLongValues;

import static org.apache.cassandra.index.sai.disk.StorageAttachedIndexWriter.sameTS;

/**
 * Column index writer that flushes indexed data directly from the corresponding Memtable index, without buffering index
 * data in memory.
 */
public class MemtableIndexWriter implements PerIndexWriter
{
    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final IndexDescriptor indexDescriptor;
    private final IndexContext indexContext;
    private final MemtableIndex memtable;
    private final RowMapping rowMapping;
    private final PackedLongValues.Builder missingValueInTSRowIdBuilder;

    public MemtableIndexWriter(MemtableIndex memtable,
                               IndexDescriptor indexDescriptor,
                               IndexContext indexContext,
                               RowMapping rowMapping)
    {
        assert rowMapping != null && rowMapping != RowMapping.DUMMY : "Row mapping must exist during FLUSH.";

        this.indexDescriptor = indexDescriptor;
        this.indexContext = indexContext;
        this.memtable = memtable;
        this.rowMapping = rowMapping;
        this.missingValueInTSRowIdBuilder = PackedLongValues.deltaPackedBuilder(PackedInts.COMPACT);
    }

    @Override
    public IndexContext indexContext()
    {
        return indexContext;
    }

    @Override
    public void addRow(PrimaryKey key, Row row)
    {
        // Memtable indexes are flushed directly to disk with the aid of a mapping between primary
        // keys and row IDs in the flushing SSTable. This writer, therefore, does nothing in
        // response to the flushing of individual rows.
        boolean sameTS = sameTS(row);

        Cell cell = row.getCell(indexContext.getTarget().left);
        if (cell == null || !sameTS)
        {
//            if (cell == null) System.out.println("cell==null rowid="+key.sstableRowId());
//            if (!sameTS) System.out.println("sameTS rowid="+key.sstableRowId());
            missingValueInTSRowIdBuilder.add(key.sstableRowId());
        }
    }

    @Override
    public void abort(Throwable cause)
    {
        logger.warn(indexContext.logMessage("Aborting index memtable flush for {}..."), indexDescriptor.descriptor, cause);
        indexDescriptor.deleteColumnIndex(indexContext);
    }

    @Override
    public void complete(Stopwatch stopwatch) throws IOException
    {
        long start = System.nanoTime();

        try
        {
            if (!rowMapping.hasRows() || (memtable == null) || memtable.isEmpty())
            {
                logger.debug(indexContext.logMessage("No indexed rows to flush from SSTable {}."), indexDescriptor.descriptor);
                // Write a completion marker even though we haven't written anything to the index
                // so we won't try to build the index again for the SSTable
                indexDescriptor.createComponentOnDisk(IndexComponent.COLUMN_COMPLETION_MARKER, indexContext);
                return;
            }

            final DecoratedKey minKey = rowMapping.minKey.partitionKey();
            final DecoratedKey maxKey = rowMapping.maxKey.partitionKey();

            final Iterator<Pair<ByteComparable, LongArrayList>> iterator = rowMapping.merge(memtable);

            try (MemtableTermsIterator terms = new MemtableTermsIterator(memtable.getMinTerm(), memtable.getMaxTerm(), iterator))
            {
                long cellCount = flush(minKey, maxKey, indexContext.getValidator(), terms, rowMapping.maxSegmentRowId);

                indexDescriptor.createComponentOnDisk(IndexComponent.COLUMN_COMPLETION_MARKER, indexContext);

                indexContext.getIndexMetrics().memtableIndexFlushCount.inc();

                long durationMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));

                if (logger.isTraceEnabled())
                {
                    logger.trace(indexContext.logMessage("Flushed {} Memtable index cells for {} in {} ms."),
                                 cellCount,
                                 indexDescriptor.descriptor,
                                 durationMillis);
                }

                indexContext.getIndexMetrics().memtableFlushCellsPerSecond.update((long) (cellCount * 1000.0 / durationMillis));
            }
        }
        catch (Throwable t)
        {
            logger.error(indexContext.logMessage("Error while flushing index {}"), t.getMessage(), t);
            indexContext.getIndexMetrics().memtableIndexFlushErrors.inc();

            throw t;
        }
    }

    private long flush(DecoratedKey minKey, DecoratedKey maxKey, AbstractType<?> termComparator, MemtableTermsIterator terms, long maxSegmentRowId) throws IOException
    {
        long numRows;
        SegmentMetadata.ComponentMetadataMap indexMetas;

        if (TypeUtil.isLiteral(termComparator))
        {
            try (InvertedIndexWriter writer = new InvertedIndexWriter(indexDescriptor, indexContext, false))
            {
                indexMetas = writer.writeAll(terms);
                numRows = writer.getPostingsCount();
            }
        }
        else
        {
            try (NumericIndexWriter writer = new NumericIndexWriter(indexDescriptor,
                                                                    indexContext,
                                                                    TypeUtil.fixedSizeOf(termComparator),
                                                                    maxSegmentRowId,
                                                                    // Due to stale entries in IndexMemtable, we may have more indexed rows than num of rowIds.
                                                                    Integer.MAX_VALUE,
                                                                    indexContext.getIndexWriterConfig(),
                                                                    false))
            {
                indexMetas = writer.writeAll(ImmutableOneDimPointValues.fromTermEnum(terms, termComparator));
                numRows = writer.getPointCount();
            }
        }

        // If no rows were written we need to delete any created column index components
        // so that the index is correctly identified as being empty (only having a completion marker)
        if (numRows == 0)
        {
            indexDescriptor.deleteColumnIndex(indexContext);
            return 0;
        }

        System.out.println("column="+indexContext.getColumnName()+" missingValueInTSRowIdBuilder.size="+missingValueInTSRowIdBuilder.size());
        if (missingValueInTSRowIdBuilder.size() > 0)
        {
            try (IndexOutput missingValuesOut = indexDescriptor.openPerIndexOutput(IndexComponent.MISSING_VALUES, indexContext, true, false))
            {
                long startFP = missingValuesOut.getFilePointer();
                PostingsWriter missingValuesWriter = new PostingsWriter(missingValuesOut);
                long missingValuesFP = missingValuesWriter.write(new PackedLongsPostingList(missingValueInTSRowIdBuilder.build()));
                long length = missingValuesOut.getFilePointer() - startFP;
                indexMetas.put(IndexComponent.MISSING_VALUES, 0, missingValuesFP, length);
            }
        }

        // During index memtable flush, the data is sorted based on terms.
        SegmentMetadata metadata = new SegmentMetadata(0,
                                                       numRows,
                                                       terms.getMinSSTableRowId(),
                                                       terms.getMaxSSTableRowId(),
                                                       indexDescriptor.primaryKeyFactory.createKey(minKey),
                                                       indexDescriptor.primaryKeyFactory.createKey(maxKey),
                                                       terms.getMinTerm(),
                                                       terms.getMaxTerm(),
                                                       indexMetas);

        try (MetadataWriter writer = new MetadataWriter(indexDescriptor.openPerIndexOutput(IndexComponent.META, indexContext)))
        {
            SegmentMetadata.write(writer, Collections.singletonList(metadata));
        }

        return numRows;
    }
}
