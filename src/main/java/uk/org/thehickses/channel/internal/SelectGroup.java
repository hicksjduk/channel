package uk.org.thehickses.channel.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReference;

public class SelectGroup implements SelectController
{
    private final List<GroupMember<?>> members = new ArrayList<>();
    private final AtomicReference<GetRequest<?>> selected = new AtomicReference<>();

    public <T> SelectGroup addMember(ChannelPrivateAccessor<T> privateAccessor, GetRequest<T> request)
    {
        synchronized (members)
        {
            members.add(new GroupMember<>(privateAccessor, request));
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
}
