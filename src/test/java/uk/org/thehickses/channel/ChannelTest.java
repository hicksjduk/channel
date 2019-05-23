package uk.org.thehickses.channel;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.Test;

public class ChannelTest
{
    private void runAsync(Runnable r)
    {
        ForkJoinPool.commonPool().execute(r);
    }

    @Test
    public void testSingleSupplier()
    {
        testChannel(1);
    }

    @Test
    public void testMultipleSuppliers()
    {
        testChannel(90);
    }

    public void testChannel(int supplierThreads)
    {
        var channel = new Channel<Integer>();
        var putsAndGets = new Channel<Integer>(supplierThreads + 1);
        var putCount = new AtomicInteger();
        var valueCount = 2000;
        for (var i = 0; i < supplierThreads; i++)
        {
            var t = i;
            Runnable putter = () -> {
                var count = 0;
                for (int v = t; v < valueCount; v += supplierThreads)
                {
                    channel.put(v);
                    count++;
                }
                putsAndGets.put(count);
                putCount.addAndGet(count);
            };
            runAsync(putter);
        }
        var values = new ArrayList<Integer>();
        Runnable reader = () -> {
            channel.range(v -> {
                values.add(v);
                putsAndGets.put(-1);
            });
        };
        runAsync(reader);
        var outstandingValueCount = Integer.MIN_VALUE;
        while (outstandingValueCount != 0)
        {
            var result = putsAndGets.get();
            if (outstandingValueCount == Integer.MIN_VALUE)
                outstandingValueCount = result.value;
            else
                outstandingValueCount += result.value;
        }
        channel.close();
        assertThat(values.size()).isEqualTo(valueCount);
        assertThat(putCount.get()).isEqualTo(valueCount);
        Collections.sort(values);
        for (int i = 0; i < valueCount; i++)
            assertThat(values.get(i)).isEqualTo(i);
    }

    @Test
    public void testCloseWhenEmptyEmptyWhenCalled()
    {
        testCloseWhenEmpty(0);
    }

    @Test
    public void testCloseWhenEmptyNotEmptyWhenCalled()
    {
        testCloseWhenEmpty(40000);
    }

    public void testCloseWhenEmpty(int valueCount)
    {
        var ch = new Channel<Integer>(valueCount);
        for (var i = 0; i < valueCount; i++)
            ch.put(i);
        ch.closeWhenEmpty();
        var values = new HashSet<>(valueCount);
        ch.range(values::add);
        assertThat(values.size()).isEqualTo(valueCount);
    }

    @Test
    public void testDoubleClose()
    {
        var ch = new Channel<Void>();
        assertThat(ch.close()).isTrue();
        assertThat(ch.close()).isFalse();
    }

    @Test
    public void testCloseBeforePut()
    {
        var ch = new Channel<Integer>();
        ch.close();
        assertThat(ch.put(1)).isFalse();
    }

    @Test
    public void testCloseAfterPutThatTerminated()
    {
        testCloseAfterPut(1, ch -> ch.put(1), true);
    }

    @Test
    public void testCloseAfterPutThatBlocked()
    {
        testCloseAfterPut(0, ch -> ch.put(1), false);
    }

    private void testCloseAfterPut(int bufferSize, Function<Channel<Integer>, Boolean> putter,
            boolean expectedResult)
    {
        var ch = new Channel<Integer>(bufferSize);
        var done = new Channel<Void>(1);
        Runnable closer = () -> {
            try
            {
                Thread.sleep(TimeUnit.SECONDS.toMillis(1));
                ch.close();
                done.put(null);
            }
            catch (InterruptedException ex)
            {
                throw new RuntimeException(ex);
            }
        };
        runAsync(closer);
        try
        {
            assertThat(putter.apply(ch)).isEqualTo(expectedResult);
        }
        finally
        {
            done.get();
        }
    }
}
