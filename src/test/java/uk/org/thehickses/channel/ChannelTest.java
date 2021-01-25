package uk.org.thehickses.channel;
import static org.junit.jupiter.params.provider.Arguments.*;
import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class ChannelTest
{
    static Stream<Arguments> testChannel()
    {
        return Stream.of(arguments(1), arguments(90));
    }

    @ParameterizedTest
    @MethodSource
    void testChannel(int supplierThreads)
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
    
    @Test
    void testGetNonBlockingChannelOpenAndEmpty()
    {
        Channel<Void> ch = new Channel<>(1);
        assertThat(ch.getNonBlocking()).isNull();
    }

    @Test
    void testGetNonBlockingChannelOpenAndNotEmpty()
    {
        Channel<Void> ch = new Channel<>(1);
        ch.put(null);
        assertThat(ch.getNonBlocking().containsValue).isTrue();
    }

    @Test
    void testGetNonBlockingChannelClosedAndEmpty()
    {
        Channel<Void> ch = new Channel<>(1);
        ch.close();
        assertThat(ch.getNonBlocking().containsValue).isFalse();
    }

    @Test
    void testGetNonBlockingChannelClosedAndNotEmpty()
    {
        Channel<Void> ch = new Channel<>(1);
        ch.put(null);
        ch.close();
        assertThat(ch.getNonBlocking().containsValue).isTrue();
    }
}
