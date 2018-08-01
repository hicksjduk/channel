# Channels

As a Java programmer, when I learned the Go language I was somewhat underwhelmed. The only thing
that really impressed me was the ease and efficiency of concurrency using goroutines and channels.

Channels are a powerful way for concurrent routines to communicate; so this is my attempt to create
a Java emulation of the Go channel.

## Channel

A channel is a first-in, first-out queue of data items of a particular type. 
They are most frequently used for communication and co-ordination between concurrent routines, 
as they are thread-safe.

In this implementation, a routine writes a data item to the channel by calling its `put()` method.
The channel has a maximum buffer size (which defaults to 0); any `put()` request which would
exceed the buffer size blocks until there is enough space in the buffer. (In the case of
a buffer size of 0, this means that a `put()` will block until a matching `get()` request is received.)

A routine reads from the channel by calling its `get()` method. This removes and returns the first
item in the channel; if the channel is empty, the call blocks until there is something there or the channel
is closed.
In Go, which supports multiple return values from a call, reading from a channel returns both the value read, and a flag which says whether a value was actually read (which may not be the case if the channel was closed);
this is simulated in Java by returning a `GetResult` object, in which there is a `containsValue` flag,
and a `value` which is only meaningful if `containsValue` is `true`.

Go provides an easy way to read and process values from a channel using its `range` operator.
This implementation likewise provides the `range()` method. You pass to this method a
`Consumer` of the data type handled by the channel, and it repeatedly calls the channel's
`get()` method, and passes each value retrieved to the specified `Consumer`, until the channel is
closed. In a Go range statement, the iteration can be explicitly terminated by a `break`, `continue` or
`return` statement within the code that processes the value. In Java, this is not possible because the processing
happens in a separate routine; however, explicit termination can be achieved by throwing a
`RangeBreakException` within the processing code.

A channel can only be used for communication until it is closed, which is done by calling its
`close()` method. Closing a channel is often used
to trigger a state change in the process which reads the channel (it should terminate, or 
should move on to a different stage of its processing). Attempting to write to a closed channel
causes an exception to be thrown; this can be pre-empted by using the `putIfOpen()` method, which
only puts the value if the channel is open, and returns a flag to indicate whether that was the case.
Reading from a closed channel returns a result with `containsValue`
set to false. Closing an already-closed channel has no effect.

**Note** that in relation to closed channels, this implementation
differs from Go in the following ways:
* You can query whether a channel
is open via the `isOpen()` method.
* You can use `putIfOpen()` to write safely to a channel that
might be closed.
* It is not an error to close a channel that is already closed.

Some examples of Go code using channels, and their Java equivalents:

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
if v, ok := <-ch; ok {
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
ch<- value
``` 

**Java**
```java
ch.put(value);
``` 

### Range over a channel (process values in turn until the channel is closed), terminating the iteration if the value is "stop"

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
ch.range(value -> {
	// Process value
	if (value.equals("stop"))
		throw new RangeBreakException();
});
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