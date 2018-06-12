package uk.org.thehickses.channel;

import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

import uk.org.thehickses.channel.Channel.GetRequest;

public class Select
{
    public static <T> Selecter withCase(Channel<T> channel, Consumer<T> processor)
    {
        return new Selecter().withCase(channel, processor);
    }

    public static class Selecter
    {
        private final List<ChannelCase<?>> cases = new LinkedList<>();
        private Runnable defaultProcessor;

        private Selecter()
        {
        }

        public <T> Selecter withCase(Channel<T> channel, Consumer<T> processor)
        {
            addCase(new ChannelCase<>(channel, processor));
            return this;
        }

        public FinalSelecter withDefault(Runnable processor)
        {
            defaultProcessor = processor;
            return new FinalSelecter(this);
        }

        private void addCase(ChannelCase<?> newCase)
        {
            cases.add(newCase);
        }

        public void run()
        {
            if (defaultProcessor != null)
                runSync();
            else
                runAsync();
        }

        private void runSync()
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

        private void runAsync()
        {
            int caseCount = cases.size();
            SelectGroup selectGroup = new SelectGroup();
            Channel<Void> doneChannel = new Channel<>(caseCount);
            cases.forEach(c -> c.runAsync(doneChannel, selectGroup));
            for (int openChannels = caseCount; openChannels > 0; openChannels--)
                if (!doneChannel.get().containsValue)
                    break;
            selectGroup.cancel();
        }
    }

    public static class FinalSelecter
    {
        private final Selecter selecter;

        private FinalSelecter(Selecter selecter)
        {
            this.selecter = selecter;
        }

        public void run()
        {
            selecter.run();
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
