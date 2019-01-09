package uk.org.thehickses.monitor;

import static org.assertj.core.api.Assertions.*;

import java.util.Arrays;
import java.util.stream.IntStream;

import org.junit.Test;

import uk.org.thehickses.channel.Channel;

public class MonitorTest
{

    @SuppressWarnings("unchecked")
    @Test
    public void test()
    {
        int threadCount = 100;
        int halfThreadCount = threadCount / 2;
        Monitor monitor = new Monitor();
        Channel<Void>[] channels = IntStream
                .range(0, threadCount)
                .mapToObj(i -> new Channel<Void>())
                .toArray(Channel[]::new);
        Arrays.stream(channels, 0, halfThreadCount).forEach(ch -> runProcess(monitor, ch));
        Channel<Void> doneChannel1 = startWait(monitor);
        Arrays.stream(channels, halfThreadCount, threadCount).forEach(ch -> runProcess(monitor, ch));
        Channel<Void> doneChannel2 = startWait(monitor);
        Arrays.stream(channels, 0, halfThreadCount).forEach(Channel::close);
        doneChannel1.get();
        assertThat(doneChannel2.isOpen());
        Channel<Void> doneChannel3 = startWait(monitor);
        Arrays.stream(channels, halfThreadCount, threadCount).forEach(Channel::close);
        doneChannel2.get();
        doneChannel3.get();
    }

    private <T> void runProcess(Monitor monitor, Channel<T> ch)
    {
        monitor.execute(() -> {
            ch.get();
        });
    }

    private <T> Channel<T> startWait(Monitor monitor)
    {
        Channel<T> answer = new Channel<>();
        new Thread(() -> {
            monitor.waitForActiveProcesses();
            answer.close();
        }).start();
        return answer;
    }
}
