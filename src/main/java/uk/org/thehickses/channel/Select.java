package uk.org.thehickses.channel;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

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
    public static <T> SelecterWithoutDefault withCase(Channel<T> channel,
            Consumer<? super T> processor)
    {
        Stream.of(channel, processor)
                .forEach(Objects::requireNonNull);
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
        public <T> SelecterWithoutDefault withCase(Channel<T> channel,
                Consumer<? super T> processor)
        {
            Stream.of(channel, processor)
                    .forEach(Objects::requireNonNull);
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
            var selectGroup = new SelectGroup();
            var processorRunnerChannel = new Channel<Runnable>(1);
            cases.forEach(c -> c.runAsync(processorRunnerChannel, selectGroup));
            return processorRunnerChannel.stream()
                    .peek(r -> r.run())
                    .findFirst()
                    .isPresent();
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
            var allClosed = new AtomicBoolean(true);
            if (cases.stream()
                    .map(ChannelCase::runSync)
                    .peek(res -> allClosed.compareAndSet(true, res == CaseResult.CHANNEL_CLOSED))
                    .anyMatch(CaseResult.VALUE_READ::equals))
                return true;
            if (allClosed.get())
                return false;
            defaultProcessor.run();
            return true;
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
            return Optional.ofNullable(channel.getNonBlocking())
                    .map(o -> o.map(v ->
                        {
                            processor.accept(v);
                            return CaseResult.VALUE_READ;
                        })
                            .orElse(CaseResult.CHANNEL_CLOSED))
                    .orElse(CaseResult.NO_VALUE_AVAILABLE);
        }

        public CaseRunner<T> runAsync(Channel<Runnable> processorRunnerChannel,
                SelectGroup selectGroup)
        {
            var cr = new CaseRunner<>(this, processorRunnerChannel, selectGroup);
            ForkJoinPool.commonPool()
                    .execute(cr);
            return cr;
        }
    }

    private static class CaseRunner<T> implements Runnable
    {
        private final Channel<T> channel;
        private final Consumer<? super T> processor;
        private final Channel<Runnable> processorRunnerChannel;
        private final SelectGroup selectGroup;
        private final SelectControllerSupplier<T> selectControllerSupplier;

        public CaseRunner(ChannelCase<T> channelCase, Channel<Runnable> processorRunnerChannel,
                SelectGroup selectGroup)
        {
            this.channel = channelCase.channel;
            this.processor = channelCase.processor;
            this.processorRunnerChannel = processorRunnerChannel;
            this.selectGroup = selectGroup;
            this.selectControllerSupplier = r -> selectGroup.addMember(channel, r);
        }

        @Override
        public void run()
        {
            if (channel.get(selectControllerSupplier)
                    .map(v ->
                        {
                            processorRunnerChannel.put(() -> processor.accept(v));
                            return true;
                        })
                    .orElse(selectGroup.allResultsIn()))
                processorRunnerChannel.close();
        }
    }
}
