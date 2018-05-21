package uk.org.thehickses.channel;

import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class Select
{
    private final List<Supplier<Boolean>> cases = new LinkedList<>();
    private Runnable defaultCase = null;

    public <T> Select withCase(Channel<T> channel, Consumer<T> processor)
    {
        cases.add(() -> {
            GetResult<T> result = channel.getNonBlocking();
            if (result.containsValue)
                processor.accept(result.value);
            return result.containsValue;
        });
        return this;
    }
    
    public Select withDefaultCase(Runnable processor)
    {
        defaultCase = processor;
        return this;
    }
    
    public void runSelect()
    {
        
    }
}
