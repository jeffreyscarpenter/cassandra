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
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.PriorityQueue;

import com.google.common.base.MoreObjects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.db.marshal.VectorType;
import org.apache.cassandra.index.sai.IndexContext;
import org.apache.cassandra.index.sai.SSTableQueryContext;
import org.apache.cassandra.index.sai.disk.IndexSearcherContext;
import org.apache.cassandra.index.sai.disk.PostingList;
import org.apache.cassandra.index.sai.disk.PostingListRangeIterator;
import org.apache.cassandra.index.sai.disk.PrimaryKeyMap;
import org.apache.cassandra.index.sai.disk.format.IndexComponent;
import org.apache.cassandra.index.sai.disk.format.IndexDescriptor;
import org.apache.cassandra.index.sai.plan.Expression;
import org.apache.cassandra.index.sai.utils.RangeIterator;
import org.apache.cassandra.io.util.File;
import org.apache.cassandra.utils.Hex;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.lucene95.Lucene95Codec;
import org.apache.lucene.codecs.lucene95.Lucene95HnswVectorsFormat;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.SegmentInfo;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.Version;

/**
 * Executes ann search against the HNSW graph for an individual index segment.
 */
public class VectorIndexSearcher extends IndexSearcher
{
    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final KnnVectorsReader reader;
    private final int numRows;

    VectorIndexSearcher(PrimaryKeyMap.Factory primaryKeyMapFactory,
                        PerIndexFiles perIndexFiles, // TODO not used for now because lucene has different file extensions
                        SegmentMetadata segmentMetadata,
                        IndexDescriptor indexDescriptor,
                        IndexContext indexContext) throws IOException
    {
        super(primaryKeyMapFactory, perIndexFiles, segmentMetadata, indexDescriptor, indexContext);

        File vectorPath = indexDescriptor.fileFor(IndexComponent.VECTOR, indexContext);
        Directory directory = FSDirectory.open(vectorPath.toPath().getParent());
        String segmentName = vectorPath.name();

        Map<String, String> configs = segmentMetadata.componentMetadatas.get(IndexComponent.VECTOR).attributes;
        String segmentIdHex = configs.get("SEGMENT_ID");
        byte[] segmentId = Hex.hexToBytes(segmentIdHex);

        int maxDocId = Math.toIntExact(segmentMetadata.maxSSTableRowId); // TODO we don't support more than 2.1B docs per segment. Do not enable segment merging
        this.numRows = Math.toIntExact(segmentMetadata.numRows);
        SegmentInfo segmentInfo = new SegmentInfo(directory, Version.LATEST, Version.LATEST, segmentName, maxDocId, false, Lucene95Codec.getDefault(), Collections.emptyMap(), segmentId, Collections.emptyMap(), null);

        int vectorDimension = ((VectorType) indexContext.getValidator()).getDimensions();
        FieldInfo fieldInfo = indexContext.createFieldInfoForVector(vectorDimension);
        FieldInfos fieldInfos = new FieldInfos(Collections.singletonList(fieldInfo).toArray(new FieldInfo[0]));
        SegmentReadState state = new SegmentReadState(directory, segmentInfo, fieldInfos, IOContext.DEFAULT);
        reader = new Lucene95HnswVectorsFormat(indexContext.getIndexWriterConfig().getMaximumNodeConnections(),
                                               indexContext.getIndexWriterConfig().getConstructionBeamWidth()).fieldsReader(state);
    }

    @Override
    public long indexFileCacheSize()
    {
        return reader.ramBytesUsed();
    }

    @Override
    @SuppressWarnings("resource")
    public RangeIterator search(Expression exp, SSTableQueryContext context, boolean defer, int limit) throws IOException
    {
        if (logger.isTraceEnabled())
            logger.trace(indexContext.logMessage("Searching on expression '{}'..."), exp);

        if (exp.getOp() != Expression.Op.ANN)
            throw new IllegalArgumentException(indexContext.logMessage("Unsupported expression during ANN index query: " + exp));

        String field = indexContext.getIndexName();

        ByteBuffer buffer = exp.lower.value.raw;
        float[] queryVector = (float[])indexContext.getValidator().getSerializer().deserialize(buffer.duplicate());

        return toIterator(new BatchPostingList(field, queryVector, limit), context, defer);
    }

    @Override
    public String toString()
    {
        return MoreObjects.toStringHelper(this)
                          .add("indexContext", indexContext)
                          .toString();
    }

    @Override
    public void close() throws IOException
    {
        reader.close();
    }

    public class BatchPostingList implements PostingList
    {
        private final String field;
        private final float[] queryVector;
        private final PriorityQueue<Long> queue;

        private int limit;
        private FixedBitSet bitset;

        BatchPostingList(String field, float[] queryVector, int limit)
        {
            this.field = field;
            this.queryVector = queryVector;
            this.limit = limit;
            this.queue = new PriorityQueue<>();
        }

        @Override
        public long nextPosting() throws IOException
        {
            return computeNextPosting();
        }

        @Override
        public long size()
        {
            // TODO Figure out what this should be
            return limit;
        }

        @Override
        public long advance(long targetRowID) throws IOException
        {
            long rowId = computeNextPosting();
            while (rowId < targetRowID)
                rowId = computeNextPosting();

            return rowId;
        }

        private long computeNextPosting() throws IOException
        {
            if (queue.isEmpty())
            {
                readBatch();
                if (queue.isEmpty())
                    return PostingList.END_OF_STREAM;
            }

            return queue.poll();
        }

        private void readBatch() throws IOException
        {
            TopDocs docs = reader.search(field, queryVector, limit, new InvertedBits(bitset), Integer.MAX_VALUE);
            if (bitset == null)
                bitset = new FixedBitSet(numRows);
            limit *= 2;
            for (ScoreDoc doc : docs.scoreDocs)
            {
                queue.offer((long)doc.doc);
                bitset.set(doc.doc);
            }
        }
    }

    private static class InvertedBits implements Bits
    {
        private final Bits wrapped;

        InvertedBits(Bits wrapped)
        {
            this.wrapped = wrapped;
        }

        @Override
        public boolean get(int i)
        {
            return wrapped == null ? true : !wrapped.get(i);
        }

        @Override
        public int length()
        {
            return wrapped == null ? 0 : wrapped.length();
        }
    }
}