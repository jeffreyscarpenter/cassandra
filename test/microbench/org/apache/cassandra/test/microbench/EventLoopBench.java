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

package org.apache.cassandra.test.microbench;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.MultithreadEventExecutorGroup;
import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;
import org.apache.cassandra.concurrent.MonitoredEpollEventLoopGroup;
import org.apache.cassandra.concurrent.NettyRxScheduler;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;


/**
 * Benchmark for eventloops
 */
@BenchmarkMode(Mode.AverageTime)
@Warmup(iterations = 5)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 1)
//, jvmArgsAppend = {//"-Djmh.executor=CUSTOM", "-Djmh.executor.class=org.apache.cassandra.test.microbench.FastThreadExecutor"
//                   "-XX:+UnlockCommercialFeatures", "-XX:+FlightRecorder","-XX:+UnlockDiagnosticVMOptions", "-XX:+DebugNonSafepoints",
//                    "-XX:StartFlightRecording=duration=60s,filename=./profiling-data.jfr,name=profile,settings=profile",
//                    "-XX:FlightRecorderOptions=settings=/home/jake/workspace/cassandra/profiling-advanced.jfc,samplethreads=true"
//})
@State(Scope.Thread)
public class EventLoopBench {


    @State(Scope.Thread)
    public static class NettyExecutorState {

        @Param({"1000000"})
        public int count;

        private MultithreadEventExecutorGroup loops;

        Observable<Integer> rx2;


        @Setup
        public void setup() throws InterruptedException
        {
            Integer[] arr = new Integer[count];
            Arrays.fill(arr, 777);

            loops = new EpollEventLoopGroup(2, new NettyRxScheduler.NettyRxThreadFactory("eventLoopBench", Thread.MAX_PRIORITY));
            if (!Epoll.isAvailable())
                throw new RuntimeException("Epoll Not available");

            ((EpollEventLoopGroup)loops).setIoRatio(100);

            EventExecutor loop1 = loops.next();
            CountDownLatch latch = new CountDownLatch(2);
            loop1.submit(() -> {
                NettyRxScheduler.register(loop1, 0);
                latch.countDown();
            });

            EventExecutor loop2 = loops.next();
            loop2.submit(() -> {
                NettyRxScheduler.register(loop2, 1);
                latch.countDown();
            });

            latch.await();

            //rx1 = Observable.fromArray(arr).subscribeOn(Schedulers.computation()).observeOn(Schedulers.computation());
            rx2 = Observable.fromArray(arr).subscribeOn(NettyRxScheduler.getForCore(0)).observeOn(NettyRxScheduler.getForCore(1));
        }

        @TearDown
        public void teardown() {
            loops.shutdown();
        }
    }

    @State(Scope.Thread)
    public static class ExecutorState {

        @Param({"1000000"})
        public int count;

        private MultithreadEventExecutorGroup loops;

        Observable<Integer> rx1;
        Observable<Integer> rx2;


        @Setup
        public void setup() throws InterruptedException
        {
            Integer[] arr = new Integer[count];
            Arrays.fill(arr, 777);

            loops = new MonitoredEpollEventLoopGroup(2);
            if (!Epoll.isAvailable())
                throw new RuntimeException("Epoll Not available");

            EventExecutor loop1 = loops.next();
            CountDownLatch latch = new CountDownLatch(2);
            loop1.submit(() -> {
                NettyRxScheduler.register(loop1, 0);
                latch.countDown();
            });

            EventExecutor loop2 = loops.next();
            loop2.submit(() -> {
                NettyRxScheduler.register(loop2, 1);
                latch.countDown();
            });

            latch.await();

            //rx1 = Observable.fromArray(arr).subscribeOn(Schedulers.computation()).observeOn(Schedulers.computation());
            rx2 = Observable.fromArray(arr).subscribeOn(NettyRxScheduler.getForCore(0)).observeOn(NettyRxScheduler.getForCore(1));
        }

        @TearDown
        public void teardown() {
            loops.shutdown();
        }
    }

    static void await(int count, CountDownLatch latch) throws Exception {
        if (count < 1000) {
            while (latch.getCount() != 0) ;
        } else {
            latch.await();
        }
    }

    @Benchmark
    public void rxDefault(ExecutorState state, Blackhole bh) throws Exception {
        LatchedObserver<Integer> o = new LatchedObserver<>(bh);
        state.rx1.subscribe(o);

        await(state.count, o.latch);
    }

    @Benchmark
    public void rxNettyNew(ExecutorState state, Blackhole bh) throws Exception {
        LatchedObserver<Integer> o = new LatchedObserver<>(bh);

        state.rx2.subscribe(o);

        await(state.count, o.latch);
    }

    @Benchmark
    public void rxNettyOld(NettyExecutorState state, Blackhole bh) throws Exception {
        LatchedObserver<Integer> o = new LatchedObserver<>(bh);

        state.rx2.subscribe(o);

        await(state.count, o.latch);
    }



    public class LatchedObserver<T> implements Observer<T>
    {
        public CountDownLatch latch = new CountDownLatch(1);
        private final Blackhole bh;
        private Disposable disposable;

        public LatchedObserver(Blackhole bh) {
            this.bh = bh;
        }

        @Override
        public void onComplete() {
            latch.countDown();
            disposable.dispose();
        }

        @Override
        public void onError(Throwable e) {
            latch.countDown();
        }

        public void onSubscribe(Disposable disposable)
        {
            this.disposable = disposable;
        }

        @Override
        public void onNext(T t)
        {
            bh.consume(t);
        }
    }
}
