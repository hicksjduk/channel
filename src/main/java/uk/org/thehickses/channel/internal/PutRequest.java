package uk.org.thehickses.channel.internal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class PutRequest<T> implements Request
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