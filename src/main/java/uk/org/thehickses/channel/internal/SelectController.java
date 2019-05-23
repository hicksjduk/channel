package uk.org.thehickses.channel.internal;

@FunctionalInterface
public interface SelectController
{
    boolean select(GetRequest<?> req);
}
