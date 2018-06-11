package uk.org.thehickses.channel;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import uk.org.thehickses.channel.Channel.GetRequest;

class SelectGroup
{
    private final Map<GetRequest<?>, GroupMember<?>> membersByRequest = new HashMap<>();
    private final AtomicReference<GetRequest<?>> selected = new AtomicReference<>();

    public <T> void addRequest(Channel<T> channel, GetRequest<T> request)
    {
        synchronized (membersByRequest)
        {
            membersByRequest.put(request, new GroupMember<>(channel, request));
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
        synchronized (membersByRequest)
        {
            membersByRequest.entrySet().stream().filter(e -> e.getKey() != req).forEach(
                    e -> e.getValue().cancel());
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
