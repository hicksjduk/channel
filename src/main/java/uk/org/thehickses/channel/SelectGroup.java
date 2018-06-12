package uk.org.thehickses.channel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import uk.org.thehickses.channel.Channel.GetRequest;

class SelectGroup
{
    private final List<GroupMember<?>> members = new ArrayList<>();
    private final AtomicReference<GetRequest<?>> selected = new AtomicReference<>();

    public <T> void addRequest(Channel<T> channel, GetRequest<T> request)
    {
        synchronized (members)
        {
            members.add(new GroupMember<>(channel, request));
        }
    }

    public boolean select(GetRequest<?> req)
    {
        if (!selected.compareAndSet(null, req))
            return false;
        new Thread(() -> cancelAllExcept(req)).start();
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
            members.stream().filter(m -> m.request != req).forEach(
                    m -> m.cancel());
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
