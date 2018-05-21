package uk.org.thehickses.channel;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

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
            closed.set(true);
            requests.addAll(getQueue);
            requests.addAll(putQueue);
            getQueue.clear();
            putQueue.clear();
        }
        requests.stream().forEach(r -> r.setChannelClosed());
    }

    /**
     * Gets whether the channel is open.
     */
    public boolean isOpen()
    {
        return !closed.get();
    }

    /**
     * Puts the specified value into the channel. This call blocks until the number of requests ahead of it in the put
     * queue reduces to less than the channel's buffer size.
     * 
     * @throws ChannelClosedException
     *             if the channel is closed.
     */
    public void put(T value) throws ChannelClosedException
    {
        doPut(value).response();
    }

    private synchronized PutRequest<T> doPut(T value) throws ChannelClosedException
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
     * Gets a value from the queue, without blocking. If the channel is closed, or no value is available to satisfy the
     * request, an result is returned that contains no value.
     */
    public GetResult<T> getNonBlocking()
    {
        synchronized (this)
        {
            return putQueue.isEmpty() ? new GetResult<>() : get();
        }
    }

    /**
     * Gets a value from the queue. If no value is available in the channel to satisfy the request, this call blocks
     * until a value becomes available. If the channel is closed (either at the time the request is made, or before a
     * value becomes available), an empty request is returned that contains no value.
     */
    public GetResult<T> get()
    {
        return doGet().response().result();
    }

    private synchronized GetRequest<T> doGet()
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

    private static interface Request
    {
        void setChannelClosed();
    }

    @FunctionalInterface
    private static interface GetResponse<T>
    {
        GetResult<T> result();
    }

    private static class PutResponse
    {
    }

    private static class GetRequest<T> implements Request
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
