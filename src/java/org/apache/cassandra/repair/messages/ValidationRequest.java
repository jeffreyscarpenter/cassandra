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
package org.apache.cassandra.repair.messages;

import java.io.IOException;
import java.util.Optional;

import org.apache.cassandra.db.TypeSizes;
import org.apache.cassandra.io.util.DataInputPlus;
import org.apache.cassandra.io.util.DataOutputPlus;
import org.apache.cassandra.net.Verbs;
import org.apache.cassandra.net.Verb;
import org.apache.cassandra.repair.RepairJobDesc;
import org.apache.cassandra.repair.messages.RepairVerbs.RepairVersion;
import org.apache.cassandra.utils.versioning.Versioned;

/**
 * ValidationRequest
 *
 * @since 2.0
 */
public class ValidationRequest extends RepairMessage<ValidationRequest>
{
    public static Versioned<RepairVersion, MessageSerializer<ValidationRequest>> serializers = RepairVersion.versioned(v -> new MessageSerializer<ValidationRequest>(v)
    {
        public void serialize(ValidationRequest message, DataOutputPlus out) throws IOException
        {
            RepairJobDesc.serializers.get(version).serialize(message.desc, out);
            out.writeInt(message.nowInSec);
        }

        public ValidationRequest deserialize(DataInputPlus dis) throws IOException
        {
            RepairJobDesc desc = RepairJobDesc.serializers.get(version).deserialize(dis);
            return new ValidationRequest(desc, dis.readInt());
        }

        public long serializedSize(ValidationRequest message)
        {
            return RepairJobDesc.serializers.get(version).serializedSize(message.desc)
                   + TypeSizes.sizeof(message.nowInSec);
        }
    });

    public final int nowInSec;

    public ValidationRequest(RepairJobDesc desc, int nowInSec)
    {
        super(desc);
        this.nowInSec = nowInSec;
    }

    @Override
    public String toString()
    {
        return "ValidationRequest{" +
               "nowInSec=" + nowInSec +
               "} " + super.toString();
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ValidationRequest that = (ValidationRequest) o;
        return nowInSec == that.nowInSec;
    }

    @Override
    public int hashCode()
    {
        return nowInSec;
    }

    public MessageSerializer<ValidationRequest> serializer(RepairVersion version)
    {
        return serializers.get(version);
    }

    public Optional<Verb<ValidationRequest, ?>> verb()
    {
        return Optional.of(Verbs.REPAIR.VALIDATION_REQUEST);
    }
}
