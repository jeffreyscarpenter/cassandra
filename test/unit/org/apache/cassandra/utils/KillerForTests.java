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

package org.apache.cassandra.utils;

import org.junit.Assert;

/**
 * Responsible for stubbing out the System.exit() logic during unit tests.
 */
public class KillerForTests extends JVMStabilityInspector.Killer
{
    private boolean killed = false;
    private boolean quiet = false;
    private final boolean expected;

    public KillerForTests()
    {
        expected = true;
    }

    public KillerForTests(boolean expectFailure)
    {
        expected = expectFailure;
    }

    @Override
    public void killJVM(Throwable t, boolean quiet)
    {
        if (!expected)
            Assert.fail("Saw JVM Kill but did not expect it.");

        this.killed = true;
        this.quiet = quiet;
    }

    public boolean wasKilled()
    {
        return killed;
    }

    public boolean wasKilledQuietly()
    {
        return quiet;
    }

    public void reset()
    {
        killed = false;
    }
}
