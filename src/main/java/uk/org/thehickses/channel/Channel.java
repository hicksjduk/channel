package uk.org.thehickses.channel;

import static uk.org.thehickses.locking.Locking.*;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Optional;
import java.util.Spliterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A class that emulates a channel in the Go language. Note that a channel may not hold null values; if it is desired to
 * put nulls in a channel, it is recommended to wrap them in an {@link Optional Optional}.
 * 
 * @author Jeremy Hicks
 *
 * @param <T>
 *            the type of the objects that the channel holds.
 */
public class Channel<T> implements Iterable<T>
{
    private final int bufferSize;
    private Status status = Status.OPEN;
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
     * Completed put requests remain in the put queue and available for retrieval. Blocked put requests complete, but do
     * not put their values in the channel.
     * 
     * @return whether this call closed the channel. A return value of false means that the channel was already closed.
     */
    public boolean close()
    {
        var blockedRequests = doWithLock(lock, this::closeAndGetBlockedRequests);
        if (blockedRequests != null)
            blockedRequests.forEach(Request::setChannelClosed);
        return blockedRequests != null;
    }

    /**
     * If the channel is open, closes it and returns a stream of all blocked requests if there are any. If it is already
     * closes, returns a null stream.
     * 
     * @return a stream of blocked requests. May be null (the stream was already closed) or empty (there are no blocked
     *         requests).
     */
    private Stream<Request> closeAndGetBlockedRequests()
    {
        if (status == Status.CLOSED)
            return null;
        status = Status.CLOSED;
        var blockedRequests = new LinkedList<Request>();
        if (!getQueue.isEmpty())
            blockedRequests.addAll(getQueue);
        else
            IntStream.range(bufferSize, putQueue.size())
                    .forEach(i -> blockedRequests.add(putQueue.removeLast()));
        return blockedRequests.stream();
    }

    /**
     * Gets whether the channel is open.
     * 
     * @return whether the channel is open.
     */
    public boolean isOpen()
    {
        return doWithLock(lock, () -> status != Status.CLOSED);
    }

    /**
     * Puts the specified value into the channel, as long as it is open. This call blocks until the number of requests
     * ahead of it in the put queue reduces to less than the channel's buffer size, or the value is used to satisfy a
     * get request.
     * 
     * @param value
     *            the value to put. May not be null.
     * 
     * @return whether the value was put. A value of false means that the channel was closed at the time the request was
     *         made, or became closed while the request was blocked.
     */
    public boolean put(T value)
    {
        Objects.requireNonNull(value);
        return putRequest(value).result();
    }

    /**
     * Creates a put request, and if the channel is open adds it to the put queue and processes the queues to see if any
     * blocked requests can be completed.
     * 
     * @param value
     *            the value to put.
     * @return the put request.
     */
    private PutRequest<T> putRequest(T value)
    {
        var request = new PutRequest<>(value);
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
     *         the result is empty; otherwise it contains the value retrieved.
     */
    public Optional<T> get()
    {
        return get(null);
    }

    /**
     * Gets and removes a value from the channel, under the control of a {@link SelectController} supplied by the
     * specified object.
     * 
     * @param selectControllerSupplier
     *            the object that supplies a SelectController.
     * @return the result. If the channel was closed, either at the time of the call or while the request was blocked,
     *         the result is empty; otherwise it contains the value retrieved.
     */
    Optional<T> get(SelectControllerSupplier<T> selectControllerSupplier)
    {
        return getRequest(selectControllerSupplier).result();
    }

    /**
     * Creates a get request, and if the channel is open or not empty adds it to the get queue and processes the queues
     * to see if any blocked requests can be completed.
     * 
     * @param selectControllerSupplier
     *            a {@link SelectControllerSupplier}.
     * @return the get request.
     */
    private GetRequest<T> getRequest(SelectControllerSupplier<T> selectControllerSupplier)
    {
        var request = new GetRequest<>(selectControllerSupplier);
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

    /**
     * Does a non-blocking get. This is the same as a standard get, except that if a get request would block (the
     * channel is open and empty), a null result is returned.
     * 
     * @return the result of the get, or null if the channel is open and empty.
     */
    Optional<T> getNonBlocking()
    {
        return doWithLock(lock, () ->
            {
                if (putQueue.isEmpty() && isOpen())
                    return null;
                return get();
            });
    }

    /**
     * Processes the get and put queues, until at least one of them is empty, completing as many blocked requests as
     * possible.
     */
    private void processQueues()
    {
        while (!getQueue.isEmpty() && !putQueue.isEmpty())
        {
            var getRequest = getQueue.pop();
            if (!getRequest.isSelectable())
                continue;
            if (putQueue.size() > bufferSize)
                putQueue.get(bufferSize)
                        .setCompleted();
            var putRequest = putQueue.pop();
            getRequest.setReturnedValue(putRequest.value);
        }
    }

    /**
     * Cancels the specified get request, by removing it from the get queue and completing it with no value if it is
     * blocked
     * 
     * @param request
     *            the request
     */
    void cancel(GetRequest<T> request)
    {
        if (request.isComplete())
            return;
        doWithLock(lock, () -> getQueue.remove(request));
        request.setNoValue();
    }

    /**
     * Gets a {@link Stream} which contains the values retrieved from the channel.
     * 
     * @return the stream.
     */
    public Stream<T> stream()
    {
        return StreamSupport.stream(spliterator(), false);
    }

    @Override
    public Iterator<T> iterator()
    {
        return stream().iterator();
    }

    @Override
    public Spliterator<T> spliterator()
    {
        return new ChannelSpliterator();
    }

    private class ChannelSpliterator implements Spliterator<T>
    {
        @Override
        public boolean tryAdvance(Consumer<? super T> action)
        {
            return get().map(v ->
                {
                    action.accept(v);
                    return true;
                })
                    .orElse(false);
        }

        @Override
        public Spliterator<T> trySplit()
        {
            return null;
        }

        @Override
        public long estimateSize()
        {
            return doWithLock(lock,
                    () -> status == Status.CLOSED ? putQueue.size() : Long.MAX_VALUE);
        }

        @Override
        public int characteristics()
        {
            return CONCURRENT & ORDERED;
        }
    }

    private static interface Request
    {
        void setChannelClosed();

        boolean isComplete();
    }

    private static <T> T getResult(CompletableFuture<T> cf)
    {
        while (true)
            try
            {
                return cf.get();
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

    static class GetRequest<T> implements Request
    {
        private final CompletableFuture<Optional<T>> result = new CompletableFuture<>();
        private final SelectController selectController;

        public GetRequest(SelectControllerSupplier<T> supplier)
        {
            this.selectController = Optional.ofNullable(supplier)
                    .map(s -> s.apply(this))
                    .orElse(r -> true);
        }

        @Override
        public void setChannelClosed()
        {
            setNoValue();
        }

        public void setNoValue()
        {
            result.complete(Optional.empty());
        }

        public void setReturnedValue(T value)
        {
            result.complete(Optional.of(value));
        }

        public boolean isSelectable()
        {
            return selectController.select(this);
        }

        @Override
        public boolean isComplete()
        {
            return result.isDone();
        }

        public Optional<T> result()
        {
            return getResult(result);
        }
    }

    private static class PutRequest<T> implements Request
    {
        public final T value;
        private final CompletableFuture<Boolean> result = new CompletableFuture<>();

        public PutRequest(T value)
        {
            this.value = value;
        }

        @Override
        public void setChannelClosed()
        {
            result.complete(false);
        }

        @Override
        public boolean isComplete()
        {
            return result.isDone();
        }

        public void setCompleted()
        {
            result.complete(true);
        }

        public boolean result()
        {
            return getResult(result);
        }
    }

    private static enum Status
    {
        OPEN, CLOSED
    }
}
