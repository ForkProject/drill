/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.work.fragment;

import java.io.IOException;
import java.security.PrivilegedExceptionAction;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.drill.common.DeferredException;
import org.apache.drill.common.concurrent.ExtendedLatch;
import org.apache.drill.common.exceptions.UserException;
import org.apache.drill.exec.coord.ClusterCoordinator;
import org.apache.drill.exec.memory.OutOfMemoryRuntimeException;
import org.apache.drill.exec.ops.FragmentContext;
import org.apache.drill.exec.ops.FragmentContext.ExecutorState;
import org.apache.drill.exec.physical.base.FragmentRoot;
import org.apache.drill.exec.physical.impl.ImplCreator;
import org.apache.drill.exec.physical.impl.RootExec;
import org.apache.drill.exec.proto.BitControl.FragmentStatus;
import org.apache.drill.exec.proto.CoordinationProtos;
import org.apache.drill.exec.proto.CoordinationProtos.DrillbitEndpoint;
import org.apache.drill.exec.proto.ExecProtos.FragmentHandle;
import org.apache.drill.exec.proto.UserBitShared.FragmentState;
import org.apache.drill.exec.proto.helper.QueryIdHelper;
import org.apache.drill.exec.server.DrillbitContext;
import org.apache.drill.exec.testing.ExecutionControlsInjector;
import org.apache.drill.exec.util.ImpersonationUtil;
import org.apache.drill.exec.work.foreman.DrillbitStatusListener;
import org.apache.hadoop.security.UserGroupInformation;

/**
 * Responsible for running a single fragment on a single Drillbit. Listens/responds to status request
 * and cancellation messages.
 */
public class FragmentExecutor implements Runnable {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(FragmentExecutor.class);
  private final static ExecutionControlsInjector injector = ExecutionControlsInjector.getInjector(FragmentExecutor.class);

  private final String fragmentName;
  private final FragmentRoot rootOperator;
  private final FragmentContext fragmentContext;
  private final StatusReporter listener;
  private final DeferredException deferredException = new DeferredException();

  private volatile RootExec root;
  private final AtomicReference<FragmentState> fragmentState = new AtomicReference<>(FragmentState.AWAITING_ALLOCATION);
  private final ExtendedLatch acceptExternalEvents = new ExtendedLatch();

  // Thread that is currently executing the Fragment. Value is null if the fragment hasn't started running or finished
  private final AtomicReference<Thread> myThreadRef = new AtomicReference<>(null);

  public FragmentExecutor(final FragmentContext context, final FragmentRoot rootOperator,
                          final StatusReporter listener) {
    this.fragmentContext = context;
    this.rootOperator = rootOperator;
    this.listener = listener;
    this.fragmentName = QueryIdHelper.getQueryIdentifier(context.getHandle());

    context.setExecutorState(new ExecutorStateImpl());
  }

  @Override
  public String toString() {
    final StringBuilder builder = new StringBuilder();
    builder.append("FragmentExecutor [fragmentContext=");
    builder.append(fragmentContext);
    builder.append(", fragmentState=");
    builder.append(fragmentState);
    builder.append("]");
    return builder.toString();
  }

  /**
   * Returns the current fragment status if the fragment is running. Otherwise, returns no status.
   *
   * @return FragmentStatus or null.
   */
  public FragmentStatus getStatus() {
    /*
     * If the query is not in a running state, the operator tree is still being constructed and
     * there is no reason to poll for intermediate results.
     *
     * Previously the call to get the operator stats with the AbstractStatusReporter was happening
     * before this check. This caused a concurrent modification exception as the list of operator
     * stats is iterated over while collecting info, and added to while building the operator tree.
     */
    if (fragmentState.get() != FragmentState.RUNNING) {
      return null;
    }

    return AbstractStatusReporter
        .getBuilder(fragmentContext, FragmentState.RUNNING, null)
        .build();
  }

  /**
   * Cancel the execution of this fragment is in an appropriate state. Messages come from external.
   * NOTE that this will be called from threads *other* than the one running this runnable(),
   * so we need to be careful about the state transitions that can result.
   */
  public void cancel() {
    /*
     * When cancel() is called before run(), root is not initialized and the executor is not
     * ready to accept external events. So do not wait to change the state.
     *
     * For example, consider the case when the Foreman sets up the root fragment executor which is
     * waiting on incoming data, but the Foreman fails to setup non-root fragment executors. The
     * run() method on the root executor will never be called, and the executor will never be ready
     * to accept external events. This would make the cancelling thread wait forever, if it was waiting on
     * acceptExternalEvents.
     */
    synchronized (this) {
      if (root != null) {
        acceptExternalEvents.awaitUninterruptibly();
      } else {
        // This fragment may or may not start running. If it doesn't then closeOutResources() will never be called.
        // Assuming it's safe to call closeOutResources() multiple times, we call it here explicitly in case this
        // fragment will never start running.
        closeOutResources();
      }

      /*
       * We set the cancel requested flag but the actual cancellation is managed by the run() loop, if called.
       */
      updateState(FragmentState.CANCELLATION_REQUESTED);

      /*
       * Interrupt the thread so that it exits from any blocking operation it could be executing currently.
       */
      final Thread myThread = myThreadRef.get();
      if (myThread != null) {
        myThread.interrupt();
      }
    }
  }

  /**
   * Resume all the pauses within the current context. Note that this method will be called from threads *other* than
   * the one running this runnable(). Also, this method can be called multiple times.
   */
  public synchronized void unpause() {
    fragmentContext.getExecutionControls().unpauseAll();
  }

  /**
   * Inform this fragment that one of its downstream partners no longer needs additional records. This is most commonly
   * called in the case that a limit query is executed.
   *
   * @param handle The downstream FragmentHandle of the Fragment that needs no more records from this Fragment.
   */
  public void receivingFragmentFinished(final FragmentHandle handle) {
    acceptExternalEvents.awaitUninterruptibly();
    if (root != null) {
      logger.info("Applying request for early sender termination for {} -> {}.",
          QueryIdHelper.getFragmentId(this.getContext().getHandle()), QueryIdHelper.getFragmentId(handle));
      root.receivingFragmentFinished(handle);
    } else {
      logger.warn("Dropping request for early fragment termination for path {} -> {} as no root exec exists.",
          QueryIdHelper.getFragmentId(this.getContext().getHandle()), QueryIdHelper.getFragmentId(handle));
    }
  }

  @Override
  public void run() {
    final Thread myThread = Thread.currentThread();
    myThreadRef.set(myThread);
    final String originalThreadName = myThread.getName();
    final FragmentHandle fragmentHandle = fragmentContext.getHandle();
    final DrillbitContext drillbitContext = fragmentContext.getDrillbitContext();
    final ClusterCoordinator clusterCoordinator = drillbitContext.getClusterCoordinator();
    final DrillbitStatusListener drillbitStatusListener = new FragmentDrillbitStatusListener();
    final String newThreadName = QueryIdHelper.getExecutorThreadName(fragmentHandle);

    try {

      myThread.setName(newThreadName);

      synchronized (this) {
        /*
         * fragmentState might have changed even before this method is called e.g. cancel()
         */
        if (shouldContinue()) {
          root = ImplCreator.getExec(fragmentContext, rootOperator);

          clusterCoordinator.addDrillbitStatusListener(drillbitStatusListener);
          updateState(FragmentState.RUNNING);

          acceptExternalEvents.countDown();

          final DrillbitEndpoint endpoint = drillbitContext.getEndpoint();
          logger.debug("Starting fragment {}:{} on {}:{}",
            fragmentHandle.getMajorFragmentId(), fragmentHandle.getMinorFragmentId(),
            endpoint.getAddress(), endpoint.getUserPort());
        }
      }

      final UserGroupInformation queryUserUgi = fragmentContext.isImpersonationEnabled() ?
          ImpersonationUtil.createProxyUgi(fragmentContext.getQueryUserName()) :
          ImpersonationUtil.getProcessUserUGI();

      queryUserUgi.doAs(new PrivilegedExceptionAction<Void>() {
        public Void run() throws Exception {
          injector.injectChecked(fragmentContext.getExecutionControls(), "fragment-execution", IOException.class);
          /*
           * Run the query until root.next returns false OR we no longer need to continue.
           */
          while (shouldContinue() && root.next()) {
            // loop
          }

          return null;
        }
      });

      updateState(FragmentState.FINISHED);
    } catch (OutOfMemoryError | OutOfMemoryRuntimeException e) {
      if (!(e instanceof OutOfMemoryError) || "Direct buffer memory".equals(e.getMessage())) {
        fail(UserException.memoryError(e).build());
      } else {
        // we have a heap out of memory error. The JVM in unstable, exit.
        System.err.println("Node ran out of Heap memory, exiting.");
        e.printStackTrace(System.err);
        System.err.flush();
        System.exit(-2);

      }
    } catch (AssertionError | Exception e) {
      fail(e);
    } finally {

      // We need to sure we countDown at least once. We'll do it here to guarantee that.
      acceptExternalEvents.countDown();

      closeOutResources();

      // send the final state of the fragment. only the main execution thread can send the final state and it can
      // only be sent once.
      sendFinalState();

      clusterCoordinator.removeDrillbitStatusListener(drillbitStatusListener);

      myThread.setName(originalThreadName);

      myThreadRef.set(null);
    }
  }

  /**
   * Utility method to check where we are in a no terminal state.
   *
   * @return Whether or not execution should continue.
   */
  private boolean shouldContinue() {
    return !isCompleted() && FragmentState.CANCELLATION_REQUESTED != fragmentState.get();
  }

  /**
   * Returns true if the fragment is in a terminal state
   *
   * @return Whether this state is in a terminal state.
   */
  private boolean isCompleted() {
    return isTerminal(fragmentState.get());
  }

  private void sendFinalState() {
    final FragmentState outcome = fragmentState.get();
    if (outcome == FragmentState.FAILED) {
      final FragmentHandle handle = getContext().getHandle();
      final UserException uex = UserException.systemError(deferredException.getAndClear())
          .addIdentity(getContext().getIdentity())
          .addContext("Fragment", handle.getMajorFragmentId() + ":" + handle.getMinorFragmentId())
          .build();
      listener.fail(fragmentContext.getHandle(), uex);
    } else {
      listener.stateChanged(fragmentContext.getHandle(), outcome);
    }
  }


  private void closeOutResources() {

    // first close the operators and release all memory.
    try {
      // Say executor was cancelled before setup. Now when executor actually runs, root is not initialized, but this
      // method is called in finally. So root can be null.
      if (root != null) {
        root.close();
      }
    } catch (final Exception e) {
      fail(e);
    }

    // then close the fragment context.
    fragmentContext.close();

  }

  private void warnStateChange(final FragmentState current, final FragmentState target) {
    logger.warn("Ignoring unexpected state transition {} => {}.", current.name(), target.name());
  }

  private void errorStateChange(final FragmentState current, final FragmentState target) {
    final String msg = "Invalid state transition %s => %s.";
    throw new StateTransitionException(String.format(msg, current.name(), target.name()));
  }

  private synchronized boolean updateState(FragmentState target) {
    final FragmentHandle handle = fragmentContext.getHandle();
    final FragmentState current = fragmentState.get();
    logger.info(fragmentName + ": State change requested from {} --> {} for ", current, target);
    switch (target) {
    case CANCELLATION_REQUESTED:
      switch (current) {
      case SENDING:
      case AWAITING_ALLOCATION:
      case RUNNING:
        fragmentState.set(target);
        listener.stateChanged(handle, target);
        return true;

      default:
        warnStateChange(current, target);
        return false;
      }

    case FINISHED:
      if(current == FragmentState.CANCELLATION_REQUESTED){
        target = FragmentState.CANCELLED;
      }
      // fall-through
    case FAILED:
      if(!isTerminal(current)){
        fragmentState.set(target);
        // don't notify listener until we finalize this terminal state.
        return true;
      } else if (current == FragmentState.FAILED) {
        // no warn since we can call fail multiple times.
        return false;
      } else if (current == FragmentState.CANCELLED && target == FragmentState.FAILED) {
        fragmentState.set(FragmentState.FAILED);
        return true;
      }else{
        warnStateChange(current, target);
        return false;
      }

    case RUNNING:
      if(current == FragmentState.AWAITING_ALLOCATION){
        fragmentState.set(target);
        listener.stateChanged(handle, target);
        return true;
      }else{
        errorStateChange(current, target);
      }

    // these should never be requested.
    case SENDING:
    case AWAITING_ALLOCATION:
    case CANCELLED:
    default:
      errorStateChange(current, target);
    }

    // errorStateChange() throw should mean this is never executed
    throw new IllegalStateException();
  }

  private boolean isTerminal(final FragmentState state) {
    return state == FragmentState.CANCELLED
        || state == FragmentState.FAILED
        || state == FragmentState.FINISHED;
  }

  /**
   * Capture an exception and add store it. Update state to failed status (if not already there). Does not immediately
   * report status back to Foreman. Only the original thread can return status to the Foreman.
   *
   * @param excep
   *          The failure that occurred.
   */
  private void fail(final Throwable excep) {
    deferredException.addThrowable(excep);
    updateState(FragmentState.FAILED);
  }

  public FragmentContext getContext() {
    return fragmentContext;
  }

  private class ExecutorStateImpl implements ExecutorState {
    public boolean shouldContinue() {
      return FragmentExecutor.this.shouldContinue();
    }

    public void fail(final Throwable t) {
      FragmentExecutor.this.fail(t);
    }

    public boolean isFailed() {
      return fragmentState.get() == FragmentState.FAILED;
    }
    public Throwable getFailureCause(){
      return deferredException.getException();
    }
  }

  private class FragmentDrillbitStatusListener implements DrillbitStatusListener {
    @Override
    public void drillbitRegistered(final Set<CoordinationProtos.DrillbitEndpoint> registeredDrillbits) {
    }

    @Override
    public void drillbitUnregistered(final Set<CoordinationProtos.DrillbitEndpoint> unregisteredDrillbits) {
      // if the defunct Drillbit was running our Foreman, then cancel the query
      final DrillbitEndpoint foremanEndpoint = FragmentExecutor.this.fragmentContext.getForemanEndpoint();
      if (unregisteredDrillbits.contains(foremanEndpoint)) {
        logger.warn("Foreman {} no longer active.  Cancelling fragment {}.",
                    foremanEndpoint.getAddress(),
                    QueryIdHelper.getQueryIdentifier(fragmentContext.getHandle()));
        FragmentExecutor.this.cancel();
      }
    }
  }
}
