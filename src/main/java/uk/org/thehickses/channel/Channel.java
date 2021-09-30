package uk.org.thehickses.channel;

import static uk.org.thehickses.locking.Locking.*;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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
    private final Status status = Status.OPEN;
    private final Deque<GetRequest<T>> getQueue = new ArrayDeque<>();
    private final LinkedList<PutRequest<T>> putQueue = new LinkedList<>();
    private final Lock lock = new ReentrantLock();

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
     * If the channel is not already closed, closes the channel and signals all blocked requests that it is closed.
     * Completed put requests remain in the put queue and remain available for retrieval.
     * 
     * @return whether this call closed the channel. A return value of false means that the channel was already closed.
     */
    public boolean close()
    {
        return doWithLock(lock, () ->
            {
                if (status == Status.CLOSED)
                    return false;
                Stream
                        .of(getQueue, putQueue)
                        .filter(q -> !q.isEmpty())
                        .map(q -> q.iterator())
                        .forEach(it ->
                            {
                                while (it.hasNext())
                                {
                                    Request req = it.next();
                                    if (!req.isComplete())
                                    {
                                        req.setChannelClosed();
                                        it.remove();
                                    }
                                }
                            });
                return true;
            });
    }

    public boolean isOpen()
    {
        return doWithLock(lock, () -> status != Status.CLOSED);
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
                if (!isOpen() && putQueue.isEmpty())
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
                if (putQueue.isEmpty())
                    return isOpen() ? null : new GetResult<>();
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

    private static interface Request
    {
        void setChannelClosed();

        boolean isComplete();
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

        @Override
        public boolean isComplete()
        {
            return responder.isDone();
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
        OPEN, CLOSED
    }
}
