package uk.org.thehickses.channel;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import uk.org.thehickses.channel.internal.CaseResult;
import uk.org.thehickses.channel.internal.ChannelCase;

/**
 * A selecter which has a default clause.
 */
public class SelecterWithDefault implements Selecter
{
    private final List<ChannelCase<?>> cases;
    private final Runnable defaultProcessor;

    SelecterWithDefault(SelecterWithoutDefault base, Runnable defaultProcessor)
    {
        this.cases = new LinkedList<>(base.cases);
        this.defaultProcessor = defaultProcessor;
    }

    /**
     * Runs the select. As this selecter has a default, this method reads each of the channels in turn, but does not
     * block if any contains no value. If, after reading all the channels, none has a value and any are still open,
     * the default processor is run.
     * 
     * @return whether a value was selected and processed, or the default processor was run. If this is false, it
     *         means that all the channels were closed.
     */
    @Override
    public boolean run()
    {
        AtomicBoolean allClosed = new AtomicBoolean(true);
        if (cases
                .stream()
                .map(ChannelCase::runSync)
                .peek(res -> allClosed.compareAndSet(true, res == CaseResult.CHANNEL_CLOSED))
                .anyMatch(res -> res == CaseResult.VALUE_READ))
            return true;
        if (allClosed.get())
            return false;
        defaultProcessor.run();
        return true;
    }
}