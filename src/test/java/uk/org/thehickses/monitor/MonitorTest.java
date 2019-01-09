package uk.org.thehickses.monitor;

import static org.assertj.core.api.Assertions.*;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.Test;

import uk.org.thehickses.channel.Channel;

public class MonitorTest
{

    @SuppressWarnings("unchecked")
    @Test
    public void test()
    {
        Monitor monitor = new Monitor();
        Channel<Void>[] channels = IntStream
                .range(0, 10)
                .mapToObj(i -> new Channel<Void>())
                .toArray(Channel[]::new);
        Arrays.stream(channels, 0, 5).forEach(ch -> runProcess(monitor, ch));
        Channel<Void> doneChannel1 = startWait(monitor);
        Arrays.stream(channels, 5, 10).forEach(ch -> runProcess(monitor, ch));
        Channel<Void> doneChannel2 = startWait(monitor);
        Arrays.stream(channels, 0, 5).forEach(this::waitAndClose);
        doneChannel1.get();
        assertThat(doneChannel2.isOpen());
        Channel<Void> doneChannel3 = startWait(monitor);
        Arrays.stream(channels, 5, 10).forEach(this::waitAndClose);
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

    private <T> void waitAndClose(Channel<T> ch)
    {
        int seconds = 1;
        try
        {
            Thread.sleep(TimeUnit.SECONDS.toMillis(seconds));
        }
        catch (InterruptedException e)
        {
        }
        ch.close();
    }

}
