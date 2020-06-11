/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.cassandra.index.sai.disk.v1;

import java.io.Closeable;
import java.io.IOException;
import javax.annotation.concurrent.NotThreadSafe;

import org.apache.commons.lang3.mutable.MutableLong;

import org.apache.cassandra.index.sai.disk.io.IndexComponents;
import org.apache.cassandra.index.sai.disk.io.IndexOutputWriter;
import org.apache.cassandra.io.tries.IncrementalTrieWriter;
import org.apache.cassandra.utils.ByteComparable;

import static org.apache.cassandra.index.sai.utils.SAICodecUtils.writeFooter;
import static org.apache.cassandra.index.sai.utils.SAICodecUtils.writeHeader;

/**
 * Writes terms dictionary to disk in a trie format (see {@link IncrementalTrieWriter}.
 *
 * Allows for variable-length keys. Trie values are 64-bit offsets to the posting file, pointing to the beginning of
 * summary block for that postings list.
 */
@NotThreadSafe
public class TrieTermsDictionaryWriter implements Closeable
{
    private final IncrementalTrieWriter<Long> termsDictionaryWriter;
    private final IndexOutputWriter termDictionaryOutput;
    private final long startOffset;

    TrieTermsDictionaryWriter(IndexComponents indexComponents) throws IOException
    {
        termDictionaryOutput = indexComponents.createOutput(indexComponents.termsData, true);
        startOffset = termDictionaryOutput.getFilePointer();

        writeHeader(termDictionaryOutput);
        // we pass the output as SequentialWriter, but we keep IndexOutputWriter around to write footer on flush
        termsDictionaryWriter = IncrementalTrieWriter.open(TrieTermsDictionaryReader.trieSerializer, termDictionaryOutput.asSequentialWriter());
    }

    public void add(ByteComparable term, long postingListOffset) throws IOException
    {
        termsDictionaryWriter.add(term, postingListOffset);
    }

    @Override
    public void close() throws IOException
    {
        termsDictionaryWriter.close();
        termDictionaryOutput.close();
    }

    /**
     * complete trie index and write footer
     *
     * @return the position in the file of the root node.
     */
    public long complete(MutableLong footerPointer) throws IOException
    {
        long root = termsDictionaryWriter.complete();

        footerPointer.setValue(termDictionaryOutput.getFilePointer());
        writeFooter(termDictionaryOutput);
        return root;
    }

    /**
     * @return current file pointer
     */
    public long getFilePointer()
    {
        return termDictionaryOutput.getFilePointer();
    }

    /**
     * @return file pointer where index structure begins
     */
    public long getStartOffset()
    {
        return startOffset;
    }
}
