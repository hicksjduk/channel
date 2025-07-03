package uk.org.thehickses.channel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import uk.org.thehickses.channel.Channel.GetRequest;

class SelectGroup implements SelectController
{
    private final List<GroupMember<?>> members = new ArrayList<>();
    private final AtomicReference<GetRequest<?>> selected = new AtomicReference<>();
    private final AtomicInteger resultCount = new AtomicInteger();

    public <T> SelectGroup addMember(Channel<T> channel, GetRequest<T> request)
    {
        synchronized (members)
        {
            members.add(new GroupMember<>(channel, request));
        }
        return this;
    }

    @Override
    public boolean select(GetRequest<?> req)
    {
        if (!selected.compareAndSet(null, req))
            return false;
        ForkJoinPool.commonPool().execute(() -> cancelAllExcept(req));
        return true;
    }
    
    public boolean allResultsIn()
    {
        synchronized (members)
        {
            return resultCount.incrementAndGet() == members.size();
        }
    }

    public void cancel()
    {
        cancelAllExcept(selected.get());
    }

    private void cancelAllExcept(GetRequest<?> req)
    {
        synchronized (members)
        {
            members.stream().filter(m -> m.request != req).forEach(GroupMember::cancel);
        }
    }

    private static class GroupMember<T>
    {
        public final Channel<T> channel;
        public final GetRequest<T> request;

        public GroupMember(Channel<T> channel, GetRequest<T> request)
        {
            this.channel = channel;
            this.request = request;
        }

        public void cancel()
        {
            channel.cancel(request);
        }
    }
}
