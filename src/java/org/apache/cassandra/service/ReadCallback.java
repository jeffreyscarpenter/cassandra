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
package org.apache.cassandra.service;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.stream.Collectors;

import io.reactivex.Single;
import org.apache.cassandra.db.*;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.reactivex.subjects.BehaviorSubject;
import org.apache.cassandra.concurrent.NettyRxScheduler;
import org.apache.cassandra.concurrent.Stage;
import org.apache.cassandra.concurrent.StageManager;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.partitions.PartitionIterator;
import org.apache.cassandra.exceptions.RequestFailureReason;
import org.apache.cassandra.exceptions.ReadFailureException;
import org.apache.cassandra.exceptions.ReadTimeoutException;
import org.apache.cassandra.exceptions.UnavailableException;
import org.apache.cassandra.metrics.ReadRepairMetrics;
import org.apache.cassandra.net.IAsyncCallbackWithFailure;
import org.apache.cassandra.net.MessageIn;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.tracing.TraceState;
import org.apache.cassandra.tracing.Tracing;
import org.apache.cassandra.utils.FBUtilities;


public class ReadCallback implements IAsyncCallbackWithFailure<ReadResponse>
{
    protected static final Logger logger = LoggerFactory.getLogger( ReadCallback.class );

    public final ResponseResolver resolver;
    private final long queryStartNanoTime;
    final int blockfor;
    final List<InetAddress> endpoints;
    private final ReadCommand command;
    private final ConsistencyLevel consistencyLevel;
    private volatile int received = 0;
    private volatile int failures = 0;
    private final Map<InetAddress, RequestFailureReason> failureReasonByEndpoint;

    private final Keyspace keyspace; // TODO push this into ConsistencyLevel?

    final BehaviorSubject<PartitionIterator> publishSubject = BehaviorSubject.create();
    final Single<PartitionIterator> observable;

    // the core on which a local request is scheduled, if any
    private int localCoreId = -1;

    /**
     * Constructor when response count has to be calculated and blocked for.
     */
    public ReadCallback(ResponseResolver resolver, ConsistencyLevel consistencyLevel, ReadCommand command, List<InetAddress> filteredEndpoints, long queryStartNanoTime)
    {
        this(resolver,
             consistencyLevel,
             consistencyLevel.blockFor(Keyspace.open(command.metadata().keyspace)),
             command,
             Keyspace.open(command.metadata().keyspace),
             filteredEndpoints,
             queryStartNanoTime);
    }

    public ReadCallback(ResponseResolver resolver, ConsistencyLevel consistencyLevel, int blockfor, ReadCommand command, Keyspace keyspace, List<InetAddress> endpoints, long queryStartNanoTime)
    {
        this.command = command;
        this.keyspace = keyspace;
        this.blockfor = blockfor;
        this.consistencyLevel = consistencyLevel;
        this.resolver = resolver;
        this.queryStartNanoTime = queryStartNanoTime;
        this.endpoints = endpoints;
        this.failureReasonByEndpoint = new ConcurrentHashMap<>();
        this.observable = makeObservable();
        // we don't support read repair (or rapid read protection) for range scans yet (CASSANDRA-6897)
        assert !(command instanceof PartitionRangeReadCommand) || blockfor >= endpoints.size();

        if (logger.isTraceEnabled())
            logger.trace("Blockfor is {}; setting up requests to {}", blockfor, StringUtils.join(this.endpoints, ","));
    }


    private Single<PartitionIterator> makeObservable()
    {
        return publishSubject
               //.timeout(command.getTimeout(), TimeUnit.MILLISECONDS)
               .first(EmptyIterators.partition())
               .onErrorResumeNext(exc ->
                                  {
                                      if (Tracing.isTracing())
                                      {
                                          String gotData = received > 0 ? (resolver.isDataPresent() ? " (including data)" : " (only digests)") : "";
                                          Tracing.trace("{}; received {} of {} responses{}", new Object[]{ (exc instanceof TimeoutException ? "Timed out" : "Failed"), received, blockfor, gotData });
                                      }
                                      else if (logger.isDebugEnabled())
                                      {
                                          String gotData = received > 0 ? (resolver.isDataPresent() ? " (including data)" : " (only digests)") : "";
                                          logger.debug("{}; received {} of {} responses{}", new Object[]{ (exc instanceof TimeoutException ? "Timed out" : "Failed"), received, blockfor, gotData });
                                      }

                   if (exc instanceof TimeoutException)
                   {
                       if (NettyRxScheduler.isValidCoreId(localCoreId))
                       {
                           logger.info("Local request was running on core {}, thread stack:\n{}",
                                       localCoreId,
                                       Arrays.stream(NettyRxScheduler.getForCore(localCoreId).cpuThread.getStackTrace())
                                             .map(e -> String.format("\tat %s\n", e))
                                             .collect(Collectors.toList()));
                       }
                       else
                       {
                           logger.debug("Local request was running on unassigned or invalid core ({})", localCoreId);
                       }

                       return Single.error(new ReadTimeoutException(consistencyLevel, received, blockfor, resolver.isDataPresent()));
                   }

                                      return Single.error(exc);
                                  });
    }

    /**
     * Store the information of which core a local request is running on, if any. This is helpful
     * for debugging local query timeouts.
     *
     * @param localCoreId - the core id of the local read request, null if running on a scheduler with unassinged core
     */
    public void setLocalCoreId(int localCoreId)
    {
        this.localCoreId = localCoreId;
    }

    public Single<PartitionIterator> get()
    {
        return observable;
    }

    public boolean hasValue()
    {
        return publishSubject.hasValue();
    }

    public int blockFor()
    {
        return blockfor;
    }

    public void response(MessageIn<ReadResponse> message)
    {
        resolver.preprocess(message);
        int n = waitingFor(message.from)
              ? ++received
              : received;

        if (n >= blockfor && resolver.isDataPresent())
        {
            PartitionIterator result;

            try
            {
                result = blockfor == 1 ? resolver.getData() : resolver.resolve();
            }
            catch (DigestMismatchException e)
            {
                publishSubject.onError(e);
                return;
            }

            publishSubject.onNext(result);

            if (logger.isTraceEnabled())
                logger.trace("Read: {} ms.", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - queryStartNanoTime));

            // kick off a background digest comparison if this is a result that (may have) arrived after
            // the original resolve that get() kicks off as soon as the condition is signaled
            if (blockfor < endpoints.size() && n == endpoints.size())
            {
                TraceState traceState = Tracing.instance.get();
                if (traceState != null)
                    traceState.trace("Initiating read-repair");
                StageManager.getStage(Stage.READ_REPAIR).execute(new AsyncRepairRunner(traceState, queryStartNanoTime));
            }
        }
    }

    /**
     * @return true if the message counts towards the blockfor threshold
     */
    private boolean waitingFor(InetAddress from)
    {
        return consistencyLevel.isDatacenterLocal()
             ? DatabaseDescriptor.getLocalDataCenter().equals(DatabaseDescriptor.getEndpointSnitch().getDatacenter(from))
             : true;
    }

    /**
     * @return the current number of received responses
     */
    public int getReceivedCount()
    {
        return received;
    }

    public void response(ReadResponse result)
    {
        MessageIn<ReadResponse> message = MessageIn.create(FBUtilities.getBroadcastAddress(),
                                                           result,
                                                           Collections.<String, byte[]>emptyMap(),
                                                           MessagingService.Verb.INTERNAL_RESPONSE,
                                                           MessagingService.current_version);
        response(message);
    }

    public void assureSufficientLiveNodes() throws UnavailableException
    {
        consistencyLevel.assureSufficientLiveNodes(keyspace, endpoints);
    }

    public boolean isLatencyForSnitch()
    {
        return true;
    }

    private class AsyncRepairRunner implements Runnable
    {
        private final TraceState traceState;
        private final long queryStartNanoTime;

        public AsyncRepairRunner(TraceState traceState, long queryStartNanoTime)
        {
            this.traceState = traceState;
            this.queryStartNanoTime = queryStartNanoTime;
        }

        public void run()
        {
            // If the resolver is a DigestResolver, we need to do a full data read if there is a mismatch.
            // Otherwise, resolve will send the repairs directly if needs be (and in that case we should never
            // get a digest mismatch).
            try
            {
                resolver.compareResponses();
            }
            catch (DigestMismatchException e)
            {
                assert resolver instanceof DigestResolver;

                if (traceState != null)
                    traceState.trace("Digest mismatch: {}", e.toString());
                if (logger.isDebugEnabled())
                    logger.debug("Digest mismatch:", e);

                ReadRepairMetrics.repairedBackground.mark();

                final DataResolver repairResolver = new DataResolver(keyspace, command, consistencyLevel, endpoints.size(), queryStartNanoTime);
                AsyncRepairCallback repairHandler = new AsyncRepairCallback(repairResolver, endpoints.size());

                for (InetAddress endpoint : endpoints)
                    MessagingService.instance().sendRR(command.createMessage(), endpoint, repairHandler);
            }
        }
    }

    @Override
    public void onFailure(InetAddress from, RequestFailureReason failureReason)
    {
        int n = waitingFor(from)
              ? ++failures
              : failures;

        failureReasonByEndpoint.put(from, failureReason);

        if (blockfor + n > endpoints.size())
            publishSubject.onError(new ReadFailureException(consistencyLevel, received, blockfor, resolver.isDataPresent(), failureReasonByEndpoint));
    }
}
