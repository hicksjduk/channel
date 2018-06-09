package uk.org.thehickses.channel;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.function.Consumer;

import org.junit.After;
import org.junit.Test;

import uk.org.thehickses.channel.Select.Selecter;

@SuppressWarnings("unchecked")
public class SelectTest
{
    private final Channel<Integer> ch1 = new Channel<>();
    private final Channel<Boolean> ch2 = new Channel<>();
    private final Channel<String> ch3 = new Channel<>();
    private final Consumer<Integer> m1 = mock(Consumer.class);
    private final Consumer<Boolean> m2 = mock(Consumer.class);
    private final Consumer<String> m3 = mock(Consumer.class);

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
        new Thread(() -> channel.put(value)).start();
        Select.withCase(ch1, m1).withCase(ch2, m2).withCase(ch3, m3).run();
        verify(receiver).accept(value);
    }

    @Test
    public void testWithMultiplePut()
    {
        new Thread(() -> {
            ch3.put("Bonjour");
            ch1.put(981);
            ch2.put(false);
            ch3.put("Hej");
        }).start();
        Select.withCase(ch1, m1).withCase(ch2, m2).withCase(ch3, m3).run();
        verify(m3).accept("Bonjour");
        assertThat(ch1.get().value).isEqualTo(981);
        assertThat(ch2.get().value).isEqualTo(false);
        assertThat(ch3.get().value).isEqualTo("Hej");
    }

    @Test
    public void testWithMultiplePutAndGet()
    {
        Channel<Integer> valueCount = new Channel<>(1);
        new Thread(() -> {
            valueCount.put(4);
            ch3.put("Bonjour");
            ch1.put(981);
            ch2.put(false);
            ch3.put("Hej");
        }).start();
        Selecter select = Select.withCase(ch1, m1).withCase(ch2, m2).withCase(ch3, m3);
        for (int count = valueCount.get().value; count > 0; count += valueCount.get().value)
        {
            select.run();
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
        Runnable m4 = mock(Runnable.class);
        Select.withCase(ch1, m1).withCase(ch2, m2).withCase(ch3, m3).withDefault(m4).run();
        verify(m4).run();
        verifyNoMoreInteractions(m4);
    }
}
