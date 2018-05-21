# Channel

As a Java programmer, when I learned the Go language I was somewhat underwhelmed. The only thing
that really impressed me was the ease and efficiency of concurrency using goroutines and channels.

Channels are a powerful way for concurrent routines to communicate; so this is my attempt to create
a Java emulation of the Go channel.

A channel is a first-in, first-out queue of data items. They are most frequently used in Go for
communication and co-ordination between concurrent routines, as they are thread-safe.

In this implementation, a routine writes a data item to the channel by calling its put() method.
The channel has a maximum buffer size (which defaults to 0); any put() request which would
exceed the buffer size blocks until there is enough space in the buffer.

A routine reads from the channel by calling its get() method. This removes and returns the first
item in the channel; if the channel is empty, the call blocks until there is something there.
In Go, which supports multiple return values from a call,  
reading from a channel return both the value read, and a flag which says whether a value was actually
read; this is simulated in Java by returning a GetResult with a containsValue flag, and a value
which is only meaningful if containsValue is true.

A channel can only be used for communication until it is closed. Closing a channel is often used
to trigger a state change in the the process which reads the channel (it should terminate, or 
should move on to a different stage of its processing). Attempting to write to a closed channel
causes an exception to be thrown. Reading from a closed channel returns a result with containsValue
set to false.
