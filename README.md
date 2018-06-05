# Channel

As a Java programmer, when I learned the Go language I was somewhat underwhelmed. The only thing
that really impressed me was the ease and efficiency of concurrency using goroutines and channels.

Channels are a powerful way for concurrent routines to communicate; so this is my attempt to create
a Java emulation of the Go channel.

A channel is a first-in, first-out queue of data items of a particular type. 
They are most frequently used for communication and co-ordination between concurrent routines, 
as they are thread-safe.

In this implementation, a routine writes a data item to the channel by calling its put() method.
The channel has a maximum buffer size (which defaults to 0); any put() request which would
exceed the buffer size blocks until there is enough space in the buffer. (In the case of
a buffer size of 0, this means that a put() will block until a matching get() request is received.)

A routine reads from the channel by calling its get() method. This removes and returns the first
item in the channel; if the channel is empty, the call blocks until there is something there.
In Go, which supports multiple return values from a call,  
reading from a channel returns both the value read, and a flag which says whether a value was actually
read; this is simulated in Java by returning a GetResult object, in which there is a containsValue flag,
and a value which is only meaningful if containsValue is true.

Go provides an easy way to read and process values from a channel using its range operator.
This implementation likewise provides the range() method. You pass to this method a
Consumer of the data type handled by the channel, and it repeatedly calls the channel's
get() method, and passes each value retrieved to the specified Consumer, until the channel is
closed. 

A channel can only be used for communication until it is closed. Closing a channel is often used
to trigger a state change in the the process which reads the channel (it should terminate, or 
should move on to a different stage of its processing). Attempting to write to a closed channel
causes an exception to be thrown; this can be pre-empted by using the putIfOpen() method, which
only puts the value if the channel is open, and returns a flag to indicate whether that is the case.
Reading from a closed channel returns a result with containsValue
set to false. Closing an already-closed channel has no effect.
