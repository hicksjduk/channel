package uk.org.thehickses.channel.internal;

import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;

import uk.org.thehickses.channel.Channel;

public class ChannelCase<T>
{
    public final ChannelPrivateAccessor<T> privateAccessor;
    public final Consumer<? super T> processor;

    public ChannelCase(ChannelPrivateAccessor<T> privateAccessor, Consumer<? super T> processor)
    {
        this.privateAccessor = privateAccessor;
        this.processor = processor;
    }

    public CaseResult runSync()
    {
        var result = privateAccessor.getNonBlocking();
        if (result == null)
            return CaseResult.NO_VALUE_AVAILABLE;
        if (!result.containsValue)
            return CaseResult.CHANNEL_CLOSED;
        processor.accept(result.value);
        return CaseResult.VALUE_READ;
    }

    public CaseRunner<T> runAsync(Channel<Runnable> processorRunnerChannel,
            SelectGroup selectGroup)
    {
        var cr = new CaseRunner<>(this, processorRunnerChannel, selectGroup);
        ForkJoinPool.commonPool().execute(cr);
        return cr;
    }
}