package uk.org.thehickses.channel;

import java.util.function.Function;

import uk.org.thehickses.channel.Channel.GetRequest;

@FunctionalInterface
public interface SelectControllerSupplier<T> extends Function<GetRequest<T>, SelectController>
{
}
