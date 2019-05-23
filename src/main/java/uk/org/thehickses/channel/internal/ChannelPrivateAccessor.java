package uk.org.thehickses.channel.internal;

public interface ChannelPrivateAccessor<T>
{
    GetResult<T> getNonBlocking();
    GetResult<T> get(SelectControllerSupplier<T> selectControllerSupplier);
    void cancel(GetRequest<T> request);
}
