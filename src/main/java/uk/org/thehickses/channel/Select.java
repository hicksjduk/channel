package uk.org.thehickses.channel;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

import uk.org.thehickses.channel.internal.ChannelCase;

/**
 * A Java implementation of the Go select statement, for reading multiple channels.
 * 
 * @author Jeremy Hicks
 */
public class Select
{
    /**
     * Creates a selecter which runs a case to read the specified channel, and process the retrieved value if there is
     * one.
     */
    public static <T> SelecterWithoutDefault withCase(Channel<T> channel,
            Consumer<? super T> processor)
    {
        Stream.of(channel, processor).forEach(Objects::requireNonNull);
        return new SelecterWithoutDefault(new ChannelCase<>(channel.privateAccessor(), processor));
    }
}
