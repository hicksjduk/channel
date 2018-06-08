package uk.org.thehickses.channel;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
    private final int bufferSize;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Deque<GetRequest<T>> getQueue = new ArrayDeque<>();
    private final LinkedList<PutRequest<T>> putQueue = new LinkedList<>();

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
     * Puts the specified value into the channel. This call blocks until the number of requests ahead of it in the put
     * queue reduces to less than the channel's buffer size, or the value is used to satisfy a get request.
     * 
     * @throws ChannelClosedException
     *             if the channel is closed at the time the request is made.
     */
    public void put(T value) throws ChannelClosedException
    {
        putRequest(value).response();
    }

    /**
     * Puts the specified value into the channel, but only if the channel is open. This call blocks under the same
     * conditions as the put() method, but does not throw an exception if the channel is closed.
     * 
     * @return whether the channel was open, and therefore whether the value was actually put.
     */
    public boolean putIfOpen(T value)
    {
        synchronized (this)
        {
            if (closed.get())
                return false;
            put(value);
            return true;
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
     *            the processor that is invoked to process each value.
     */
    public void range(Consumer<T> processor)
    {
        GetResult<T> result;
        while ((result = get()).containsValue)
            processor.accept(result.value);
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
        return getRequest().response().result();
    }

    synchronized GetRequest<T> getRequest()
    {
        if (closed.get())
            return new GetRequest<>();
        GetRequest<T> request = new GetRequest<>();
        getQueue.offer(request);
        processQueues();
        return request;
    }

    private synchronized void processQueues()
    {
        if (getQueue.isEmpty() || putQueue.isEmpty())
            return;
        GetRequest<T> getRequest = getQueue.pop();
        if (putQueue.size() > bufferSize)
            putQueue.get(bufferSize).setCompleted();
        PutRequest<T> putRequest = putQueue.pop();
        getRequest.setReturnedValue(putRequest.value);
    }
    
    void cancel(GetRequest<T> request)
    {
        synchronized(this)
        {
            getQueue.remove(request);
        }
        request.setChannelClosed();
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

    private static class PutResponse
    {
    }

    static class GetRequest<T> implements Request
    {
        private final CompletableFuture<GetResponse<T>> responder = new CompletableFuture<>();

        @Override
        public void setChannelClosed()
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
            responder.complete(new PutResponse());
        }

        public void setCompleted()
        {
            responder.complete(new PutResponse());
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
}
