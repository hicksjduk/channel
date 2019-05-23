package uk.org.thehickses.channel;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Test;

@SuppressWarnings("unchecked")
public class SelectTest
{
    private Channel<Integer> ch1 = new Channel<>();
    private Channel<Boolean> ch2 = new Channel<>();
    private Channel<String> ch3 = new Channel<>();
    private Consumer<Integer> m1 = mock(Consumer.class);
    private Consumer<Boolean> m2 = mock(Consumer.class);
    private Consumer<String> m3 = mock(Consumer.class);

    @After
    public void tearDown()
    {
        verifyNoMoreInteractions(m1, m2, m3);
    }

    @Test
    public void testWithSinglePut1()
    {
        testWithSinglePut(ch1, m1, 42);
    }

    @Test
    public void testWithSinglePut2()
    {
        testWithSinglePut(ch2, m2, true);
    }

    @Test
    public void testWithSinglePut3()
    {
        testWithSinglePut(ch3, m3, "Hello");
    }

    private <T> void testWithSinglePut(Channel<T> channel, Consumer<T> receiver, T value)
    {
        runAsync(() -> channel.put(value));
        assertThat(Select.withCase(ch1, m1).withCase(ch2, m2).withCase(ch3, m3).run()).isTrue();
        verify(receiver).accept(value);
    }

    @Test
    public void testWithMultiplePut()
    {
        runAsync(() -> {
            ch3.put("Bonjour");
            ch1.put(981);
            ch2.put(false);
            ch3.put("Hej");
        });
        assertThat(Select.withCase(ch1, m1).withCase(ch2, m2).withCase(ch3, m3).run()).isTrue();
        verify(m3).accept("Bonjour");
        assertThat(ch1.get().value).isEqualTo(981);
        assertThat(ch2.get().value).isEqualTo(false);
        assertThat(ch3.get().value).isEqualTo("Hej");
    }

    @Test
    public void testWithMultiplePutAndGet()
    {
        var valueCount = new Channel<Integer>(1);
        runAsync(() -> {
            valueCount.put(4);
            ch3.put("Bonjour");
            ch1.put(981);
            ch2.put(false);
            ch3.put("Hej");
        });
        var select = Select.withCase(ch1, m1).withCase(ch2, m2).withCase(ch3, m3);
        for (int count = valueCount.get().value; count > 0; count += valueCount.get().value)
        {
            assertThat(select.run()).isTrue();
            valueCount.put(-1);
        }
        verify(m1).accept(981);
        verify(m2).accept(false);
        verify(m3).accept("Bonjour");
        verify(m3).accept("Hej");
    }

    @Test
    public void testWithDefault()
    {
        var m4 = mock(Runnable.class);
        assertThat(
                Select.withCase(ch1, m1).withCase(ch2, m2).withCase(ch3, m3).withDefault(m4).run())
                        .isTrue();
        verify(m4).run();
        verifyNoMoreInteractions(m4);
    }

    @Test
    public void testAllClosedNoDefault()
    {
        Stream.of(ch1, ch2, ch3).forEach(Channel::close);
        assertThat(Select.withCase(ch1, m1).withCase(ch2, m2).withCase(ch3, m3).run()).isFalse();
    }

    @Test
    public void testWithDefaultAndTwoPuts()
    {
        ch2 = new Channel<>(1);
        ch3 = new Channel<>(1);
        ch2.put(false);
        ch3.put("Hello");
        var m4 = mock(Runnable.class);
        assertThat(
                Select.withCase(ch1, m1).withCase(ch2, m2).withCase(ch3, m3).withDefault(m4).run())
                        .isTrue();
        verify(m2).accept(false);
        verifyNoMoreInteractions(m4);
    }

    @Test
    public void testAllClosedWithDefault()
    {
        var m4 = mock(Runnable.class);
        Stream.of(ch1, ch2, ch3).forEach(Channel::close);
        assertThat(
                Select.withCase(ch1, m1).withCase(ch2, m2).withCase(ch3, m3).withDefault(m4).run())
                        .isFalse();
        verifyNoMoreInteractions(m4);
    }

    @Test
    public void testAllButOneClosedWithDefault()
    {
        var m4 = mock(Runnable.class);
        Stream.of(ch1, ch3).forEach(Channel::close);
        assertThat(
                Select.withCase(ch1, m1).withCase(ch2, m2).withCase(ch3, m3).withDefault(m4).run())
                        .isTrue();
        verify(m4).run();
        verifyNoMoreInteractions(m4);
    }

    private void runAsync(Runnable r)
    {
        ForkJoinPool.commonPool().execute(r);
    }
}
