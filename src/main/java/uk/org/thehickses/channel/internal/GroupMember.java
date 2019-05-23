package uk.org.thehickses.channel.internal;

public class GroupMember<T>
{
    public final ChannelPrivateAccessor<T> privateAccessor;
    public final GetRequest<T> request;

    public GroupMember(ChannelPrivateAccessor<T> privateAccessor, GetRequest<T> request)
    {
        this.privateAccessor = privateAccessor;
        this.request = request;
    }

    public void cancel()
    {
        privateAccessor.cancel(request);
    }
}