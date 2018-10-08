package uk.org.thehickses.channel;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

import uk.org.thehickses.channel.Channel.GetRequest;

/**
 * A Java implementation of the Go select statement, for reading multiple channels.
 * 
 * @author Jeremy Hicks
 */
public class Select
{
    /**
     * Creates a selecter which runs a case to read the specified channel, and process the retrieved value if there is
     * one.
     */
    public static <T> SelecterWithoutDefault withCase(Channel<T> channel, Consumer<? super T> processor)
    {
        Stream.of(channel, processor).forEach(Objects::requireNonNull);
        return new SelecterWithoutDefault(new ChannelCase<>(channel, processor));
    }

    public static interface Selecter
    {
        boolean run();
    }

    /**
     * A selecter which has no default clause.
     */
    public static class SelecterWithoutDefault implements Selecter
    {
        private final List<ChannelCase<?>> cases;

        private SelecterWithoutDefault(ChannelCase<?> newCase)
        {
            this.cases = Arrays.asList(newCase);
        }

        private SelecterWithoutDefault(SelecterWithoutDefault base, ChannelCase<?> newCase)
        {
            (this.cases = new LinkedList<>(base.cases)).add(newCase);
        }

        /**
         * Creates a selecter which adds a case, to read the specified channel and process the retrieved value if there
         * is one, to the receiver.
         */
        public <T> SelecterWithoutDefault withCase(Channel<T> channel, Consumer<? super T> processor)
        {
            Stream.of(channel, processor).forEach(Objects::requireNonNull);
            return new SelecterWithoutDefault(this, new ChannelCase<>(channel, processor));
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
         * Runs the select. As this selecter has no default, this method blocks until either a value is retrieved from
         * one of the channels, or all the channels are closed.
         * 
         * @return whether a value was selected and processed. If this is false, it means that all the channels were
         *         closed.
         */
        @Override
        public boolean run()
        {
            int caseCount = cases.size();
            SelectGroup selectGroup = new SelectGroup();
            Channel<Void> doneChannel = new Channel<>(caseCount);
            cases.forEach(c -> c.runAsync(doneChannel, selectGroup));
            // If a value has been retrieved and processed, the case runner closes doneChannel. If a channel
            // is closed, each case runner that is waiting for it puts a value into doneChannel. So we loop until
            // doneChannel is closed, or has received a value from every case runner.
            for (int openChannels = caseCount; openChannels > 0; openChannels--)
                if (!doneChannel.get().containsValue)
                    return true;
            return false;
        }
    }

    /**
     * A selecter which has a default clause.
     */
    public static class SelecterWithDefault implements Selecter
    {
        private final List<ChannelCase<?>> cases;
        private final Runnable defaultProcessor;

        private SelecterWithDefault(SelecterWithoutDefault base, Runnable defaultProcessor)
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
            boolean allClosed = true;
            for (ChannelCase<?> c : cases)
            {
                CaseResult result = c.runSync();
                if (result == CaseResult.VALUE_READ)
                    return true;
                if (result != CaseResult.CHANNEL_CLOSED)
                    allClosed = false;
            }
            if (!allClosed)
                defaultProcessor.run();
            return !allClosed;
        }
    }

    private static enum CaseResult
    {
        VALUE_READ, CHANNEL_CLOSED, NO_VALUE_AVAILABLE
    }

    private static class ChannelCase<T>
    {
        public final Channel<T> channel;
        public final Consumer<? super T> processor;

        public ChannelCase(Channel<T> channel, Consumer<? super T> processor)
        {
            this.channel = channel;
            this.processor = processor;
        }

        public CaseResult runSync()
        {
            GetResult<T> result = channel.getNonBlocking();
            if (result == null)
                return CaseResult.NO_VALUE_AVAILABLE;
            if (!result.containsValue)
                return CaseResult.CHANNEL_CLOSED;
            processor.accept(result.value);
            return CaseResult.VALUE_READ;
        }

        public CaseRunner<T> runAsync(Channel<Void> doneChannel, SelectGroup selectGroup)
        {
            CaseRunner<T> cr = new CaseRunner<>(this, doneChannel, selectGroup);
            new Thread(cr).start();
            return cr;
        }
    }

    private static class CaseRunner<T> implements Runnable
    {
        private final Channel<T> channel;
        private final Consumer<? super T> processor;
        private final Channel<Void> doneChannel;
        private final SelectGroup selectGroup;

        public CaseRunner(ChannelCase<T> channelCase, Channel<Void> doneChannel,
                SelectGroup selectGroup)
        {
            this.channel = channelCase.channel;
            this.processor = channelCase.processor;
            this.doneChannel = doneChannel;
            this.selectGroup = selectGroup;
        }

        @Override
        public void run()
        {
            GetRequest<T> request = channel.getRequest(r -> selectGroup.addMember(channel, r));
            GetResult<T> result = request.response().result();
            if (result.containsValue)
            {
                processor.accept(result.value);
                doneChannel.close();
            }
            else
                doneChannel.put(null);
        }
    }
}
