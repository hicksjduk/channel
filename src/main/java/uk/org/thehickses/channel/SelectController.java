package uk.org.thehickses.channel;

import uk.org.thehickses.channel.Channel.GetRequest;

@FunctionalInterface
public interface SelectController
{
    boolean select(GetRequest<?> req);
}
