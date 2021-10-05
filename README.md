# Channels

As a Java programmer, when I learned the Go language I was somewhat underwhelmed. The only thing
that really impressed me was the ease and efficiency of concurrency using goroutines and channels.

Channels are a powerful way for concurrent routines to communicate; so this is my attempt to create
a Java emulation of the Go channel.

## Channel

A channel is a first-in, first-out queue of data items of a particular type. 
Channels are most frequently used for communication and co-ordination between concurrent routines, 
as they are thread-safe.

### Reading from and and writing to a channel

In this implementation, a routine writes a data item to the channel by calling its `put()` method.
The channel has a maximum buffer size (which defaults to 0); any `put()` request which would
exceed the buffer size blocks until there is enough space in the buffer. (In the case of
a buffer size of 0, this means that a `put()` will block until a matching `get()` request is received.)

A routine reads from the channel by calling its `get()` method. This removes and returns the first
item in the channel; if the channel is empty, the call blocks until there is something there or the channel
is closed.
In Go, which supports multiple return values from a call, reading from a channel returns both the value read, and a flag which says whether a value was actually read (which is not the case if the channel was closed and empty);
this is simulated in Java by returning a `GetResult` object, in which there is a `containsValue` flag
and a `value` which is only meaningful if `containsValue` is `true`.

### Iterating over a channel

A channel implements the `Iterable` interface, which means that it is possible to iterate over a channel using the Java for-each loop.

A channel also provides a `stream()` method, which creates a `Stream` over the values retrieved from the channel.

### Closing a channel

A channel can only be written to until it is closed, which is done by calling its
`close()` method.
Closing a channel is often used
to trigger a state change in the process which reads the channel (it should terminate, or 
should move on to a different stage of its processing).
 
Closing an already-closed channel has no effect; the `close()` method 
returns a flag to indicate whether it actually closed the channel.

Attempting to write to a closed channel
has no effect; the `put()` method
only puts the value if the channel is open, and returns a flag to indicate whether that was the case.

The result of reading from a closed channel has `containsValue`
set to `true` (and `value` to a meaningful value) if the channel was not empty, or to `false` if it was empty. 

**Note** that in relation to closed channels, this implementation
differs from Go in the following ways:
* You can query whether a channel
is open via the `isOpen()` method.
* It is not an error to write to or close a channel that is closed; the `put()` and `close()`
methods have no effect on the channel if called on a closed channel, and return whether they
actually changed the channel. (In Go, writing to or closing a closed channel causes a panic, which
is how Go communicates that something has gone very wrong.)

## Channel examples

Some examples of Go code using channels, and their Java equivalents using this library:

### Create a channel

**Go**

```go
unbuffered := make(chan string)
buffered := make(chan string, 20)
```

**Java**

```java
Channel<String> unbuffered = new Channel<>();
Channel<String> buffered = new Channel<>(20);
```

### Read from a channel, check whether a value was read, and print it to stdout if so

**Go**

```go
if v, ok := <- ch; ok {
	fmt.Println(v)
}
```

**Java**

```java
GetResult<String> res = ch.get();
if (res.containsValue) 
	System.out.println(res.value);
```

### Write a value to a channel

**Go**

```go
ch <- value
```

**Java**

```java
ch.put(value);
```

### Iterate a channel until it is closed, or the value encountered is "stop"

**Go**

```go
for value := range ch {
	// Process value
	if value == "stop" {
		break
	}
}
```

**Java**

```java
for (String value : ch) {
	// Process value
	if (value.equals("stop"))
		break;
}
```

### Close a channel

**Go**

```go
close(ch)
```

**Java**

```java
ch.close();
```

## Select

Go provides a special `select` statement which allows for multiple channels to be read
at the same time; whenever a value is read from any of the specified channels, it is processed and
the select completes. The select may also optionally have a `default` clause, in which
case the `default` clause is executed, and the select completes, even if none of the specified channels
has an available value. Without a `default` clause, the select blocks until a value is available on one
of the channels, or all the channels are closed.

An equivalent Java implementation of this is also provided in this package. Shown below are a Go `select`
statement and its Java equivalent:

**Go**  

```go
func doSelect(channelA chan int, channelB chan bool, channelC chan string) {
	select {
	case value := <-channelA:
		// process value which is an int  
	case value := <-channelB:
		// process value which is a bool
	case value := <-channelC:
		// process value which is a string
	default:
		// do default processing
	}
}
```

**Java**  

```java
void doSelect(Channel<Integer> channelA, Channel<Boolean> channelB, Channel<String> channelC)
{
	Select.withCase(channelA, value -> {
		// process value which is an Integer  
	}).withCase(channelB, value -> {
		// process value which is a Boolean  
	}).withCase(channelC, value -> {
		// process value which is a String  
	}).withDefault(() -> {
		// do default processing
	}).run();
}
```

**Note** that this implementation of the select differs from Go in how it behaves when all the selected
channels are closed. In these circumstances, Go executes the handler for one of the cases, and which handler that is 
is apparently chosen at random. The Java implementation executes no handler at all, not even the default handler
if one is specified, and the `run()` method returns a boolean result - `true` if any case handler or the default
handler was run, and `false` if none was run because all the channels are closed. 
The execution of a random case 
handler in Go is useful only in allowing the code to detect the case where all channels are closed, though since 
the choice of handler is non-deterministic, all handlers must cater for that possibility. 
I think it is arguable that the Java implementation presented here is an improvement as it separates the closure
concern from the processing of values.