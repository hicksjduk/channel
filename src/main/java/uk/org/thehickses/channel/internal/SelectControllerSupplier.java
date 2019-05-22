package uk.org.thehickses.channel.internal;

import java.util.function.Function;

@FunctionalInterface
public interface SelectControllerSupplier<T> extends Function<GetRequest<T>, SelectController>
{
}
