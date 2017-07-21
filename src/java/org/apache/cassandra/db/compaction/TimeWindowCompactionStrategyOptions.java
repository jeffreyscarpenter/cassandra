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

package org.apache.cassandra.db.compaction;

import com.google.common.collect.ImmutableList;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.config.Config;
import org.apache.cassandra.exceptions.ConfigurationException;

public final class TimeWindowCompactionStrategyOptions
{
    private static final Logger logger = LoggerFactory.getLogger(TimeWindowCompactionStrategyOptions.class);

    protected static final TimeUnit DEFAULT_TIMESTAMP_RESOLUTION = TimeUnit.MICROSECONDS;
    protected static final TimeUnit DEFAULT_COMPACTION_WINDOW_UNIT = TimeUnit.DAYS;
    protected static final int DEFAULT_COMPACTION_WINDOW_SIZE = 1;
    protected static final int DEFAULT_EXPIRED_SSTABLE_CHECK_FREQUENCY_SECONDS = 60 * 10;

    protected static final String TIMESTAMP_RESOLUTION_KEY = "timestamp_resolution";
    protected static final String COMPACTION_WINDOW_UNIT_KEY = "compaction_window_unit";
    protected static final String COMPACTION_WINDOW_SIZE_KEY = "compaction_window_size";
    protected static final String EXPIRED_SSTABLE_CHECK_FREQUENCY_SECONDS_KEY = "expired_sstable_check_frequency_seconds";
    protected static final String ALLOW_UNSAFE_AGGRESSIVE_SSTABLE_EXPIRATION_TABLE_OPTION = "unsafe_aggressive_sstable_expiration";
    protected static final String ALLOW_UNSAFE_AGGRESSIVE_SSTABLE_EXPIRATION_PROPERTY = Config.PROPERTY_PREFIX + "allow_unsafe_aggressive_sstable_expiration";
    protected static final boolean ALLOW_UNSAFE_AGGRESSIVE_SSTABLE_EXPIRATION = Boolean.getBoolean(ALLOW_UNSAFE_AGGRESSIVE_SSTABLE_EXPIRATION_PROPERTY);

    protected final int sstableWindowSize;
    protected final TimeUnit sstableWindowUnit;
    protected final TimeUnit timestampResolution;
    protected final long expiredSSTableCheckFrequency;
    protected final boolean ignoreOverlaps;

    protected final static ImmutableList<TimeUnit> validTimestampTimeUnits = ImmutableList.of(TimeUnit.SECONDS, TimeUnit.MILLISECONDS, TimeUnit.MICROSECONDS, TimeUnit.NANOSECONDS);
    protected final static ImmutableList<TimeUnit> validWindowTimeUnits = ImmutableList.of(TimeUnit.MINUTES, TimeUnit.HOURS, TimeUnit.DAYS);

    SizeTieredCompactionStrategyOptions stcsOptions;

    public TimeWindowCompactionStrategyOptions(Map<String, String> options)
    {
        String optionValue = options.get(TIMESTAMP_RESOLUTION_KEY);
        timestampResolution = optionValue == null ? DEFAULT_TIMESTAMP_RESOLUTION : TimeUnit.valueOf(optionValue);

        optionValue = options.get(COMPACTION_WINDOW_UNIT_KEY);
        sstableWindowUnit = optionValue == null ? DEFAULT_COMPACTION_WINDOW_UNIT : TimeUnit.valueOf(optionValue);

        optionValue = options.get(COMPACTION_WINDOW_SIZE_KEY);
        sstableWindowSize = optionValue == null ? DEFAULT_COMPACTION_WINDOW_SIZE : Integer.parseInt(optionValue);

        optionValue = options.get(EXPIRED_SSTABLE_CHECK_FREQUENCY_SECONDS_KEY);
        expiredSSTableCheckFrequency = TimeUnit.MILLISECONDS.convert(optionValue == null ? DEFAULT_EXPIRED_SSTABLE_CHECK_FREQUENCY_SECONDS : Long.parseLong(optionValue), TimeUnit.SECONDS);

        optionValue = options.get(ALLOW_UNSAFE_AGGRESSIVE_SSTABLE_EXPIRATION_TABLE_OPTION);
        boolean aggressiveExpirationTableOption = Boolean.parseBoolean(optionValue);
        if (aggressiveExpirationTableOption && !ALLOW_UNSAFE_AGGRESSIVE_SSTABLE_EXPIRATION)
        {
            logger.warn("Not enabling agressive SSTable expiration, as the system property '" + ALLOW_UNSAFE_AGGRESSIVE_SSTABLE_EXPIRATION_PROPERTY + "' is set to 'false'. " +
                        "Set it to 'true' to enable aggressive SSTable expiration.");
        }
        ignoreOverlaps = ALLOW_UNSAFE_AGGRESSIVE_SSTABLE_EXPIRATION && aggressiveExpirationTableOption;

        stcsOptions = new SizeTieredCompactionStrategyOptions(options);
    }

    public TimeWindowCompactionStrategyOptions()
    {
        sstableWindowUnit = DEFAULT_COMPACTION_WINDOW_UNIT;
        timestampResolution = DEFAULT_TIMESTAMP_RESOLUTION;
        sstableWindowSize = DEFAULT_COMPACTION_WINDOW_SIZE;
        expiredSSTableCheckFrequency = TimeUnit.MILLISECONDS.convert(DEFAULT_EXPIRED_SSTABLE_CHECK_FREQUENCY_SECONDS, TimeUnit.SECONDS);
        ignoreOverlaps = false;
        stcsOptions = new SizeTieredCompactionStrategyOptions();
    }

    public static Map<String, String> validateOptions(Map<String, String> options, Map<String, String> uncheckedOptions) throws  ConfigurationException
    {
        String optionValue = options.get(TIMESTAMP_RESOLUTION_KEY);
        try
        {
            if (optionValue != null)
                if (!validTimestampTimeUnits.contains(TimeUnit.valueOf(optionValue)))
                    throw new ConfigurationException(String.format("%s is not valid for %s", optionValue, TIMESTAMP_RESOLUTION_KEY));
        }
        catch (IllegalArgumentException e)
        {
            throw new ConfigurationException(String.format("%s is not valid for %s", optionValue, TIMESTAMP_RESOLUTION_KEY));
        }


        optionValue = options.get(COMPACTION_WINDOW_UNIT_KEY);
        try
        {
            if (optionValue != null)
                if (!validWindowTimeUnits.contains(TimeUnit.valueOf(optionValue)))
                    throw new ConfigurationException(String.format("%s is not valid for %s", optionValue, COMPACTION_WINDOW_UNIT_KEY));

        }
        catch (IllegalArgumentException e)
        {
            throw new ConfigurationException(String.format("%s is not valid for %s", optionValue, COMPACTION_WINDOW_UNIT_KEY), e);
        }

        optionValue = options.get(COMPACTION_WINDOW_SIZE_KEY);
        try
        {
            int sstableWindowSize = optionValue == null ? DEFAULT_COMPACTION_WINDOW_SIZE : Integer.parseInt(optionValue);
            if (sstableWindowSize < 1)
            {
                throw new ConfigurationException(String.format("%d must be greater than 1 for %s", sstableWindowSize, COMPACTION_WINDOW_SIZE_KEY));
            }
        }
        catch (NumberFormatException e)
        {
            throw new ConfigurationException(String.format("%s is not a parsable int (base10) for %s", optionValue, COMPACTION_WINDOW_SIZE_KEY), e);
        }

        optionValue = options.get(EXPIRED_SSTABLE_CHECK_FREQUENCY_SECONDS_KEY);
        try
        {
            long expiredCheckFrequency = optionValue == null ? DEFAULT_EXPIRED_SSTABLE_CHECK_FREQUENCY_SECONDS : Long.parseLong(optionValue);
            if (expiredCheckFrequency < 0)
            {
                throw new ConfigurationException(String.format("%s must not be negative, but was %d", EXPIRED_SSTABLE_CHECK_FREQUENCY_SECONDS_KEY, expiredCheckFrequency));
            }
        }
        catch (NumberFormatException e)
        {
            throw new ConfigurationException(String.format("%s is not a parsable int (base10) for %s", optionValue, EXPIRED_SSTABLE_CHECK_FREQUENCY_SECONDS_KEY), e);
        }

        optionValue = options.get(ALLOW_UNSAFE_AGGRESSIVE_SSTABLE_EXPIRATION_TABLE_OPTION);
        if (optionValue != null)
        {
            if (!(optionValue.equalsIgnoreCase("true") || optionValue.equalsIgnoreCase("false")))
                throw new ConfigurationException(String.format("%s is not 'true' or 'false' (%s)", ALLOW_UNSAFE_AGGRESSIVE_SSTABLE_EXPIRATION_TABLE_OPTION, optionValue));
        }

        uncheckedOptions.remove(COMPACTION_WINDOW_SIZE_KEY);
        uncheckedOptions.remove(COMPACTION_WINDOW_UNIT_KEY);
        uncheckedOptions.remove(TIMESTAMP_RESOLUTION_KEY);
        uncheckedOptions.remove(EXPIRED_SSTABLE_CHECK_FREQUENCY_SECONDS_KEY);
        uncheckedOptions.remove(ALLOW_UNSAFE_AGGRESSIVE_SSTABLE_EXPIRATION_TABLE_OPTION);

        uncheckedOptions = SizeTieredCompactionStrategyOptions.validateOptions(options, uncheckedOptions);

        return uncheckedOptions;
    }
}
