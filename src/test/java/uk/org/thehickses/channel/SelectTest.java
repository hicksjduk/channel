package uk.org.thehickses.channel;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import uk.org.thehickses.channel.Channel.RangeBreakException;
import uk.org.thehickses.channel.Select.Selecter;

@SuppressWarnings("unchecked")
public class SelectTest
{
    private Channel<Integer> ch1 = new Channel<>(5);
    private Channel<Boolean> ch2 = new Channel<>(5);
    private Channel<String> ch3 = new Channel<>(5);
    private Consumer<Integer> proc1 = mock(Consumer.class);
    private Consumer<Boolean> proc2 = mock(Consumer.class);
    private Consumer<String> proc3 = mock(Consumer.class);
    private Runnable defaultProc = mock(Runnable.class);

    @AfterEach
    public void tearDown()
    {
        verifyNoMoreInteractions(proc1, proc2, proc3, defaultProc);
    }

    @Test
    public void testWithSinglePut1()
    {
        testWithSinglePut(ch1, proc1, 42);
    }

    @Test
    public void testWithSinglePut2()
    {
        testWithSinglePut(ch2, proc2, true);
    }

    @Test
    public void testWithSinglePut3()
    {
        testWithSinglePut(ch3, proc3, "Hello");
    }

    private <T> void testWithSinglePut(Channel<T> channel, Consumer<T> receiver, T value)
    {
        channel.put(value);
        assertThat(Select.withCase(ch1, proc1).withCase(ch2, proc2).withCase(ch3, proc3).run())
                .isTrue();
        verify(receiver).accept(value);
    }

    @Test
    public void testWithMultiplePut()
    {
        ch3.put("Bonjour");
        ch1.put(981);
        ch2.put(false);
        ch3.put("Hej");
        List<Runnable> verifiers = new ArrayList<>();
        doAnswer(invocation -> verifiers.add(() -> verify(proc1).accept(invocation.getArgument(0))))
                .when(proc1)
                .accept(anyInt());
        doAnswer(invocation -> verifiers.add(() -> verify(proc2).accept(invocation.getArgument(0))))
                .when(proc2)
                .accept(any());
        doAnswer(invocation -> verifiers.add(() -> verify(proc3).accept(invocation.getArgument(0))))
                .when(proc3)
                .accept(any());
        assertThat(Select.withCase(ch1, proc1).withCase(ch2, proc2).withCase(ch3, proc3).run())
                .isTrue();
        assertThat(verifiers.size()).isEqualTo(1);
        verifiers.forEach(Runnable::run);
    }

    @Test
    public void testWithMultiplePutAndGet()
    {
        ch3.put("Bonjour");
        ch1.put(981);
        ch2.put(false);
        ch3.put("Hej");
        Selecter select = Select.withCase(ch1, proc1).withCase(ch2, proc2).withCase(ch3, proc3);
        for (int count = 0; count < 4; count++)
            assertThat(select.run()).isTrue();
        verify(proc1).accept(981);
        verify(proc2).accept(false);
        verify(proc3).accept("Bonjour");
        verify(proc3).accept("Hej");
    }

    @Test
    public void testWithDefault()
    {
        assertThat(Select
                .withCase(ch1, proc1)
                .withCase(ch2, proc2)
                .withCase(ch3, proc3)
                .withDefault(defaultProc)
                .run()).isTrue();
        verify(defaultProc).run();
    }

    @Test
    public void testAllClosedNoDefault()
    {
        Stream.of(ch1, ch2, ch3).forEach(Channel::close);
        assertThat(Select.withCase(ch1, proc1).withCase(ch2, proc2).withCase(ch3, proc3).run())
                .isFalse();
    }

    @Test
    public void testWithDefaultAndTwoPuts()
    {
        ch2.put(false);
        ch3.put("Hello");
        Selecter select = Select
                .withCase(ch1, proc1)
                .withCase(ch2, proc2)
                .withCase(ch3, proc3)
                .withDefault(defaultProc);
        assertThat(select.run()).isTrue();
        assertThat(select.run()).isTrue();
        assertThat(select.run()).isTrue();
        verify(proc2).accept(false);
        verify(proc3).accept("Hello");
        verify(defaultProc).run();
    }

    @Test
    public void testAllClosedWithDefault()
    {
        Stream.of(ch1, ch2, ch3).forEach(Channel::close);
        assertThat(Select
                .withCase(ch1, proc1)
                .withCase(ch2, proc2)
                .withCase(ch3, proc3)
                .withDefault(defaultProc)
                .run()).isFalse();
    }

    @Test
    public void testAllButOneClosedWithDefault()
    {
        Stream.of(ch1, ch3).forEach(Channel::close);
        assertThat(Select
                .withCase(ch1, proc1)
                .withCase(ch2, proc2)
                .withCase(ch3, proc3)
                .withDefault(defaultProc)
                .run()).isTrue();
        verify(defaultProc).run();
    }

    @Test
    public void testValuePutIsNullNoDefault()
    {
        ch2.put(null);
        assertThat(Select.withCase(ch1, proc1).withCase(ch2, proc2).withCase(ch3, proc3).run())
                .isTrue();
        verify(proc2).accept(null);
    }

    @Test
    public void testValuePutIsNullWithDefault()
    {
        ch2.put(null);
        assertThat(Select
                .withCase(ch1, proc1)
                .withCase(ch2, proc2)
                .withCase(ch3, proc3)
                .withDefault(defaultProc)
                .run()).isTrue();
        verify(proc2).accept(null);
    }

    @Test
    public void testRangeNoDefault()
    {
        ch1.put(41);
        ch2.put(null);
        ch1.put(422141);
        ch3.put("Hello");
        Stream.of(ch1, ch2, ch3).forEach(Channel::close);
        List<Runnable> verifiers = new ArrayList<>();
        doAnswer(invocation -> verifiers.add(() -> verify(proc1).accept(invocation.getArgument(0))))
                .when(proc1)
                .accept(anyInt());
        doAnswer(invocation -> verifiers.add(() -> verify(proc2).accept(invocation.getArgument(0))))
                .when(proc2)
                .accept(any());
        doAnswer(invocation -> verifiers.add(() -> verify(proc3).accept(invocation.getArgument(0))))
                .when(proc3)
                .accept(any());
        Select.withCase(ch1, proc1).withCase(ch2, proc2).withCase(ch3, proc3).range();
        verifiers.forEach(Runnable::run);
    }

    @Test
    public void testRangeWithDefault()
    {
        ch1.put(41);
        ch2.put(null);
        ch1.put(422141);
        ch3.put("Hello");
        Select
                .withCase(ch1, proc1)
                .withCase(ch2, proc2)
                .withCase(ch3, proc3)
                .withDefault(() -> Stream.of(ch1, ch2, ch3).forEach(Channel::close))
                .range();
        verify(proc1).accept(41);
        verify(proc2).accept(null);
        verify(proc1).accept(422141);
        verify(proc3).accept("Hello");
    }

    @Test
    public void testRangeWithBreakExceptionNoDefault()
    {
        Stream.of(41, 422141).forEach(ch1::put);
        Stream.of(null, false).forEach(ch2::put);
        Stream.of("Hello", "Goodbye").forEach(ch3::put);
        Stream.of(ch1, ch2, ch3).forEach(Channel::close);
        List<Runnable> verifiers = new ArrayList<>();
        doAnswer(invocation -> verifiers.add(() -> verify(proc1).accept(invocation.getArgument(0))))
                .when(proc1)
                .accept(anyInt());
        doAnswer(invocation -> verifiers.add(() -> verify(proc2).accept(invocation.getArgument(0))))
                .when(proc2)
                .accept(any());
        Select.withCase(ch1, proc1).withCase(ch2, proc2).withCase(ch3, v ->
            {
                if (v.equals("Hello"))
                    throw new RangeBreakException();
            }).range();
        verifiers.forEach(Runnable::run);
        assertThat(ch3.get().value).isEqualTo("Goodbye");
    }

    @Test
    public void testRangeWithBreakExceptionAndDefault()
    {
        ch1.put(41);
        ch2.put(null);
        ch1.put(422141);
        ch3.put("Hello");
        ch2.put(false);
        Stream.of(ch1, ch2, ch3).forEach(Channel::close);
        Runnable m4 = mock(Runnable.class);
        Select.withCase(ch1, proc1).withCase(ch2, proc2).withCase(ch3, v ->
            {
                throw new RangeBreakException();
            }).withDefault(m4).range();
        verify(proc1).accept(41);
        verify(proc1).accept(422141);
        verify(proc2).accept(null);
        verify(proc2).accept(false);
    }
}