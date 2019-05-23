package uk.org.thehickses.channel.internal;

@FunctionalInterface 
public interface GetResponse<T>
{
    GetResult<T> result();
}