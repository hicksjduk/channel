package uk.org.thehickses.channel;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.function.Predicate;
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
            return processorRunnerChannel.stream()
                    .limit(caseCount)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .peek(Runnable::run)
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
         *         means that all the channels were closed and empty.
         */
        @Override
        public boolean run()
        {
            Iterable<CaseResult> results = () -> cases.stream()
                    .map(ChannelCase::runSync)
                    .filter(Predicate.not(CaseResult::isChannelClosed))
                    .iterator();
            var runProcessor = false;
            for (var res : results)
            {
                res.getProcessor()
                        .ifPresent(Runnable::run);
                if (res.isValueRetrieved())
                    return true;
                runProcessor = true;
            }
            if (runProcessor)
                defaultProcessor.run();
            return runProcessor;
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

        public CaseResult runSync()
        {
            return Optional.ofNullable(channel.getNonBlocking())
                    .map(o -> o.map(v -> (Runnable) () -> processor.accept(v))
                            .map(CaseResult::valueRetrieved)
                            .orElse(CaseResult.channelClosed()))
                    .orElse(CaseResult.noValueAvailable());
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

    private static class CaseResult
    {
        public static CaseResult valueRetrieved(Runnable processor)
        {
            return new CaseResult(Optional.of(processor));
        }

        public static CaseResult noValueAvailable()
        {
            return new CaseResult(null);
        }

        public static CaseResult channelClosed()
        {
            return new CaseResult(Optional.empty());
        }

        private final Optional<Runnable> processor;

        private CaseResult(Optional<Runnable> processor)
        {
            this.processor = processor;
        }

        public boolean isValueRetrieved()
        {
            return processor != null && processor.isPresent();
        }

        @SuppressWarnings("unused")
        public boolean isNoValueAvailable()
        {
            return processor == null;
        }

        public boolean isChannelClosed()
        {
            return processor != null && processor.isEmpty();
        }

        public Optional<Runnable> getProcessor()
        {
            return Optional.ofNullable(processor)
                    .filter(Optional::isPresent)
                    .map(Optional::get);
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
