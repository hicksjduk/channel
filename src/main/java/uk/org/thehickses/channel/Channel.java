package uk.org.thehickses.channel;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
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
    private static <T> T doWithLock(Lock lock, Supplier<T> toDo)
    {
        lock.lock();
        try
        {
            return toDo.get();
        }
        finally
        {
            lock.unlock();
        }
    }

    private static void doWithLock(Lock lock, Runnable toDo)
    {
        doWithLock(lock, () -> {
            toDo.run();
            return true;
        });
    }

    private final int bufferSize;
    private final AtomicReference<Status> status = new AtomicReference<>(Status.OPEN);
    private final Deque<GetRequest<T>> getQueue = new ArrayDeque<>();
    private final LinkedList<PutRequest<T>> putQueue = new LinkedList<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock(true);

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
        if (status.getAndSet(Status.CLOSED) == Status.CLOSED)
            return false;
        Stream.Builder<Request> requests = Stream.builder();
        doWithLock(lock.writeLock(), () -> {
            Stream.of(getQueue, putQueue).filter(q -> !q.isEmpty()).forEach(drainer(requests));
        });
        requests.build().forEach(Request::setChannelClosed);
        return true;
    }

    private static <V, C extends Collection<? extends V>> Consumer<C> drainer(
            Stream.Builder<V> target)
    {
        return source -> {
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
        doWithLock(lock.writeLock(), () -> {
            if (putQueue.isEmpty())
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
        if (!isOpen())
            request.setChannelClosed();
        else
            doWithLock(lock.writeLock(), () -> {
                if (putQueue.size() < bufferSize)
                    request.setCompleted();
                putQueue.offer(request);
                processQueues();
            });
        return request;
    }

    /**
     * Gets and removes a value from the channel. If the channel contains no values, this call blocks until a value
     * becomes available.
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
        if (!isOpen())
            request.setChannelClosed();
        else
            doWithLock(lock.writeLock(), () -> {
                getQueue.offer(request);
                processQueues();
            });
        return request;
    }

    GetResult<T> getNonBlocking()
    {
        return doWithLock(lock.readLock(), () -> {
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
        doWithLock(lock.writeLock(), () -> {
            getQueue.remove(request);
        });
        request.setNoValue();
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
}
