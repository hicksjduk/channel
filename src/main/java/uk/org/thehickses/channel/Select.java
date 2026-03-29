package uk.org.thehickses.channel;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReference;
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
     * Creates a selecter which runs a case to read the specified channel, and handle the retrieved value if there is
     * one.
     */
    public static <T> SelecterWithoutDefault withCase(Channel<T> channel,
            Consumer<? super T> handler)
    {
        Stream.of(channel, handler)
                .forEach(Objects::requireNonNull);
        return new SelecterWithoutDefault(new ChannelCase<>(channel, handler));
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
         * Creates a selecter which adds a case, to read the specified channel and handle the retrieved value if there
         * is one, to the receiver.
         */
        public <T> SelecterWithoutDefault withCase(Channel<T> channel, Consumer<? super T> handler)
        {
            Stream.of(channel, handler)
                    .forEach(Objects::requireNonNull);
            return new SelecterWithoutDefault(this, new ChannelCase<>(channel, handler));
        }

        /**
         * Creates a selecter which adds a default handler to the receiver.
         */
        public SelecterWithDefault withDefault(Runnable handler)
        {
            Objects.requireNonNull(handler);
            return new SelecterWithDefault(this, handler);
        }

        /**
         * Runs the select. As this selecter has no default, this method blocks until either a value is retrieved from
         * one of the channels, or all the channels are closed and empty.
         * 
         * @return whether a value was selected and handled. If this is false, it means that all the channels were
         *         closed and empty.
         */
        @Override
        public boolean run()
        {
            var selectGroup = new SelectGroup();
            var resultChannel = new Channel<CaseResult>();
            cases.forEach(c -> c.runAsync(resultChannel, selectGroup));
            var answer = resultChannel.stream()
                    .limit(cases.size())
                    .filter(CaseResult::valueRetrieved)
                    .map(CaseResult::getHandler)
                    .peek(Runnable::run)
                    .findFirst()
                    .isPresent();
            resultChannel.close();
            return answer;
        }
    }

    /**
     * A selecter which has a default clause.
     */
    public static class SelecterWithDefault implements Selecter
    {
        private final List<ChannelCase<?>> cases;
        private final Runnable defaultHandler;

        private SelecterWithDefault(SelecterWithoutDefault base, Runnable defaultHandler)
        {
            this.cases = new LinkedList<>(base.cases);
            this.defaultHandler = defaultHandler;
        }

        /**
         * Runs the select. As this selecter has a default, this method reads each of the channels in turn, but does not
         * block if any contains no value. If, after reading all the channels, none has a value and any are still open,
         * the default handler is run.
         * 
         * @return whether a value was selected and handled, or the default handler was run. If this is false, it means
         *         that all the channels were closed and empty.
         */
        @Override
        public boolean run()
        {
            var runners = Stream.<Consumer<Runnable>> builder();
            var handler = cases.stream()
                    .map(ChannelCase::runSync)
                    .filter(Predicate.not(CaseResult::channelClosed))
                    .peek(r -> runners.add(Runnable::run))
                    .filter(CaseResult::valueRetrieved)
                    .map(CaseResult::getHandler)
                    .findFirst()
                    .orElse(defaultHandler);
            return runners.build()
                    .peek(runner -> runner.accept(handler))
                    .findFirst()
                    .isPresent();
        }
    }

    private static class ChannelCase<T>
    {
        public final Channel<T> channel;
        public final Consumer<? super T> handler;

        public ChannelCase(Channel<T> channel, Consumer<? super T> handler)
        {
            this.channel = channel;
            this.handler = handler;
        }

        public CaseResult runSync()
        {
            return CaseResult.from(channel.getNonBlocking(), handler);
        }

        public void runAsync(Channel<CaseResult> resultChannel, SelectGroup selectGroup)
        {
            SelectControllerSupplier<T> scs = req -> selectGroup.addMember(channel, req);
            Runnable runner = () -> resultChannel.put(CaseResult.from(channel.get(scs), handler));
            ForkJoinPool.commonPool()
                    .execute(runner);
        }
    }

    private static class CaseResult
    {
        public static <T> CaseResult from(Optional<T> readResult, Consumer<? super T> handler)
        {
            var h = readResult == null ? null
                    : readResult.map(v -> (Runnable) () -> handler.accept(v));
            return new CaseResult(h);
        }

        private final Optional<Runnable> handler;

        private CaseResult(Optional<Runnable> handler)
        {
            this.handler = handler;
        }

        public boolean valueRetrieved()
        {
            return handler != null && handler.isPresent();
        }

        @SuppressWarnings("unused")
        public boolean noValueAvailable()
        {
            return handler == null;
        }

        public boolean channelClosed()
        {
            return handler != null && handler.isEmpty();
        }

        public Runnable getHandler()
        {
            return handler == null ? null : handler.orElse(null);
        }
    }
}