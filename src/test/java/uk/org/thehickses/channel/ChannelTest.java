package uk.org.thehickses.channel;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

public class ChannelTest
{
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
        Channel<Integer> channel = new Channel<>();
        Channel<Integer> putsAndGets = new Channel<>(supplierThreads + 1);
        AtomicInteger putCount = new AtomicInteger();
        int valueCount = 2000;
        for (int i = 0; i < supplierThreads; i++)
        {
            int t = i;
            Runnable putter = () -> {
                int count = 0;
                for (int v = t; v < valueCount; v += supplierThreads)
                {
                    channel.put(v);
                    count++;
                }
                putsAndGets.put(count);
                putCount.addAndGet(count);
            };
            ForkJoinPool.commonPool().execute(putter);
        }
        List<Integer> values = new ArrayList<Integer>();
        Runnable reader = () -> {
            channel.range(v -> {
                values.add(v);
                putsAndGets.put(-1);
            });
        };
        ForkJoinPool.commonPool().execute(reader);
        int outstandingValueCount = Integer.MIN_VALUE;
        while (outstandingValueCount != 0)
        {
            GetResult<Integer> result = putsAndGets.get();
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
        Channel<Integer> ch = new Channel<>(valueCount);
        for (int i = 0; i < valueCount; i++)
            ch.put(i);
        ch.closeWhenEmpty();
        Set<Integer> values = new HashSet<>(valueCount);
        ch.range(values::add);
        assertThat(values.size()).isEqualTo(valueCount);
    }
    
    @Test
    public void testDoubleClose()
    {
        Channel<Void> ch = new Channel<>();
        assertThat(ch.close()).isTrue();
        assertThat(ch.close()).isFalse();
    }

    @Test
    public void testCloseBeforePut()
    {
        Channel<Integer> ch = new Channel<>();
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
        Channel<Integer> ch = new Channel<>(bufferSize);
        Channel<Void> done = new Channel<>(1);
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
        ForkJoinPool.commonPool().execute(closer);
        try
        {
            assertThat(putter.apply(ch)).isEqualTo(expectedResult);
        }
        finally
        {
            done.get();
        }
        assertThat(ch.get().containsValue).isEqualTo(expectedResult);
        assertThat(ch.get().containsValue).isFalse();
    }
}
