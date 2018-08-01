package uk.org.thehickses.channel;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A class that emulates the action of a channel in the Go language.
 * 
 * @author Jeremy Hicks
 *
 * @param <T>
 *            the type of the objects that the channel holds.
 */
public class Channel<T>
{
    private final SelectGroupSupplier<T> nullSelectGroupSupplier = r -> null;
    private final int bufferSize;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Deque<GetRequest<T>> getQueue = new ArrayDeque<>();
    private final LinkedList<PutRequest<T>> putQueue = new LinkedList<>();
    private final AtomicBoolean closeWhenEmpty = new AtomicBoolean();

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
     * Closes the channel and signals all pending requests that it is closed.
     */
    public void close()
    {
        List<Request> requests = new LinkedList<>();
        synchronized (this)
        {
            if (!closed.compareAndSet(false, true))
                return;
            requests.addAll(getQueue);
            requests.addAll(putQueue);
            getQueue.clear();
            putQueue.clear();
        }
        requests.stream().forEach(r -> r.setChannelClosed());
    }

    /**
     * Tells the channel to close when all the existing values have been consumed.
     */
    public void closeWhenEmpty()
    {
        closeWhenEmpty.set(true);
        closeIfEmpty();
    }

    private synchronized void closeIfEmpty()
    {
        if (putQueue.isEmpty())
            close();
    }

    /**
     * Puts the specified value into the channel. This call blocks until the number of requests ahead of it in the put
     * queue reduces to less than the channel's buffer size, or the value is used to satisfy a get request.
     * 
     * @throws ChannelClosedException
     *             if the channel is closed at the time the request is made, or while the request is blocked.
     */
    public void put(T value) throws ChannelClosedException
    {
        putRequest(value).response().result();
    }

    /**
     * Puts the specified value into the channel, but only if the channel is open. This call blocks under the same
     * conditions as the put() method, but does not throw an exception if the channel is closed.
     * 
     * @return whether the channel was open, and therefore whether the value was actually put.
     */
    public boolean putIfOpen(T value)
    {
        try
        {
            put(value);
            return true;
        }
        catch (ChannelClosedException ex)
        {
            return false;
        }
    }

    public boolean isOpen()
    {
        return !closed.get();
    }

    /**
     * Processes values from the channel until it is closed.
     * 
     * @param processor
     *            the processor that is invoked to process each value. This processor may signal that the iteration
     *            should terminate by throwing a {@link RangeBreakException}.
     */
    public void range(Consumer<T> processor)
    {
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

    private synchronized PutRequest<T> putRequest(T value) throws ChannelClosedException
    {
        if (closed.get())
            throw new ChannelClosedException();
        PutRequest<T> request = new PutRequest<>(value);
        if (putQueue.size() < bufferSize)
            request.setCompleted();
        putQueue.offer(request);
        processQueues();
        return request;
    }

    /**
     * Gets and removes a value from the channel. If the channel contains no values, this call blocks until a value
     * becomes available. If the channel is closed (either at the time the request is made, or before a value becomes
     * available), a result is returned that contains no value.
     */
    public GetResult<T> get()
    {
        return getRequest(nullSelectGroupSupplier).response().result();
    }

    synchronized GetResult<T> getNonBlocking()
    {
        if (!isOpen())
            return new GetResult<>();
        if (putQueue.isEmpty())
            return null;
        return get();
    }

    synchronized GetRequest<T> getRequest(SelectGroupSupplier<T> selectGroupSupplier)
    {
        GetRequest<T> request = new GetRequest<>(selectGroupSupplier);
        if (closed.get())
        {
            request.setChannelClosed();
            return request;
        }
        getQueue.offer(request);
        processQueues();
        return request;
    }

    private synchronized void processQueues()
    {
        while (!getQueue.isEmpty() && !putQueue.isEmpty())
        {
            GetRequest<T> getRequest = getQueue.pop();
            if (getRequest.selectGroup != null && !getRequest.selectGroup.select(getRequest))
                continue;
            if (putQueue.size() > bufferSize)
                putQueue.get(bufferSize).setCompleted();
            PutRequest<T> putRequest = putQueue.pop();
            getRequest.setReturnedValue(putRequest.value);
        }
        if (closeWhenEmpty.get())
            closeIfEmpty();
    }

    void cancel(GetRequest<T> request)
    {
        synchronized (this)
        {
            getQueue.remove(request);
        }
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
        void result();
    }

    @FunctionalInterface
    static interface SelectGroupSupplier<T> extends Function<GetRequest<T>, SelectGroup>
    {
    }

    static class GetRequest<T> implements Request
    {
        private final CompletableFuture<GetResponse<T>> responder = new CompletableFuture<>();
        public final SelectGroup selectGroup;

        public GetRequest(SelectGroupSupplier<T> selectGroupSupplier)
        {
            this.selectGroup = selectGroupSupplier.apply(this);
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

        public GetResponse<T> response()
        {
            while (true)
            {
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
            responder.complete(() -> {throw new ChannelClosedException();});
        }

        public void setCompleted()
        {
            responder.complete(() -> {});
        }

        public PutResponse response()
        {
            while (true)
            {
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
    }

    @SuppressWarnings("serial")
    public static class RangeBreakException extends RuntimeException
    {
    }
}
