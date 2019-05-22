package uk.org.thehickses.channel;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import uk.org.thehickses.channel.internal.ChannelCase;
import uk.org.thehickses.channel.internal.SelectGroup;

/**
 * A selecter which has no default clause.
 */
public class SelecterWithoutDefault implements Selecter
{
    final List<ChannelCase<?>> cases;

    public SelecterWithoutDefault(ChannelCase<?> newCase)
    {
        this.cases = Arrays.asList(newCase);
    }

    public SelecterWithoutDefault(SelecterWithoutDefault base, ChannelCase<?> newCase)
    {
        (this.cases = new LinkedList<>(base.cases)).add(newCase);
    }

    /**
     * Creates a selecter which adds a case, to read the specified channel and process the retrieved value if there is
     * one, to the receiver.
     */
    public <T> SelecterWithoutDefault withCase(Channel<T> channel, Consumer<? super T> processor)
    {
        Stream.of(channel, processor).forEach(Objects::requireNonNull);
        return new SelecterWithoutDefault(this,
                new ChannelCase<>(channel.privateAccessor(), processor));
    }

    /**
     * Creates a selecter which adds a default processor to the receiver.
     */
    public SelecterWithDefault withDefault(Runnable processor)
    {
        Objects.requireNonNull(processor);
        return new SelecterWithDefault(this, processor);
    }

    /**
     * Runs the select. As this selecter has no default, this method blocks until either a value is retrieved from one
     * of the channels, or all the channels are closed.
     * 
     * @return whether a value was selected and processed. If this is false, it means that all the channels were closed.
     */
    @Override
    public boolean run()
    {
        int caseCount = cases.size();
        SelectGroup selectGroup = new SelectGroup();
        Channel<Runnable> processorRunnerChannel = new Channel<>(caseCount);
        cases.forEach(c -> c.runAsync(processorRunnerChannel, selectGroup));
        Runnable processorRunner = IntStream
                .range(0, caseCount)
                .mapToObj(i -> processorRunnerChannel.get().value)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (processorRunner == null)
            return false;
        processorRunner.run();
        return true;
    }
}