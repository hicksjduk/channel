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
            addCase(new ChannelCase<Void>(null, v -> processor.run()));
            return new FinalSelecter(this);
        }

        private void addCase(ChannelCase<?> newCase)
        {
            cases.add(newCase);
        }

        public void run()
        {
            SelectGroup selectGroup = new SelectGroup();
            Channel<Void> doneChannel = new Channel<>();
            cases.stream().forEach(c -> {
                if (doneChannel.isOpen())
                    c.runCase(doneChannel, selectGroup);
            });
            doneChannel.get();
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

    private static class ChannelCase<T>
    {
        public final Channel<T> channel;
        public final Consumer<T> processor;

        public ChannelCase(Channel<T> channel, Consumer<T> processor)
        {
            this.channel = channel;
            this.processor = processor;
        }

        public void runCase(Channel<Void> doneChannel, SelectGroup selectGroup)
        {
            CaseRunner<T> cr = new CaseRunner<>(this, doneChannel, selectGroup);
            new Thread(cr).start();
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
            doneChannel.close();
            if (result.containsValue)
                processor.accept(result.value);
        }
    }
}
