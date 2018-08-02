package uk.org.thehickses.channel;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import uk.org.thehickses.channel.Channel.GetRequest;

class SelectGroup
{
    private final List<GroupMember<?>> members = new ArrayList<>();
    private final AtomicReference<GetRequest<?>> selected = new AtomicReference<>();

    public <T> SelectGroup addMember(Channel<T> channel, GetRequest<T> request)
    {
        synchronized (members)
        {
            members.add(new GroupMember<>(channel, request));
        }
        return this;
    }

    public boolean select(GetRequest<?> req)
    {
        if (!selected.compareAndSet(null, req))
            return false;
        new Thread(() -> cancelAllExcept(req)).start();
        return true;
    }

    public void removeMember(GetRequest<?> req)
    {
        synchronized (members)
        {
            OptionalInt index = IntStream
                    .range(0, members.size())
                    .filter(i -> members.get(i).request == req)
                    .findFirst();
            if (index.isPresent())
                members.remove(index.getAsInt());
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
