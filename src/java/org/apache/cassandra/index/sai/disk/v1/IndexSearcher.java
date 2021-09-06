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

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.cassandra.index.sai.IndexContext;
import org.apache.cassandra.index.sai.SSTableQueryContext;
import org.apache.cassandra.index.sai.disk.IndexSearcherContext;
import org.apache.cassandra.index.sai.disk.PerIndexFiles;
import org.apache.cassandra.index.sai.disk.PostingList;
import org.apache.cassandra.index.sai.disk.PostingListRangeIterator;
import org.apache.cassandra.index.sai.disk.PrimaryKeyMap;
import org.apache.cassandra.index.sai.disk.format.IndexDescriptor;
import org.apache.cassandra.index.sai.plan.Expression;
import org.apache.cassandra.index.sai.utils.RangeIterator;

/**
 * Abstract reader for individual segments of an on-disk index.
 *
 * Accepts shared resources (token/offset file readers), and uses them to perform lookups against on-disk data
 * structures.
 */
public abstract class IndexSearcher implements Closeable
{
    final PrimaryKeyMap.Factory primaryKeyMapFactory;
    final PerIndexFiles indexFiles;
    final SegmentMetadata metadata;
    final IndexDescriptor indexDescriptor;
    final IndexContext indexContext;

    IndexSearcher(PrimaryKeyMap.Factory primaryKeyMapFactory,
                  PerIndexFiles perIndexFiles,
                  SegmentMetadata segmentMetadata,
                  IndexDescriptor indexDescriptor,
                  IndexContext indexContext)
    {
        this.primaryKeyMapFactory = primaryKeyMapFactory;
        this.indexFiles = perIndexFiles;
        this.metadata = segmentMetadata;
        this.indexDescriptor = indexDescriptor;
        this.indexContext = indexContext;
    }

    public static IndexSearcher open(PrimaryKeyMap.Factory primaryKeyMapFactory, PerIndexFiles indexFiles, SegmentMetadata segmentMetadata, IndexDescriptor indexDescriptor, IndexContext columnContext) throws IOException
    {
        return columnContext.isLiteral() ? new InvertedIndexSearcher(primaryKeyMapFactory, indexFiles, segmentMetadata, indexDescriptor, columnContext)
                                         : new KDTreeIndexSearcher(primaryKeyMapFactory, indexFiles, segmentMetadata, indexDescriptor, columnContext);
    }

    /**
     * @return memory usage of underlying on-disk data structure
     */
    public abstract long indexFileCacheSize();

    /**
     * Search on-disk index synchronously.
     *
     * @param expression to filter on disk index
     * @param queryContext to track per sstable cache and per query metrics
     *
     * @return {@link RangeIterator} that matches given expression
     */
    public abstract List<RangeIterator> search(Expression expression, SSTableQueryContext queryContext) throws IOException;

    List<RangeIterator> toIterators(List<PostingList.PeekablePostingList> postingLists, SSTableQueryContext queryContext) throws IOException
    {
        if (postingLists == null || postingLists.isEmpty())
            return Collections.emptyList();

        List<RangeIterator> iterators = new ArrayList<>();

        for (PostingList.PeekablePostingList postingList : postingLists)
        {
            IndexSearcherContext searcherContext = new IndexSearcherContext(metadata.minKey,
                                                                            metadata.maxKey,
                                                                            queryContext,
                                                                            postingList.peekable());

            iterators.add(new PostingListRangeIterator(indexContext, searcherContext));
        }

        return iterators;
    }
}