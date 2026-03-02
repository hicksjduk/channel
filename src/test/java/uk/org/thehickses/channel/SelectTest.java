package uk.org.thehickses.channel;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import uk.org.thehickses.channel.Select.Selecter;

@SuppressWarnings("unchecked")
public class SelectTest
{
    private Channel<Integer> channel1 = new Channel<>(5);
    private Channel<Boolean> channel2 = new Channel<>(5);
    private Channel<String> channel3 = new Channel<>(5);
    private Consumer<Integer> handler1 = mock(Consumer.class);
    private Consumer<Boolean> handler2 = mock(Consumer.class);
    private Consumer<String> handler3 = mock(Consumer.class);
    private Runnable defaultHandler = mock(Runnable.class);

    @AfterEach
    public void tearDown()
    {
        verifyNoMoreInteractions(handler1, handler2, handler3, defaultHandler);
    }

    @Test
    public void testWithSinglePut1()
    {
        testWithSinglePut(channel1, handler1, 42);
    }

    @Test
    public void testWithSinglePut2()
    {
        testWithSinglePut(channel2, handler2, true);
    }

    @Test
    public void testWithSinglePut3()
    {
        testWithSinglePut(channel3, handler3, "Hello");
    }

    private <T> void testWithSinglePut(Channel<T> channel, Consumer<T> receiver, T value)
    {
        channel.put(value);
        assertThat(Select.withCase(channel1, handler1)
                .withCase(channel2, handler2)
                .withCase(channel3, handler3)
                .run()).isTrue();
        verify(receiver).accept(value);
    }

    @Test
    public void testWithMultiplePut()
    {
        channel3.put("Bonjour");
        channel1.put(981);
        channel2.put(false);
        channel3.put("Hej");
        var verifiers = new ArrayList<Runnable>();
        doAnswer(invocation -> verifiers
                .add(() -> verify(handler1).accept(invocation.getArgument(0)))).when(handler1)
                        .accept(anyInt());
        doAnswer(invocation -> verifiers
                .add(() -> verify(handler2).accept(invocation.getArgument(0)))).when(handler2)
                        .accept(any());
        doAnswer(invocation -> verifiers
                .add(() -> verify(handler3).accept(invocation.getArgument(0)))).when(handler3)
                        .accept(any());
        assertThat(Select.withCase(channel1, handler1)
                .withCase(channel2, handler2)
                .withCase(channel3, handler3)
                .run()).isTrue();
        assertThat(verifiers.size()).isEqualTo(1);
        verifiers.forEach(Runnable::run);
    }

    @Test
    public void testWithMultiplePutAndGet()
    {
        channel3.put("Bonjour");
        channel1.put(981);
        channel2.put(false);
        channel3.put("Hej");
        var select = Select.withCase(channel1, handler1)
                .withCase(channel2, handler2)
                .withCase(channel3, handler3);
        IntStream.range(0, 4)
                .forEach(i -> assertThat(select.run()).isTrue());
        verify(handler1).accept(981);
        verify(handler2).accept(false);
        verify(handler3).accept("Bonjour");
        verify(handler3).accept("Hej");
    }

    @Test
    public void testWithDefault()
    {
        assertThat(Select.withCase(channel1, handler1)
                .withCase(channel2, handler2)
                .withCase(channel3, handler3)
                .withDefault(defaultHandler)
                .run()).isTrue();
        verify(defaultHandler).run();
    }

    @Test
    public void testAllClosedNoDefault()
    {
        Stream.of(channel1, channel2, channel3)
                .forEach(Channel::close);
        assertThat(Select.withCase(channel1, handler1)
                .withCase(channel2, handler2)
                .withCase(channel3, handler3)
                .run()).isFalse();
    }

    @Test
    public void testWithDefaultAndTwoPuts()
    {
        channel2.put(false);
        channel3.put("Hello");
        Selecter select = Select.withCase(channel1, handler1)
                .withCase(channel2, handler2)
                .withCase(channel3, handler3)
                .withDefault(defaultHandler);
        assertThat(select.run()).isTrue();
        assertThat(select.run()).isTrue();
        assertThat(select.run()).isTrue();
        verify(handler2).accept(false);
        verify(handler3).accept("Hello");
        verify(defaultHandler).run();
    }

    @Test
    public void testAllClosedWithDefault()
    {
        Stream.of(channel1, channel2, channel3)
                .forEach(Channel::close);
        assertThat(Select.withCase(channel1, handler1)
                .withCase(channel2, handler2)
                .withCase(channel3, handler3)
                .withDefault(defaultHandler)
                .run()).isFalse();
    }

    @Test
    public void testAllButOneClosedWithDefault()
    {
        Stream.of(channel1, channel3)
                .forEach(Channel::close);
        assertThat(Select.withCase(channel1, handler1)
                .withCase(channel2, handler2)
                .withCase(channel3, handler3)
                .withDefault(defaultHandler)
                .run()).isTrue();
        verify(defaultHandler).run();
    }
}