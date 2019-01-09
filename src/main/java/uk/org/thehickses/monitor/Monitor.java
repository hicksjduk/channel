package uk.org.thehickses.monitor;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.org.thehickses.channel.Channel;
import uk.org.thehickses.idgenerator.IdGenerator;
import uk.org.thehickses.listeners.Listener;
import uk.org.thehickses.listeners.Listeners;

public class Monitor implements Executor
{
    private final static Logger LOG = LoggerFactory.getLogger(Monitor.class);

    private final IdGenerator processIdGenerator = new IdGenerator(0, Integer.MAX_VALUE);
    private final BiConsumer<Runnable, Integer> executor;
    private final Set<Integer> activeProcessIds = new HashSet<>();
    private final Listeners<Listener<Integer>, Integer> listeners = Listeners.newInstance(5);

    public Monitor()
    {
        this.executor = (r, pid) -> {
            String threadName = String.format("Process %s", formatProcessId(pid));
            new Thread(r, threadName).start();
        };
    }

    @Override
    public void execute(Runnable process)
    {
        runMonitored(process);
    }

    private void runMonitored(Runnable process)
    {
        int processId = processStarting();
        executor.accept(wrappedProcess(process, processId), processId);
    }

    private int processStarting()
    {
        int processId = processIdGenerator.allocateId();
        synchronized (activeProcessIds)
        {
            activeProcessIds.add(processId);
        }
        return processId;
    }

    private Runnable wrappedProcess(Runnable process, int processId)
    {
        return () -> {
            LOG.debug("Monitored process starting");
            try
            {
                process.run();
            }
            catch (Throwable ex)
            {
                LOG.error("Unexpected error", ex);
            }
            processEnded(processId);
            LOG.debug("Monitored process finished");
        };
    }

    private void processEnded(int processId)
    {
        listeners.fire(processId);
        synchronized (activeProcessIds)
        {
            activeProcessIds.remove(processId);
        }
        processIdGenerator.freeId(processId);
    }

    public void waitForActiveProcesses()
    {
        Set<Integer> processIds;
        Channel<Integer> ch;
        Listener<Integer> listener;
        synchronized (activeProcessIds)
        {
            if (activeProcessIds.isEmpty())
                return;
            processIds = new HashSet<>(activeProcessIds);
            ch = new Channel<>(processIds.size());
            listeners.addOrUpdateListener(listener = ch::put, processIds::contains);
        }
        while (!processIds.isEmpty())
        {
            LOG.debug("Waiting for process(es) {} to terminate",
                    Arrays.toString(processIds.stream().map(Monitor::formatProcessId).toArray()));
            processIds.remove(ch.get().value);
        }
        LOG.debug("All processes terminated");
        listeners.removeListener(listener);
    }

    private static String formatProcessId(int processId)
    {
        return String.format("%08X", processId);
    }
}
