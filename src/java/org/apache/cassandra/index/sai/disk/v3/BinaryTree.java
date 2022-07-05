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

package org.apache.cassandra.index.sai.disk.v3;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.agrona.collections.IntArrayList;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.store.ByteBuffersDataOutput;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefArray;
import org.apache.lucene.util.BytesRefBuilder;
import org.apache.lucene.util.MathUtil;

public class BinaryTree
{
    public interface IntersectVisitor
    {
        /** Called for non-leaf cells to test how the cell relates to the query, to
         *  determine how to further recurse down the tree. */
        PointValues.Relation compare(BytesRef minPackedValue, BytesRef maxPackedValue);

        default boolean visit(BytesRef value)
        {
            return compare(value, value) == PointValues.Relation.CELL_INSIDE_QUERY;
        }
    }

    public interface BinaryTreeTraversalCallback
    {
        void onLeaf(int leafNodeID, IntArrayList pathToRoot);
    }

    public static class Reader implements Closeable
    {
        final IndexInput input;
        private final int[] rightNodePositions;
        final int leafNodeOffset;
        // total number of points
        final long pointCount;
        // last node might not be fully populated
        private final int lastLeafNodePointCount;
        // right most leaf node ID
        private final int rightMostLeafNode;
        // during clone, the node root can be different to 1
        private final int nodeRoot;
        // level is 1-based so that we can do level-1 w/o checking each time:
        protected int level;
        protected int nodeID;

        private final BytesRef[] splitPackedValueStack;
        private final BytesRef[] splitValuesStack;

        public Reader(int numLeaves, long pointCount, int maxPointsPerLeaf, IndexInput input) throws IOException
        {
            this.pointCount = pointCount;
            this.input = input;

            this.leafNodeOffset = numLeaves;
            this.nodeRoot = 1;
            this.nodeID = 1;
            this.level = 1;

            final int treeDepth = getTreeDepth(numLeaves);

            this.splitPackedValueStack = new BytesRef[treeDepth + 1];
            this.splitValuesStack = new BytesRef[treeDepth + 1];
            this.rightNodePositions = new int[treeDepth + 1];

            this.rightMostLeafNode = (1 << treeDepth - 1) - 1;
            int lastLeafNodePointCount = Math.toIntExact(pointCount % maxPointsPerLeaf);
            this.lastLeafNodePointCount = lastLeafNodePointCount == 0 ? maxPointsPerLeaf : lastLeafNodePointCount;

            readNodeData(false);
        }

        /**
         * Change the node id to 0..N leaf block id.
         */
        public int getBlockID()
        {
            return nodeID - leafNodeOffset;
        }

        @Override
        public void close() throws IOException
        {
            FileUtils.closeQuietly(input);
        }

        public static void copyBytes(BytesRef source, BytesRef dest)
        {
            dest.bytes = ArrayUtil.grow(dest.bytes, source.length + dest.offset);
            dest.length = source.length;
            System.arraycopy(source.bytes, source.offset, dest.bytes, dest.offset, source.length);
        }

//        public void intersect(IntersectVisitor visitor,
//                              BytesRef cellMinPacked,
//                              BytesRef cellMaxPacked) throws IOException
//        {
//            PointValues.Relation r = visitor.compare(cellMinPacked, cellMaxPacked);
//
//            System.out.println("relation=" + r + " nodeID=" + nodeID);
//
//            if (r == PointValues.Relation.CELL_OUTSIDE_QUERY)
//            {
//                // This cell is fully outside of the query shape: stop recursing
//            }
//            else if (r == PointValues.Relation.CELL_INSIDE_QUERY)
//            {
//                // This cell is fully inside of the query shape: recursively add all points in this cell without filtering
//                //addAll(state, false);
//            }
//            else if (isLeafNode())
//            {
//                // In the unbalanced case it's possible the left most node only has one child:
//                if (nodeExists())
//                {
//                    System.out.println("onLeaf nodeID=" + getNodeID());
//                }
//            }
//            else
//            {
////                // Non-leaf node: recurse on the split left and right nodes
////                int splitDim = state.index.getSplitDim();
////                assert splitDim >= 0 : "splitDim=" + splitDim + ", config.numIndexDims=" + config.numIndexDims;
////                assert splitDim < config.numIndexDims : "splitDim=" + splitDim + ", config.numIndexDims=" + config.numIndexDims;
//
//                BytesRef splitPackedValue = getSplitPackedValue();
//                BytesRef splitDimValue = getSplitDimValue().clone();
//
//                // make sure cellMin <= splitValue <= cellMax:
////                assert FutureArrays.compareUnsigned(cellMinPacked, splitDim * config.bytesPerDim, splitDim * config.bytesPerDim + config.bytesPerDim, splitDimValue.bytes, splitDimValue.offset, splitDimValue.offset + config.bytesPerDim) <= 0 : "config.bytesPerDim=" + config.bytesPerDim + " splitDim=" + splitDim + " config.numIndexDims=" + config.numIndexDims + " config.numDims=" + config.numDims;
////                assert FutureArrays.compareUnsigned(cellMaxPacked, splitDim * config.bytesPerDim, splitDim * config.bytesPerDim + config.bytesPerDim, splitDimValue.bytes, splitDimValue.offset, splitDimValue.offset + config.bytesPerDim) >= 0 : "config.bytesPerDim=" + config.bytesPerDim + " splitDim=" + splitDim + " config.numIndexDims=" + config.numIndexDims + " config.numDims=" + config.numDims;
//
//                // Recurse on left sub-tree:
//                copyBytes(cellMaxPacked, splitPackedValue);
//                copyBytes(splitDimValue, splitPackedValue);
//                // System.arraycopy(cellMaxPacked, 0, splitPackedValue, 0, config.packedIndexBytesLength);
//                // System.arraycopy(splitDimValue.bytes, splitDimValue.offset, splitPackedValue, splitDim * config.bytesPerDim, config.bytesPerDim);
//
//                pushLeft();
//                intersect(visitor, cellMinPacked, splitPackedValue);
//                pop();
//
//                // Restore the split dim value since it may have been overwritten while recursing:
//                copyBytes(splitPackedValue, splitDimValue);
//                // System.arraycopy(splitPackedValue, splitDim * config.bytesPerDim, splitDimValue.bytes, splitDimValue.offset, config.bytesPerDim);
//
//                // Recurse on right sub-tree:
//                copyBytes(cellMinPacked, splitPackedValue);
//                copyBytes(splitDimValue, splitPackedValue);
//                // System.arraycopy(cellMinPacked, 0, splitPackedValue, 0, config.packedIndexBytesLength);
//                // System.arraycopy(splitDimValue.bytes, splitDimValue.offset, splitPackedValue, splitDim * config.bytesPerDim, config.bytesPerDim);
//
//                pushRight();
//                intersect(visitor, splitPackedValue, cellMaxPacked);
//                pop();
//            }
//        }

        public BytesRef getSplitDimValue()
        {
            assert isLeafNode() == false;
            return splitValuesStack[level];
        }

        // it seems this is used as a buffer stack to copy into during traversal
        public BytesRef getSplitPackedValue()
        {
            assert isLeafNode() == false;
            assert splitPackedValueStack[level] != null: "level=" + level;
            return splitPackedValueStack[level];
        }

        public void traverse(IntArrayList pathToRoot,
                             BinaryTreeTraversalCallback callback) throws IOException
        {
            final int nodeID = getNodeID();
            System.out.println("traverse nodeID="+nodeID+" isLeafNode="+isLeafNode());
            if (isLeafNode())
            {
                // In the unbalanced case it's possible the left most node only has one child:
                if (nodeExists())
                {
                    System.out.println("onLeaf nodeID="+getNodeID());
                    callback.onLeaf(nodeID, pathToRoot);
                }
            }
            else
            {
                final IntArrayList currentPath = new IntArrayList();
                currentPath.addAll(pathToRoot);
                currentPath.add(nodeID);

                pushLeft();
                traverse(currentPath, callback);
                pop();

                pushRight();
                traverse(currentPath, callback);
                pop();
            }
        }

        public void pop()
        {
            nodeID /= 2;
            level--;
            //System.out.println("  pop nodeID=" + nodeID);
        }

        public boolean isLeafNode()
        {
            return nodeID >= leafNodeOffset;
        }

        public boolean nodeExists()
        {
            return nodeID - leafNodeOffset < leafNodeOffset;
        }

        public int getNodeID()
        {
            return nodeID;
        }

        public void pushRight() throws IOException
        {
            System.out.println("pushRight level="+level);
            final int nodePosition = rightNodePositions[level];
            assert nodePosition >= input.getFilePointer() : "nodePosition = " + nodePosition + " < currentPosition=" + input.getFilePointer();
            nodeID = nodeID * 2 + 1;
            level++;
            input.seek(nodePosition);
            readNodeData(false);
        }

        public void pushLeft() throws IOException
        {
            System.out.println("pushLeft level="+level);
            nodeID *= 2;
            level++;
            readNodeData(true);
        }

        private static int getTreeDepth(int numLeaves)
        {
            // First +1 because all the non-leave nodes makes another power
            // of 2; e.g. to have a fully balanced tree with 4 leaves you
            // need a depth=3 tree:

            // Second +1 because MathUtil.log computes floor of the logarithm; e.g.
            // with 5 leaves you need a depth=4 tree:
            return MathUtil.log(numLeaves, 2) + 2;
        }

        private void readNodeData(boolean isLeft) throws IOException
        {
            if (splitPackedValueStack[level] == null)
                splitPackedValueStack[level] = new BytesRef(new byte[10]);

            System.out.println("readNodeData nodeID=" + nodeID + " isLeft=" + isLeft + " isLeafNode=" + isLeafNode() + " leafNodeOffset=" + leafNodeOffset + " fp=" + input.getFilePointer() + " level=" + level);

            if (isLeafNode())
                return;

            final int len = input.readVInt();

            if (splitValuesStack[level] == null)
                splitValuesStack[level] = new BytesRef(new byte[len]);
            else
                splitValuesStack[level].bytes = ArrayUtil.grow(splitValuesStack[level].bytes, len);

            System.out.println("  readNodeData len="+len);

            input.readBytes(splitValuesStack[level].bytes, 0, len);
            // System.out.println("  readNodeData bytes="+splitValuesStack[level].utf8ToString());

            int leftNumBytes;
            if (nodeID * 2 < leafNodeOffset)
                leftNumBytes = input.readVInt();
            else
                leftNumBytes = 0;

            System.out.println("  isLeft="+isLeft+" leftNumBytes="+leftNumBytes+" nodeID*2="+(nodeID * 2)+" leafNodeOffset="+leafNodeOffset);

            rightNodePositions[level] = Math.toIntExact(input.getFilePointer()) + leftNumBytes;

            System.out.println("  rightNodePositions=" + Arrays.toString(rightNodePositions));
        }

        // for assertions
        private int getNumLeavesSlow(int node)
        {
            if (node >= 2 * leafNodeOffset)
                return 0;
            else if (node >= leafNodeOffset)
                return 1;
            else
            {
                final int leftCount = getNumLeavesSlow(node * 2);
                final int rightCount = getNumLeavesSlow(node * 2 + 1);
                return leftCount + rightCount;
            }
        }
    }

    public static class Writer
    {
        private final BytesRefBuilder spare = new BytesRefBuilder();

        public Writer()
        {
        }

        public void finish(BytesRefArray minBlockTerms, IndexOutput output) throws IOException
        {
            ByteBuffersDataOutput writeBuffer = ByteBuffersDataOutput.newResettableInstance();
            List<byte[]> blocks = new ArrayList<>();

            int totalSize = recurseIndex(minBlockTerms, false, 0, minBlockTerms.size(), writeBuffer, blocks);

            System.out.println("finish totalSize="+totalSize);

            byte[] index = new byte[totalSize];
            int upto = 0;
            for(byte[] block : blocks) {
                System.arraycopy(block, 0, index, upto, block.length);
                upto += block.length;
            }
            output.writeBytes(index, 0, index.length);
        }

        private int appendBlock(ByteBuffersDataOutput writeBuffer, List<byte[]> blocks)
        {
            byte[] block = writeBuffer.toArrayCopy();
            blocks.add(block);
            writeBuffer.reset();
            return block.length;
        }

        private int recurseIndex(BytesRefArray minBlockTerms,
                                 boolean isLeft,
                                 int leavesOffset,
                                 int numLeaves,
                                 ByteBuffersDataOutput writeBuffer,
                                 List<byte[]> blocks) throws IOException
        {
            System.out.println("recurseIndex isLeft="+isLeft+" leavesOffset="+leavesOffset+" numLeaves="+numLeaves);
            if (numLeaves == 1)
            {
                if (isLeft)
                    return 0;
                else
                    return appendBlock(writeBuffer, blocks);
            }
            else
            {
                final int numLeftLeafNodes = getNumLeftLeafNodes(numLeaves);
                final int rightOffset = leavesOffset + numLeftLeafNodes;
                final int splitOffset = rightOffset - 1;

                System.out.println("  rightOffset="+rightOffset);

                minBlockTerms.get(spare, splitOffset);

                // System.out.println("  write min term="+spare.get().utf8ToString()+" len="+spare.get().length+" writeBuffer.size="+writeBuffer.size());

                // write bytes
                writeBuffer.writeVInt(spare.get().length);
                writeBuffer.writeBytes(spare.get().bytes, spare.get().offset, spare.get().length);

                final int numBytes = appendBlock(writeBuffer, blocks);

                final int idxSav = blocks.size();
                blocks.add(null);

                final int leftNumBytes = recurseIndex(minBlockTerms, true, leavesOffset, numLeftLeafNodes, writeBuffer, blocks);
                if (numLeftLeafNodes != 1)
                {
                    System.out.println("  leftNumBytes="+leftNumBytes);
                    writeBuffer.writeVInt(leftNumBytes);
                }
                else
                    assert leftNumBytes == 0 : "leftNumBytes=" + leftNumBytes;

                byte[] bytes2 = writeBuffer.toArrayCopy();
                writeBuffer.reset();
                // replace our placeholder:
                blocks.set(idxSav, bytes2);

                final int rightNumBytes = recurseIndex(minBlockTerms, false, rightOffset, numLeaves - numLeftLeafNodes, writeBuffer, blocks);

                return numBytes + bytes2.length + leftNumBytes + rightNumBytes;
            }
        }

        private int getNumLeftLeafNodes(int numLeaves)
        {
            assert numLeaves > 1 : "getNumLeftLeaveNodes() called with " + numLeaves;
            // return the level that can be filled with this number of leaves
            int lastFullLevel = 31 - Integer.numberOfLeadingZeros(numLeaves);
            // how many leaf nodes are in the full level
            int leavesFullLevel = 1 << lastFullLevel;
            // half of the leaf nodes from the full level goes to the left
            int numLeftLeafNodes = leavesFullLevel / 2;
            // leaf nodes that do not fit in the full level
            int unbalancedLeafNodes = numLeaves - leavesFullLevel;
            // distribute unbalanced leaf nodes
            numLeftLeafNodes += Math.min(unbalancedLeafNodes, numLeftLeafNodes);
            // we should always place unbalanced leaf nodes on the left
            assert numLeftLeafNodes >= numLeaves - numLeftLeafNodes && numLeftLeafNodes <= 2L * (numLeaves - numLeftLeafNodes);
            return numLeftLeafNodes;
        }
    }
}
