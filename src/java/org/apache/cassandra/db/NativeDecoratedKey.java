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
package org.apache.cassandra.db;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.apache.cassandra.dht.Token;
import org.apache.cassandra.utils.UnsafeByteBufferAccess;
import org.apache.cassandra.utils.UnsafeMemoryAccess;
import org.apache.cassandra.utils.UnsafeCopy;
import org.apache.cassandra.utils.memory.NativeAllocator;

public class NativeDecoratedKey extends DecoratedKey
{
    final long peer;

    public NativeDecoratedKey(Token token, NativeAllocator allocator, ByteBuffer key)
    {
        super(token);
        assert key != null;
        assert key.order() == ByteOrder.BIG_ENDIAN;

        int size = key.remaining();
        this.peer = allocator.allocate(4 + size);
        UnsafeMemoryAccess.setInt(peer, size);
        UnsafeCopy.copyBufferToMemory(peer + 4, key);
    }

    public ByteBuffer getKey()
    {
        return UnsafeByteBufferAccess.getByteBuffer(peer + 4, UnsafeMemoryAccess.getInt(peer), ByteOrder.BIG_ENDIAN);
    }
}
