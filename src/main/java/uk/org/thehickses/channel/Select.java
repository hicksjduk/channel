package uk.org.thehickses.channel;

import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.IntStream;
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
            this.cases = List.of(newCase);
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
         * one of the channels, or all the channels are closed and empty.
         * 
         * @return whether a value was selected and processed. If this is false, it means that all the channels were
         *         closed and empty.
         */
        @Override
        public boolean run()
        {
            var selectGroup = new SelectGroup();
            var caseCount = cases.size();
            var processorRunnerChannel = new Channel<Optional<Runnable>>(caseCount);
            cases.forEach(c -> c.runAsync(processorRunnerChannel, selectGroup));
            var answer = IntStream.range(0, caseCount)
                    .mapToObj(i -> processorRunnerChannel.get())
                    .map(Optional::get)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .peek(Runnable::run)
                    .findFirst()
                    .isPresent();
            processorRunnerChannel.close();
            return answer;
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
         *         means that all the channels were closed and empty.
         */
        @Override
        public boolean run()
        {
            var f = cases.stream()
                    .map(ChannelCase::runSync)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(Optional::isEmpty))
                    .findFirst();
            if (f.isEmpty())
            {
                defaultProcessor.run();
                return true;
            }
            if (f.get().isEmpty())
                return false;
            f.get().get().run();
            return true;
            // var allClosed = new AtomicBoolean(true);
            // if (cases.stream()
            // .map(ChannelCase::runSync)
            // .peek(res -> allClosed.compareAndSet(true, res == CaseResult.CHANNEL_CLOSED))
            // .anyMatch(CaseResult.VALUE_READ::equals))
            // return true;
            // if (allClosed.get())
            // return false;
            // defaultProcessor.run();
            // return true;
        }
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

        public Optional<Runnable> runSync()
        {
            return Optional.ofNullable(channel.getNonBlocking())
                    .map(o -> o.map(v -> (Runnable) () -> processor.accept(v)))
                    .orElse(null);
        }

        public CaseRunner<T> runAsync(Channel<Optional<Runnable>> processorRunnerChannel,
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
        private final Channel<Optional<Runnable>> processorRunnerChannel;
        private final SelectControllerSupplier<T> selectControllerSupplier;

        public CaseRunner(ChannelCase<T> channelCase,
                Channel<Optional<Runnable>> processorRunnerChannel, SelectGroup selectGroup)
        {
            this.channel = channelCase.channel;
            this.processor = channelCase.processor;
            this.processorRunnerChannel = processorRunnerChannel;
            this.selectControllerSupplier = r -> selectGroup.addMember(channel, r);
        }

        @Override
        public void run()
        {
            Optional<Runnable> processorRunner = channel.get(selectControllerSupplier)
                    .map(v -> () -> processor.accept(v));
            processorRunnerChannel.put(processorRunner);
        }
    }
}
