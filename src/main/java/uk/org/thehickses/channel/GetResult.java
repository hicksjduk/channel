package uk.org.thehickses.channel;

public class GetResult<T>
{
    public final T value;
    public final boolean containsValue;
    
    public GetResult()
    {
        value = null;
        containsValue = false;
    }
    
    public GetResult(T value)
    {
        this.value = value;
        containsValue = true;
    }
}
