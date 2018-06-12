package uk.org.thehickses.channel;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

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
    public static <T> Selecter withCase(Channel<T> channel, Consumer<T> processor)
    {
        return new Selecter(new ChannelCase<>(channel, processor));
    }

    /**
     * A selecter which has no default clause.
     */
    public static class Selecter
    {
        private final List<ChannelCase<?>> cases;

        private Selecter(ChannelCase<?> newCase)
        {
            cases = Arrays.asList(newCase);
        }

        private Selecter(List<ChannelCase<?>> cases, ChannelCase<?> newCase)
        {
            (this.cases = cases).add(newCase);
        }

        /**
         * Creates a selecter which adds a case, to read the specified channel and process the retrieved value if there
         * is one, to the receiver.
         */
        public <T> Selecter withCase(Channel<T> channel, Consumer<T> processor)
        {
            return new Selecter(copyCases(), new ChannelCase<>(channel, processor));
        }

        /**
         * Creates a selecter which adds a default processor to the receiver.
         */
        public SelecterWithDefault withDefault(Runnable processor)
        {
            return new SelecterWithDefault(copyCases(), processor);
        }

        private List<ChannelCase<?>> copyCases()
        {
            return new LinkedList<>(cases);
        }

        /**
         * Runs the select. As this selecter has no default, this method blocks until either a value is retrieved from
         * one of the channels, or all the channels are closed.
         */
        public void run()
        {
            List<ChannelCase<?>> caseList = copyCases();
            int caseCount = caseList.size();
            SelectGroup selectGroup = new SelectGroup();
            Channel<Void> doneChannel = new Channel<>(caseCount);
            caseList.forEach(c -> c.runAsync(doneChannel, selectGroup));
            for (int openChannels = caseCount; openChannels > 0; openChannels--)
                if (!doneChannel.get().containsValue)
                    break;
            selectGroup.cancel();
        }
    }

    /**
     * A selecter which has a default clause.
     */
    public static class SelecterWithDefault
    {
        private final List<ChannelCase<?>> cases;
        private final Runnable defaultProcessor;

        private SelecterWithDefault(List<ChannelCase<?>> cases, Runnable defaultProcessor)
        {
            this.cases = cases;
            this.defaultProcessor = defaultProcessor;
        }

        /**
         * Runs the select. As this selecter has a default, this method reads each of the channels in turn, but does not
         * block if any contains no value. If, after reading all the channels, none has a value and any are still open,
         * the default processor is run.
         */
        public void run()
        {
            boolean allClosed = true;
            for (ChannelCase<?> c : cases)
            {
                CaseResult result = c.runSync();
                if (result == CaseResult.VALUE_READ)
                    return;
                if (result == CaseResult.NO_VALUE_AVAILABLE)
                    allClosed = false;
            }
            if (!allClosed)
                defaultProcessor.run();
        }
    }

    private enum CaseResult
    {
        VALUE_READ, CHANNEL_CLOSED, NO_VALUE_AVAILABLE
    }

    private static class ChannelCase<T>
    {
        public final Channel<T> channel;
        public final Consumer<T> processor;

        public ChannelCase(Channel<T> channel, Consumer<T> processor)
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
        private final Consumer<T> processor;
        private final GetRequest<T> request;
        private final Channel<Void> doneChannel;

        public CaseRunner(ChannelCase<T> channelCase, Channel<Void> doneChannel,
                SelectGroup selectGroup)
        {
            this.channel = channelCase.channel;
            this.processor = channelCase.processor;
            this.request = channel == null ? null : channel.getRequest(r -> {
                selectGroup.addRequest(channel, r);
                return selectGroup;
            });
            this.doneChannel = doneChannel;
        }

        public void run()
        {
            GetResult<T> result = request == null ? new GetResult<>(null)
                    : request.response().result();
            if (result.containsValue)
            {
                processor.accept(result.value);
                doneChannel.close();
            }
            else
                doneChannel.putIfOpen(null);
        }
    }
}