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

package org.apache.cassandra.index.sai.disk.hnsw;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.io.util.FileHandle;
import org.apache.cassandra.io.util.RandomAccessReader;
import org.apache.lucene.util.Bits;

public class OnDiskOrdinalsMap
{
    private static final Logger logger = LoggerFactory.getLogger(OnDiskOrdinalsMap.class);

    private final FileHandle fh;
    private final long ordToRowOffset;
    private final long segmentEnd;
    private final int size;
    // the offset where we switch from recording ordinal -> rows, to row -> ordinal
    private final long rowOrdinalOffset;
    private final Set<Integer> deletedOrdinals;

    public OnDiskOrdinalsMap(FileHandle fh, long segmentOffset, long segmentLength)
    {
        deletedOrdinals = new HashSet<>();

        this.segmentEnd = segmentOffset + segmentLength;
        this.fh = fh;
        try (var reader = fh.createReader())
        {
            int deletedCount = reader.readInt();
            for (var i = 0; i < deletedCount; i++)
            {
                deletedOrdinals.add(reader.readInt());
            }

            this.ordToRowOffset = reader.getFilePointer();
            this.size = reader.readInt();
            reader.seek(segmentEnd - 8);
            this.rowOrdinalOffset = reader.readLong();
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    public RowIdsView getRowIdsView()
    {
        return new RowIdsView();
    }

    public Bits ignoringDeleted(Bits acceptBits)
    {
        return BitsUtil.bitsIgnoringDeleted(acceptBits, deletedOrdinals);
    }

    public class RowIdsView implements AutoCloseable
    {
        RandomAccessReader reader = fh.createReader();

        public int[] getSegmentRowIdsMatching(int vectorOrdinal) throws IOException
        {
            Preconditions.checkArgument(vectorOrdinal < size, "vectorOrdinal %s is out of bounds %s", vectorOrdinal, size);

            // read index entry
            reader.seek(ordToRowOffset + 4L + vectorOrdinal * 8L);
            var offset = reader.readLong();
            // seek to and read ordinals
            reader.seek(offset);
            var postingsSize = reader.readInt();
            var ordinals = new int[postingsSize];
            for (var i = 0; i < ordinals.length; i++)
            {
                ordinals[i] = reader.readInt();
            }
            return ordinals;
        }

        @Override
        public void close()
        {
            reader.close();
        }
    }

    public OrdinalsView getOrdinalsView()
    {
        return new OrdinalsView();
    }

    public class OrdinalsView implements AutoCloseable
    {
        RandomAccessReader reader = fh.createReader();
        private final long high = (segmentEnd - 8 - rowOrdinalOffset) / 8;

        /**
         * @return order if given row id is found; otherwise return -1
         */
        public int getOrdinalForRowId(int rowId) throws IOException
        {
            // Compute the offset of the start of the rowId to vectorOrdinal mapping
            long index = DiskBinarySearch.searchInt(0, Math.toIntExact(high), rowId, i -> {
                try
                {
                    long offset = rowOrdinalOffset + i * 8;
                    reader.seek(offset);
                    return reader.readInt();
                }
                catch (IOException e)
                {
                    throw new RuntimeException(e);
                }
            });

            // not found
            if (index < 0)
                return -1;

            return reader.readInt();
        }

        @Override
        public void close()
        {
            reader.close();
        }
    }

    public void close()
    {
        fh.close();
    }
}