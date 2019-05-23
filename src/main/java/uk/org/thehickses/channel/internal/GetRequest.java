package uk.org.thehickses.channel.internal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class GetRequest<T> implements Request
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