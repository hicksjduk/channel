package uk.org.thehickses.channel.internal;

import java.util.function.Consumer;

import uk.org.thehickses.channel.Channel;

public class CaseRunner<T> implements Runnable
{
    private final ChannelPrivateAccessor<T> privateAccessor;
    private final Consumer<? super T> processor;
    private final Channel<Runnable> processorRunnerChannel;
    private final SelectControllerSupplier<T> selectControllerSupplier;

    public CaseRunner(ChannelCase<T> channelCase, Channel<Runnable> processorRunnerChannel,
            SelectGroup selectGroup)
    {
        this.privateAccessor = channelCase.privateAccessor;
        this.processor = channelCase.processor;
        this.processorRunnerChannel = processorRunnerChannel;
        this.selectControllerSupplier = r -> selectGroup.addMember(privateAccessor, r);
    }

    @Override
    public void run()
    {
        var result = privateAccessor.get(selectControllerSupplier);
        processorRunnerChannel
                .put(result.containsValue ? () -> processor.accept(result.value) : null);
    }
}