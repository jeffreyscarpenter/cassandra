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

public abstract class Lucene75PointValues {

    /** Maximum number of bytes for each dimension */
    public static final int MAX_NUM_BYTES = 16;

    /** Maximum number of dimensions */
    public static final int MAX_DIMENSIONS = BKDWriter.MAX_DIMS;

    /** Default constructor */
    protected Lucene75PointValues() {
    }

    /** Used by {@link #intersect} to check how each recursive cell corresponds to the query. */
    public enum Relation {
        /** Return this if the cell is fully contained by the query */
        CELL_INSIDE_QUERY,
        /** Return this if the cell and query do not overlap */
        CELL_OUTSIDE_QUERY,
        /** Return this if the cell partially overlaps the query */
        CELL_CROSSES_QUERY
    };

    /** We recurse the BKD tree, using a provided instance of this to guide the recursion.
     *
     * @lucene.experimental */
    public interface IntersectVisitor {
        /** Called for all documents in a leaf cell that's fully contained by the query.  The
         *  consumer should blindly accept the docID. */
        void visit(int docID) throws IOException;

        /** Called for all documents in a leaf cell that crosses the query.  The consumer
         *  should scrutinize the packedValue to decide whether to accept it.  In the 1D case,
         *  values are visited in increasing order, and in the case of ties, in increasing
         *  docID order. */
        void visit(int docID, byte[] packedValue) throws IOException;

        /** Called for non-leaf cells to test how the cell relates to the query, to
         *  determine how to further recurse down the tree. */
        Relation compare(byte[] minPackedValue, byte[] maxPackedValue);

        /** Notifies the caller that this many documents are about to be visited */
        default void grow(int count) {};
    }

    /** Finds all documents and points matching the provided visitor.
     *  This method does not enforce live documents, so it's up to the caller
     *  to test whether each document is deleted, if necessary. */
    public abstract void intersect(IntersectVisitor visitor) throws IOException;

    /** Returns how many dimensions were indexed */
    public abstract int getNumDimensions() throws IOException;

    /** Returns the number of bytes per dimension */
    public abstract int getBytesPerDimension() throws IOException;

    /** Returns the total number of indexed points across all documents. */
    public abstract long size();

    /** Returns the total number of documents that have indexed at least one point. */
    public abstract int getDocCount();

}