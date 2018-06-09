package uk.org.thehickses.channel;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import uk.org.thehickses.channel.Channel.GetRequest;

public class Select
{
    public static <T> Selecter withCase(Channel<T> channel, Consumer<T> processor)
    {
        Selecter answer = new Selecter();
        answer.addCase(new ChannelCase<>(channel, processor));
        return answer;
    }

    public static class Selecter
    {
        private final List<ChannelCase<?>> cases = new LinkedList<>();

        private Selecter()
        {
        }

        public <T> Selecter withCase(Channel<T> channel, Consumer<T> processor)
                throws IllegalStateException
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
            Channel<Void> doneChannel = new Channel<>();
            List<CaseRunner<?>> runners = cases
                    .stream()
                    .map(c -> doneChannel.isOpen() ? c.runCase(doneChannel) : null)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            doneChannel.get();
            runners.forEach(CaseRunner::cancel);
        }

    }
    
    public static class FinalSelecter
    {
        private final Selecter selecter;

        public FinalSelecter(Selecter selecter)
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

        public CaseRunner<T> runCase(Channel<Void> doneChannel)
        {
            CaseRunner<T> cr = new CaseRunner<>(this, doneChannel);
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

        public CaseRunner(ChannelCase<T> channelCase, Channel<Void> doneChannel)
        {
            this.channel = channelCase.channel;
            this.processor = channelCase.processor;
            this.request = channel == null ? null : channel.getRequest();
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

        public void cancel()
        {
            if (channel != null)
                channel.cancel(request);
        }
    }
}
