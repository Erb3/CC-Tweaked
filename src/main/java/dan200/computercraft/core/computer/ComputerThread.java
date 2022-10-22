/*
 * This file is part of ComputerCraft - http://www.computercraft.info
 * Copyright Daniel Ratcliffe, 2011-2022. Do not distribute without permission.
 * Send enquiries to dratcliffe@gmail.com
 */
package dan200.computercraft.core.computer;

import dan200.computercraft.ComputerCraft;
import dan200.computercraft.shared.util.ThreadUtils;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Responsible for running all tasks from a {@link Computer}.
 * <p>
 * This is split into two components: the {@link TaskRunner}s, which pull an executor from the queue and execute it, and
 * a single {@link Monitor} which observes all runners and kills them if they have not been terminated by
 * {@link TimeoutState#isSoftAborted()}.
 * <p>
 * Computers are executed using a priority system, with those who have spent less time executing having a higher
 * priority than those hogging the thread. This, combined with {@link TimeoutState#isPaused()} means we can reduce the
 * risk of badly behaved computers stalling execution for everyone else.
 * <p>
 * This is done using an implementation of Linux's Completely Fair Scheduler. When a computer executes, we compute what
 * share of execution time it has used (time executed/number of tasks). We then pick the computer who has the least
 * "virtual execution time" (aka {@link ComputerExecutor#virtualRuntime}).
 * <p>
 * When adding a computer to the queue, we make sure its "virtual runtime" is at least as big as the smallest runtime.
 * This means that adding computers which have slept a lot do not then have massive priority over everyone else. See
 * {@link #queue(ComputerExecutor)} for how this is implemented.
 * <p>
 * In reality, it's unlikely that more than a few computers are waiting to execute at once, so this will not have much
 * effect unless you have a computer hogging execution time. However, it is pretty effective in those situations.
 *
 * @see TimeoutState For how hard timeouts are handled.
 * @see ComputerExecutor For how computers actually do execution.
 */
public final class ComputerThread
{
    private static final ThreadFactory monitorFactory = ThreadUtils.factory( "Computer-Monitor" );
    private static final ThreadFactory runnerFactory = ThreadUtils.factory( "Computer-Runner" );

    /**
     * How often the computer thread monitor should run.
     *
     * @see Monitor
     */
    private static final long MONITOR_WAKEUP = TimeUnit.MILLISECONDS.toNanos( 100 );

    /**
     * The target latency between executing two tasks on a single machine.
     * <p>
     * An average tick takes 50ms, and so we ideally need to have handled a couple of events within that window in order
     * to have a perceived low latency.
     */
    private static final long DEFAULT_LATENCY = TimeUnit.MILLISECONDS.toNanos( 50 );

    /**
     * The minimum value that {@link #DEFAULT_LATENCY} can have when scaled.
     * <p>
     * From statistics gathered on SwitchCraft, almost all machines will execute under 15ms, 75% under 1.5ms, with the
     * mean being about 3ms. Most computers shouldn't be too impacted with having such a short period to execute in.
     */
    private static final long DEFAULT_MIN_PERIOD = TimeUnit.MILLISECONDS.toNanos( 5 );

    /**
     * The maximum number of tasks before we have to start scaling latency linearly.
     */
    private static final long LATENCY_MAX_TASKS = DEFAULT_LATENCY / DEFAULT_MIN_PERIOD;

    /**
     * Time difference between reporting crashed threads.
     *
     * @see TaskRunner#reportTimeout(ComputerExecutor, long)
     */
    private static final long REPORT_DEBOUNCE = TimeUnit.SECONDS.toNanos( 1 );

    /**
     * Lock used for modifications to the array of current threads.
     */
    private final Object threadLock = new Object();

    /**
     * Whether the computer thread system is currently running.
     */
    private volatile boolean running = false;

    /**
     * The current task manager.
     */
    private @Nullable Thread monitor;

    /**
     * The array of current runners, and their owning threads.
     */
    private final TaskRunner[] runners;

    private final long latency;
    private final long minPeriod;

    private final ReentrantLock computerLock = new ReentrantLock();

    private final Condition hasWork = computerLock.newCondition();
    private final AtomicInteger idleWorkers = new AtomicInteger( 0 );
    private final Condition monitorWakeup = computerLock.newCondition();

    /**
     * Active queues to execute.
     */
    private final TreeSet<ComputerExecutor> computerQueue = new TreeSet<>( ( a, b ) -> {
        if( a == b ) return 0; // Should never happen, but let's be consistent here

        long at = a.virtualRuntime, bt = b.virtualRuntime;
        if( at == bt ) return Integer.compare( a.hashCode(), b.hashCode() );
        return at < bt ? -1 : 1;
    } );

    /**
     * The minimum {@link ComputerExecutor#virtualRuntime} time on the tree.
     */
    private long minimumVirtualRuntime = 0;

    public ComputerThread( int threadCount )
    {
        runners = new TaskRunner[threadCount];

        // latency and minPeriod are scaled by 1 + floor(log2(threads)). We can afford to execute tasks for
        // longer when executing on more than one thread.
        int factor = 64 - Long.numberOfLeadingZeros( runners.length );
        latency = DEFAULT_LATENCY * factor;
        minPeriod = DEFAULT_MIN_PERIOD * factor;
    }

    /**
     * Start the computer thread.
     */
    void start()
    {
        synchronized( threadLock )
        {
            running = true;
            for( int i = 0; i < runners.length; i++ )
            {
                TaskRunner runner = runners[i];
                if( runner == null || runner.owner == null || !runner.owner.isAlive() )
                {
                    // Mark the old runner as dead, just in case.
                    if( runner != null ) runner.running = false;
                    // And start a new runner
                    runnerFactory.newThread( runners[i] = new TaskRunner() ).start();
                }
            }

            if( monitor == null || !monitor.isAlive() ) (monitor = monitorFactory.newThread( new Monitor() )).start();
        }
    }

    /**
     * Attempt to stop the computer thread. This interrupts each runner, and clears the task queue.
     */
    public void stop()
    {
        synchronized( threadLock )
        {
            running = false;
            for( TaskRunner runner : runners )
            {
                if( runner == null ) continue;

                runner.running = false;
                if( runner.owner != null ) runner.owner.interrupt();
            }

            if( monitor != null ) monitor.interrupt();
        }

        computerLock.lock();
        try
        {
            computerQueue.clear();
        }
        finally
        {
            computerLock.unlock();
        }

        synchronized( threadLock )
        {
            if( monitor != null ) tryJoin( monitor );
            for( TaskRunner runner : runners )
            {
                if( runner != null && runner.owner != null ) tryJoin( runner.owner );
            }
        }
    }

    private static void tryJoin( Thread thread )
    {
        try
        {
            thread.join( 100 );
        }
        catch( InterruptedException e )
        {
            throw new IllegalStateException( "Interrupted server thread while trying to stop " + thread.getName(), e );
        }

        if( thread.isAlive() ) ComputerCraft.log.error( "Failed to stop {}", thread.getName() );
    }

    /**
     * Mark a computer as having work, enqueuing it on the thread.
     * <p>
     * You must be holding {@link ComputerExecutor}'s {@code queueLock} when calling this method - it should only
     * be called from {@code enqueue}.
     *
     * @param executor The computer to execute work on.
     */
    void queue( ComputerExecutor executor )
    {
        computerLock.lock();
        try
        {
            if( executor.onComputerQueue ) throw new IllegalStateException( "Cannot queue already queued executor" );
            executor.onComputerQueue = true;

            updateRuntimes( null );

            // We're not currently on the queue, so update its current execution time to
            // ensure its at least as high as the minimum.
            long newRuntime = minimumVirtualRuntime;

            if( executor.virtualRuntime == 0 )
            {
                // Slow down new computers a little bit.
                newRuntime += scaledPeriod();
            }
            else
            {
                // Give a small boost to computers which have slept a little.
                newRuntime -= latency / 2;
            }

            executor.virtualRuntime = Math.max( newRuntime, executor.virtualRuntime );

            boolean wasBusy = isBusy();
            // Add to the queue, and signal the workers.
            computerQueue.add( executor );
            hasWork.signal();

            // If we've transitioned into a busy state, notify the monitor. This will cause it to sleep for scaledPeriod
            // instead of the longer wakeup duration.
            if( !wasBusy && isBusy() ) monitorWakeup.signal();
        }
        finally
        {
            computerLock.unlock();
        }
    }


    /**
     * Update the {@link ComputerExecutor#virtualRuntime}s of all running tasks, and then update the
     * {@link #minimumVirtualRuntime} based on the current tasks.
     * <p>
     * This is called before queueing tasks, to ensure that {@link #minimumVirtualRuntime} is up-to-date.
     *
     * @param current The machine which we updating runtimes from.
     */
    private void updateRuntimes( @Nullable ComputerExecutor current )
    {
        long minRuntime = Long.MAX_VALUE;

        // If we've a task on the queue, use that as our base time.
        if( !computerQueue.isEmpty() ) minRuntime = computerQueue.first().virtualRuntime;

        // Update all the currently executing tasks
        long now = System.nanoTime();
        int tasks = 1 + computerQueue.size();
        for( TaskRunner runner : runners )
        {
            if( runner == null ) continue;
            ComputerExecutor executor = runner.currentExecutor.get();
            if( executor == null ) continue;

            // We do two things here: first we update the task's virtual runtime based on when we
            // last checked, and then we check the minimum.
            minRuntime = Math.min( minRuntime, executor.virtualRuntime += (now - executor.vRuntimeStart) / tasks );
            executor.vRuntimeStart = now;
        }

        // And update the most recently executed one (if set).
        if( current != null )
        {
            minRuntime = Math.min( minRuntime, current.virtualRuntime += (now - current.vRuntimeStart) / tasks );
        }

        if( minRuntime > minimumVirtualRuntime && minRuntime < Long.MAX_VALUE )
        {
            minimumVirtualRuntime = minRuntime;
        }
    }

    /**
     * Ensure the "currently working" state of the executor is reset, the timings are updated, and then requeue the
     * executor if needed.
     *
     * @param runner   The runner this task was on.
     * @param executor The executor to requeue
     */
    private void afterWork( TaskRunner runner, ComputerExecutor executor )
    {
        // Clear the executor's thread.
        Thread currentThread = executor.executingThread.getAndSet( null );
        if( currentThread != runner.owner )
        {

            ComputerCraft.log.error(
                "Expected computer #{} to be running on {}, but already running on {}. This is a SERIOUS bug, please report with your debug.log.",
                executor.getComputer().getID(),
                runner.owner == null ? "nothing" : runner.owner.getName(),
                currentThread == null ? "nothing" : currentThread.getName()
            );
        }

        computerLock.lock();
        try
        {
            updateRuntimes( executor );

            // If we've no more tasks, just return.
            if( !executor.afterWork() ) return;

            // Otherwise, add to the queue, and signal any waiting workers.
            computerQueue.add( executor );
            hasWork.signal();
        }
        finally
        {
            computerLock.unlock();
        }
    }

    /**
     * The scaled period for a single task.
     *
     * @return The scaled period for the task
     * @see #DEFAULT_LATENCY
     * @see #DEFAULT_MIN_PERIOD
     * @see #LATENCY_MAX_TASKS
     */
    long scaledPeriod()
    {
        // +1 to include the current task
        int count = 1 + computerQueue.size();
        return count < LATENCY_MAX_TASKS ? latency / count : minPeriod;
    }

    /**
     * Determine if the thread has computers queued up.
     *
     * @return If we have work queued up.
     */
    boolean hasPendingWork()
    {
        return !computerQueue.isEmpty();
    }

    /**
     * Check if we have more work queued than we have capacity for. Effectively a more fine-grained version of
     * {@link #hasPendingWork()}.
     *
     * @return If the computer threads are busy.
     */
    private boolean isBusy()
    {
        return computerQueue.size() > idleWorkers.get();
    }

    /**
     * Observes all currently active {@link TaskRunner}s and terminates their tasks once they have exceeded the hard
     * abort limit.
     *
     * @see TimeoutState
     */
    private final class Monitor implements Runnable
    {
        @Override
        public void run()
        {
            while( true )
            {
                computerLock.lock();
                try
                {
                    // If we've got more work than we have capacity for it, then we'll need to pause a task soon, so
                    // sleep for a single pause duration. Otherwise we only need to wake up to set the soft/hard abort
                    // flags, which are far less granular.
                    monitorWakeup.awaitNanos( isBusy() ? scaledPeriod() : MONITOR_WAKEUP );
                }
                catch( InterruptedException e )
                {
                    if( running )
                    {
                        ComputerCraft.log.error( "Monitor thread interrupted. Computers may behave very badly!", e );
                    }
                    break;
                }
                finally
                {
                    computerLock.unlock();
                }

                checkRunners();
            }
        }

        private void checkRunners()
        {
            TaskRunner[] currentRunners = ComputerThread.this.runners;
            for( int i = 0; i < currentRunners.length; i++ )
            {
                TaskRunner runner = currentRunners[i];
                // If we've no runner, skip.
                if( runner == null || runner.owner == null || !runner.owner.isAlive() )
                {
                    if( !running ) continue;

                    // Mark the old runner as dead and start a new one.
                    ComputerCraft.log.warn( "Previous runner ({}) has crashed, restarting!", runner != null && runner.owner != null ? runner.owner.getName() : runner );
                    if( runner != null ) runner.running = false;
                    runnerFactory.newThread( runner = runners[i] = new TaskRunner() ).start();
                }

                // If the runner has no work, skip
                ComputerExecutor executor = runner.currentExecutor.get();
                if( executor == null ) continue;

                // Refresh the timeout state. Will set the pause/soft timeout flags as appropriate.
                executor.timeout.refresh();

                // If we're still within normal execution times (TIMEOUT) or soft abort (ABORT_TIMEOUT),
                // then we can let the Lua machine do its work.
                long afterStart = executor.timeout.nanoCumulative();
                long afterHardAbort = afterStart - TimeoutState.TIMEOUT - TimeoutState.ABORT_TIMEOUT;
                if( afterHardAbort < 0 ) continue;

                // Set the hard abort flag.
                executor.timeout.hardAbort();
                executor.abort();

                if( afterHardAbort >= TimeoutState.ABORT_TIMEOUT * 2 )
                {
                    // If we've hard aborted and interrupted, and we're still not dead, then mark the runner
                    // as dead, finish off the task, and spawn a new runner.
                    runner.reportTimeout( executor, afterStart );
                    runner.running = false;
                    if( runner.owner != null ) runner.owner.interrupt();

                    ComputerExecutor thisExecutor = runner.currentExecutor.getAndSet( null );
                    if( thisExecutor != null ) afterWork( runner, executor );

                    synchronized( threadLock )
                    {
                        if( running && runners[i] == runner )
                        {
                            runnerFactory.newThread( currentRunners[i] = new TaskRunner() ).start();
                        }
                    }
                }
                else if( afterHardAbort >= TimeoutState.ABORT_TIMEOUT )
                {
                    // If we've hard aborted but we're still not dead, dump the stack trace and interrupt
                    // the task.
                    runner.reportTimeout( executor, afterStart );
                    if( runner.owner != null ) runner.owner.interrupt();
                }
            }
        }
    }

    /**
     * Pulls tasks from the {@link #computerQueue} queue and runs them.
     * <p>
     * This is responsible for running the {@link ComputerExecutor#work()}, {@link ComputerExecutor#beforeWork()} and
     * {@link ComputerExecutor#afterWork()} functions. Everything else is either handled by the executor, timeout
     * state or monitor.
     */
    private final class TaskRunner implements Runnable
    {
        @Nullable
        Thread owner;
        long lastReport = Long.MIN_VALUE;
        volatile boolean running = true;

        final AtomicReference<ComputerExecutor> currentExecutor = new AtomicReference<>();

        @Override
        public void run()
        {
            owner = Thread.currentThread();

            tasks:
            while( running && ComputerThread.this.running )
            {
                // Wait for an active queue to execute
                ComputerExecutor executor;
                try
                {
                    computerLock.lockInterruptibly();
                    try
                    {
                        idleWorkers.incrementAndGet();
                        while( computerQueue.isEmpty() ) hasWork.await();
                        executor = computerQueue.pollFirst();
                        assert executor != null : "hasWork should ensure we never receive null work";
                    }
                    finally
                    {
                        computerLock.unlock();
                        idleWorkers.decrementAndGet();
                    }
                }
                catch( InterruptedException ignored )
                {
                    // If we've been interrupted, our running flag has probably been reset, so we'll
                    // just jump into the next iteration.
                    continue;
                }

                // If we're trying to executing some task on this computer while someone else is doing work, something
                // is seriously wrong.
                while( !executor.executingThread.compareAndSet( null, owner ) )
                {
                    Thread existing = executor.executingThread.get();
                    if( existing != null )
                    {
                        ComputerCraft.log.error(
                            "Trying to run computer #{} on thread {}, but already running on {}. This is a SERIOUS bug, please report with your debug.log.",
                            executor.getComputer().getID(), owner.getName(), existing.getName()
                        );
                        continue tasks;
                    }
                }

                // Reset the timers
                executor.beforeWork();

                // And then set the current executor. It's important to do it afterwards, as otherwise we introduce
                // race conditions with the monitor.
                currentExecutor.set( executor );

                // Execute the task
                try
                {
                    executor.work();
                }
                catch( Exception | LinkageError | VirtualMachineError e )
                {
                    ComputerCraft.log.error( "Error running task on computer #" + executor.getComputer().getID(), e );
                    // Tear down the computer immediately. There's no guarantee it's well behaved from now on.
                    executor.fastFail();
                }
                finally
                {
                    ComputerExecutor thisExecutor = currentExecutor.getAndSet( null );
                    if( thisExecutor != null ) afterWork( this, executor );
                }
            }
        }

        private void reportTimeout( ComputerExecutor executor, long time )
        {
            if( !ComputerCraft.logComputerErrors ) return;

            // Attempt to debounce stack trace reporting, limiting ourselves to one every second.
            long now = System.nanoTime();
            if( lastReport != Long.MIN_VALUE && now - lastReport - REPORT_DEBOUNCE <= 0 ) return;
            lastReport = now;

            Thread owner = Objects.requireNonNull( this.owner );

            StringBuilder builder = new StringBuilder()
                .append( "Terminating computer #" ).append( executor.getComputer().getID() )
                .append( " due to timeout (running for " ).append( time * 1e-9 )
                .append( " seconds). This is NOT a bug, but may mean a computer is misbehaving.\n" )
                .append( "Thread " )
                .append( owner.getName() )
                .append( " is currently " )
                .append( owner.getState() )
                .append( '\n' );
            Object blocking = LockSupport.getBlocker( owner );
            if( blocking != null ) builder.append( "  on " ).append( blocking ).append( '\n' );

            for( StackTraceElement element : owner.getStackTrace() )
            {
                builder.append( "  at " ).append( element ).append( '\n' );
            }

            executor.printState( builder );

            ComputerCraft.log.warn( builder.toString() );
        }
    }
}
