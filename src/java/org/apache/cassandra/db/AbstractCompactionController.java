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

import java.util.function.LongPredicate;

import org.apache.cassandra.db.compaction.CompactionRealm;
import org.apache.cassandra.db.rows.UnfilteredRowIterator;
import org.apache.cassandra.schema.CompactionParams;

/**
 * AbstractCompactionController allows custom implementations of the CompactionController for use in tooling, without being tied to the SSTableReader and local filesystem
 */
public abstract class AbstractCompactionController implements AutoCloseable
{
    public final CompactionRealm realm;
    public final int gcBefore;
    public final CompactionParams.TombstoneOption tombstoneOption;

    protected AbstractCompactionController(final CompactionRealm realm, final int gcBefore, CompactionParams.TombstoneOption tombstoneOption)
    {
        assert realm != null;
        this.realm = realm;
        this.gcBefore = gcBefore;
        this.tombstoneOption = tombstoneOption;
    }

    public abstract boolean compactingRepaired();

    public Iterable<UnfilteredRowIterator> shadowSources(DecoratedKey key, boolean tombstoneOnly)
    {
        return null;
    }

    public abstract LongPredicate getPurgeEvaluator(DecoratedKey key);
}