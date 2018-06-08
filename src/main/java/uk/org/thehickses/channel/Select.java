package uk.org.thehickses.channel;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import uk.org.thehickses.channel.Channel.GetRequest;

public class Select
{
    private final List<ChannelCase<?>> cases = new LinkedList<>();
    private boolean hasDefault = false;

    public <T> Select withCase(Channel<T> channel, Consumer<T> processor) throws IllegalStateException
    {
        addCase(new ChannelCase<>(channel, processor));
        return this;
    }

    public Select withDefault(Runnable processor) throws IllegalStateException
    {
        if (cases.isEmpty())
            throw new IllegalStateException("There are no cases, adding a default makes no sense");
        addCase(new ChannelCase<Void>(null, v -> processor.run()));
        hasDefault = true;
        return this;
    }
    
    private void addCase(ChannelCase<?> newCase) throws IllegalStateException
    {
        if (hasDefault)
            throw new IllegalStateException("Default already added, no more cases can be specified");
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
