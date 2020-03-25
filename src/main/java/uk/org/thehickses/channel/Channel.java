package uk.org.thehickses.channel;

import static uk.org.thehickses.locking.Locking.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * A class that emulates a channel in the Go language.
 * 
 * @author Jeremy Hicks
 *
 * @param <T>
 *            the type of the objects that the channel holds.
 */
public class Channel<T>
{
    private final int bufferSize;
    private final AtomicReference<Status> status = new AtomicReference<>(Status.OPEN);
    private final Deque<GetRequest<T>> getQueue = new ArrayDeque<>();
    private final LinkedList<PutRequest<T>> putQueue = new LinkedList<>();
    private final Lock lock = new ReentrantLock();
    private final Deque<DeferredPutter> scheduledPutTasks = new ConcurrentLinkedDeque<>();

    /**
     * Creates a channel with the default buffer size of 0.
     */
    public Channel()
    {
        this(0);
    }

    /**
     * Creates a channel with the specified buffer size.
     */
    public Channel(int bufferSize)
    {
        this.bufferSize = bufferSize;
    }

    /**
     * If the channel is not already closed, closes the channel and signals all pending requests that it is closed.
     * 
     * @return whether this call closed the channel. A return value of false means that the channel was already closed.
     */
    public boolean close()
    {
        scheduledPutTasks.forEach(DeferredPutter::cancel);
        Stream.Builder<Request> requests = Stream.builder();
        boolean wasOpen = doWithLock(lock, () ->
            {
                if (status.getAndSet(Status.CLOSED) == Status.CLOSED)
                    return false;
                Stream.of(getQueue, putQueue).filter(q -> !q.isEmpty()).forEach(drainer(requests));
                return true;
            });
        if (wasOpen)
            requests.build().forEach(Request::setChannelClosed);
        return wasOpen;
    }

    private static <V, C extends Collection<? extends V>> Consumer<C> drainer(
            Stream.Builder<V> target)
    {
        return source ->
            {
                source.stream().forEach(target);
                source.clear();
            };
    }

    /**
     * Tells the channel to close when all the existing values have been consumed.
     */
    public void closeWhenEmpty()
    {
        if (status.compareAndSet(Status.OPEN, Status.CLOSE_WHEN_EMPTY))
            closeIfEmpty();
    }

    private void closeIfEmpty()
    {
        doWithLock(lock, () ->
            {
                if (putQueue.isEmpty() && scheduledPutTasks.isEmpty())
                    close();
            });
    }

    public boolean isOpen()
    {
        return status.get() != Status.CLOSED;
    }

    /**
     * Processes values from the channel until it is closed.
     * 
     * @param processor
     *            the processor that is invoked to process each value. This processor may signal that the iteration
     *            should terminate by throwing a {@link RangeBreakException}.
     */
    public void range(Consumer<? super T> processor)
    {
        Objects.requireNonNull(processor);
        GetResult<T> result;
        while ((result = get()).containsValue)
            try
            {
                processor.accept(result.value);
            }
            catch (RangeBreakException ex)
            {
                break;
            }
    }

    /**
     * Puts the specified value into the channel, as long as it is open. This call blocks until the number of requests
     * ahead of it in the put queue reduces to less than the channel's buffer size, or the value is used to satisfy a
     * get request.
     * 
     * @return whether the value was put. A value of false means that the channel was closed at the time the request is
     *         made, or became closed while the request was blocked.
     */
    public boolean put(T value)
    {
        return putRequest(value).response().result();
    }

    private PutRequest<T> putRequest(T value)
    {
        PutRequest<T> request = new PutRequest<>(value);
        doWithLock(lock, () ->
            {
                if (!isOpen())
                    request.setChannelClosed();
                else
                {
                    if (putQueue.size() < bufferSize)
                        request.setCompleted();
                    putQueue.offer(request);
                    processQueues();
                }
            });
        return request;
    }

    /**
     * Gets and removes a value from the channel. If no value is available to satisfy the request, this call blocks
     * until a value becomes available.
     * 
     * @return the result. If the channel was closed, either at the time of the call or while the request was blocked,
     *         {@link GetResult#containsValue} is false; otherwise {@link GetResult#containsValue} is true and
     *         {@link GetResult#value} contains the value retrieved.
     */
    public GetResult<T> get()
    {
        return get(null);
    }

    GetResult<T> get(SelectControllerSupplier<T> selectControllerSupplier)
    {
        return getRequest(selectControllerSupplier).response().result();
    }

    private GetRequest<T> getRequest(SelectControllerSupplier<T> selectControllerSupplier)
    {
        GetRequest<T> request = new GetRequest<>(selectControllerSupplier);
        doWithLock(lock, () ->
            {
                if (!isOpen())
                    request.setChannelClosed();
                else
                {
                    getQueue.offer(request);
                    processQueues();
                }
            });
        return request;
    }

    GetResult<T> getNonBlocking()
    {
        return doWithLock(lock, () ->
            {
                if (!isOpen())
                    return new GetResult<>();
                if (putQueue.isEmpty())
                    return null;
                return get();
            });
    }

    private void processQueues()
    {
        while (!getQueue.isEmpty() && !putQueue.isEmpty())
        {
            GetRequest<T> getRequest = getQueue.pop();
            if (!getRequest.isSelectable())
                continue;
            if (putQueue.size() > bufferSize)
                putQueue.get(bufferSize).setCompleted();
            PutRequest<T> putRequest = putQueue.pop();
            getRequest.setReturnedValue(putRequest.value);
        }
        if (status.get() == Status.CLOSE_WHEN_EMPTY)
            closeIfEmpty();
    }

    void cancel(GetRequest<T> request)
    {
        if (request.isComplete())
            return;
        doWithLock(lock, () ->
            {
                getQueue.remove(request);
            });
        request.setNoValue();
    }

    private void scheduleDeferredPut(T value, Stream<Instant> schedule)
    {
        scheduledPutTasks
                .add(new DeferredPutter(() -> put(value), schedule, scheduledPutTasks::remove)
                        .scheduleIfNotAlreadyRunning());
    }

    public void putAt(T value, Instant first, Instant... others)
    {
        Stream<Instant> schedule = Stream.concat(Stream.of(first), Stream.of(others)).sorted();
        scheduleDeferredPut(value, schedule);
    }

    public void putAfter(T value, Duration first, Duration... others)
    {
        Instant now = Instant.now();
        Stream<Instant> schedule = Stream
                .concat(Stream.of(first), Stream.of(others))
                .sorted()
                .map(now::plus);
        scheduleDeferredPut(value, schedule);
    }

    public void putRepeatedly(T value, Duration interval)
    {
        putRepeatedly(value, interval, 0);
    }

    public void putRepeatedly(T value, Duration interval, long maxCount)
    {
        putRepeatedlyStartingAfter(value, interval, interval, maxCount);
    }

    public void putRepeatedlyStartingAt(T value, Instant start, Duration interval)
    {
        putRepeatedlyStartingAt(value, start, interval, 0);
    }

    public void putRepeatedlyStartingAt(T value, Instant start, Duration interval, long maxCount)
    {
        Stream<Instant> schedule = Stream.iterate(start, t -> t.plus(interval));
        if (maxCount > 0)
            schedule = schedule.limit(maxCount);
        scheduleDeferredPut(value, schedule);
    }

    public void putRepeatedlyStartingAfter(T value, Duration start, Duration interval)
    {
        putRepeatedlyStartingAfter(value, start, interval, 0);
    }

    public void putRepeatedlyStartingAfter(T value, Duration start, Duration interval,
            long maxCount)
    {
        putRepeatedlyStartingAt(value, Instant.now().plus(start), interval, maxCount);
    }

    private static interface Request
    {
        void setChannelClosed();
    }

    @FunctionalInterface
    static interface GetResponse<T>
    {
        GetResult<T> result();
    }

    @FunctionalInterface
    private static interface PutResponse
    {
        boolean result();
    }

    static class GetRequest<T> implements Request
    {
        private final CompletableFuture<GetResponse<T>> responder = new CompletableFuture<>();
        private final SelectController selectController;

        public GetRequest(SelectControllerSupplier<T> supplier)
        {
            this.selectController = supplier == null ? r -> true : supplier.apply(this);
        }

        @Override
        public void setChannelClosed()
        {
            setNoValue();
        }

        public void setNoValue()
        {
            responder.complete(() -> new GetResult<>());
        }

        public void setReturnedValue(T value)
        {
            responder.complete(() -> new GetResult<>(value));
        }

        public boolean isSelectable()
        {
            return selectController.select(this);
        }

        public boolean isComplete()
        {
            return responder.isDone();
        }

        public GetResponse<T> response()
        {
            while (true)
                try
                {
                    return responder.get();
                }
                catch (InterruptedException e)
                {
                    continue;
                }
                catch (ExecutionException e)
                {
                    throw new RuntimeException(e);
                }
        }
    }

    private static class PutRequest<T> implements Request
    {
        public final T value;
        private final CompletableFuture<PutResponse> responder = new CompletableFuture<>();

        public PutRequest(T value)
        {
            this.value = value;
        }

        @Override
        public void setChannelClosed()
        {
            responder.complete(() -> false);
        }

        public void setCompleted()
        {
            responder.complete(() -> true);
        }

        public PutResponse response()
        {
            while (true)
                try
                {
                    return responder.get();
                }
                catch (InterruptedException e)
                {
                    continue;
                }
                catch (ExecutionException e)
                {
                    throw new RuntimeException(e);
                }
        }
    }

    @SuppressWarnings("serial")
    public static class RangeBreakException extends RuntimeException
    {
    }

    private static enum Status
    {
        OPEN, CLOSED, CLOSE_WHEN_EMPTY
    }

    private static class DeferredPutter
    {
        private final Runnable putter;
        private final Stream<Instant> schedule;
        private final Consumer<DeferredPutter> endListener;
        private AtomicReference<Runnable> canceller;

        public DeferredPutter(Runnable putter, Stream<Instant> schedule,
                Consumer<DeferredPutter> endListener)
        {
            this.putter = putter;
            this.schedule = schedule;
            this.endListener = endListener;
        }

        public DeferredPutter scheduleIfNotAlreadyRunning()
        {
            canceller.updateAndGet(r -> r == null ? submitAndGetCanceller() : r);
            return this;
        }

        private Runnable submitAndGetCanceller()
        {
            ForkJoinTask<?> task = ForkJoinPool.commonPool().submit(this::run);
            return () -> task.cancel(true);
        }

        private void run()
        {
            schedule.forEach(this::waitAndPut);
            endListener.accept(this);
        }

        private void waitAndPut(Instant time)
        {
            long delay = time.toEpochMilli() - Instant.now().toEpochMilli();
            if (delay > 0)
                try
                {
                    Thread.sleep(delay);
                }
                catch (InterruptedException e)
                {
                    return;
                }
            putter.run();
        }

        public void cancel()
        {
            canceller.updateAndGet(this::stop);
        }

        private Runnable stop(Runnable stopper)
        {
            if (stopper != null)
            {
                stopper.run();
                endListener.accept(this);
            }
            return null;
        }
    }
}
