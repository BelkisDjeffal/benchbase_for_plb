/*
 * Copyright 2020 by OLTPBenchmark Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.oltpbenchmark.api;

import static com.oltpbenchmark.types.State.MEASURE;

import com.oltpbenchmark.LatencyRecord;
import com.oltpbenchmark.Phase;
import com.oltpbenchmark.SubmittedProcedure;
import com.oltpbenchmark.WorkloadConfiguration;
import com.oltpbenchmark.WorkloadState;
import com.oltpbenchmark.api.Procedure.UserAbortException;
import com.oltpbenchmark.types.DatabaseType;
import com.oltpbenchmark.types.State;
import com.oltpbenchmark.types.TransactionStatus;
import com.oltpbenchmark.util.Histogram;
import com.oltpbenchmark.util.SQLUtil;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class Worker<T extends BenchmarkModule> implements Runnable {
  private static final Logger LOG = LoggerFactory.getLogger(Worker.class);
  private static final Logger ABORT_LOG =
      LoggerFactory.getLogger("com.oltpbenchmark.api.ABORT_LOG");

  private WorkloadState workloadState;
  private LatencyRecord latencies;
  private final Statement currStatement;

  // Interval requests used by the monitor
  private final AtomicInteger intervalRequests = new AtomicInteger(0);
  private AtomicInteger attemptedRequests = new AtomicInteger();

  private final int id;
  protected String workerPriorityClass = "priority";
  private final T benchmark;
  protected Connection conn = null;
  protected final WorkloadConfiguration configuration;
  protected final TransactionTypes transactionTypes;
  protected final Map<TransactionType, Procedure> procedures = new HashMap<>();
  protected final Map<String, Procedure> name_procedures = new HashMap<>();
  protected final Map<Class<? extends Procedure>, Procedure> class_procedures = new HashMap<>();

  private long actualStartTimeMs;
  private long actualEndTimeMs;
  private final Histogram<TransactionType> txnUnknown = new Histogram<>();
  private final Histogram<TransactionType> txnSuccess = new Histogram<>();
  private final Histogram<TransactionType> txnAbort = new Histogram<>();
  private final Histogram<TransactionType> txnRetry = new Histogram<>();
  private final Histogram<TransactionType> txnErrors = new Histogram<>();
  private final Histogram<TransactionType> txtRetryDifferent = new Histogram<>();
  private final Map<String, Integer> localSuccessByPriority = new HashMap<>();
  private final Map<String, Integer> attemptedRequestsByPriority = new ConcurrentHashMap<>();
  private long hardDeadlineMs;
  private final Random workerRng;

  private boolean seenDone = false;

  private volatile boolean abandonedByPolicy = false;
  private volatile boolean stopDueToPolicy = false;
  private volatile boolean lastDoWorkAbandonedByPolicy = false;

  private int policyRejects = 0;
  private final int maxPolicyRejects =
      Integer.parseInt(System.getProperty("plb.freemium.maxRejects", "3"));

  private static final Object TS_DUMP_LOCK = new Object();
  private static final AtomicInteger ACTIVE_WORKERS = new AtomicInteger(0);
  private static final ConcurrentHashMap<String, ConcurrentHashMap<Integer, AtomicInteger>> TS =
      new ConcurrentHashMap<>();

  public Worker(T benchmark, int id, String workerPriorityClass) {
    // System.out.printf(
    //     "Worker: Printing worker's id = %d and priority = %s!%n", id, workerPriorityClass);
    this.id = id;
    this.benchmark = benchmark;
    this.workerPriorityClass = workerPriorityClass;
    this.configuration = this.benchmark.getWorkloadConfiguration();
    this.workloadState = this.configuration.getWorkloadState();
    this.currStatement = null;
    this.transactionTypes = this.configuration.getTransTypes();

    // if (!this.configuration.getNewConnectionPerTxn()) {
    //   try {
    //     this.conn = this.benchmark.makeConnection(workerPriorityClass);
    //     this.conn.setAutoCommit(false);
    //     this.conn.setTransactionIsolation(this.configuration.getIsolationMode());
    //   } catch (SQLException ex) {
    //     throw new RuntimeException("Failed to connect to database", ex);
    //   }
    // }

    this.procedures.putAll(this.benchmark.getProcedures());
    for (Entry<TransactionType, Procedure> e : this.procedures.entrySet()) {
      Procedure proc = e.getValue();
      this.name_procedures.put(e.getKey().getName(), proc);
      this.class_procedures.put(proc.getClass(), proc);
    }
    long baseSeed = this.configuration.getRandomSeed();
    if (baseSeed == -1) {
      this.workerRng = new Random();
    } else {
      long derived = baseSeed ^ (0x9E3779B97F4A7C15L * (id + 1));
      this.workerRng = new Random(derived);
    }
  }

  public Worker(T benchmark, int id) {
    this.id = id;
    this.benchmark = benchmark;
    this.configuration = this.benchmark.getWorkloadConfiguration();
    this.workloadState = this.configuration.getWorkloadState();
    this.currStatement = null;
    this.transactionTypes = this.configuration.getTransTypes();

    // if (!this.configuration.getNewConnectionPerTxn()) {
    //   try {
    //     this.conn = this.benchmark.makeConnection(workerPriorityClass);
    //     this.conn.setAutoCommit(false);
    //     this.conn.setTransactionIsolation(this.configuration.getIsolationMode());
    //   } catch (SQLException ex) {
    //     throw new RuntimeException("Failed to connect to database", ex);
    //   }
    // }

    // Generate all the Procedures that we're going to need
    this.procedures.putAll(this.benchmark.getProcedures());
    for (Entry<TransactionType, Procedure> e : this.procedures.entrySet()) {
      Procedure proc = e.getValue();
      this.name_procedures.put(e.getKey().getName(), proc);
      this.class_procedures.put(proc.getClass(), proc);
    }

    long baseSeed = this.configuration.getRandomSeed();
    if (baseSeed == -1) {
      this.workerRng = new Random();
    } else {
      long derived = baseSeed ^ (0x9E3779B97F4A7C15L * (id + 1));
      this.workerRng = new Random(derived);
    }
  }

  /** Get the BenchmarkModule managing this Worker */
  public final T getBenchmark() {
    return (this.benchmark);
  }

  /** Get the unique thread id for this worker */
  public final int getId() {
    return this.id;
  }

  @Override
  public String toString() {
    return String.format("%s<%03d>", this.getClass().getSimpleName(), this.getId());
  }

  public final WorkloadConfiguration getWorkloadConfiguration() {
    return (this.benchmark.getWorkloadConfiguration());
  }

  public String getWorkerPriorityClass() {
    return workerPriorityClass;
  }

  public final Random rng() {
    return (this.benchmark.rng());
  }

  public final int getRequests() {
    return latencies.size();
  }

  public final int getAndResetIntervalRequests() {
    return intervalRequests.getAndSet(0);
  }

  public final Iterable<LatencyRecord.Sample> getLatencyRecords() {
    return latencies;
  }

  public final Procedure getProcedure(TransactionType type) {
    return (this.procedures.get(type));
  }

  @Deprecated
  public final Procedure getProcedure(String name) {
    return (this.name_procedures.get(name));
  }

  @SuppressWarnings("unchecked")
  public final <P extends Procedure> P getProcedure(Class<P> procClass) {
    return (P) (this.class_procedures.get(procClass));
  }

  public final Histogram<TransactionType> getTransactionSuccessHistogram() {
    return (this.txnSuccess);
  }

  public final Histogram<TransactionType> getTransactionUnknownHistogram() {
    return (this.txnUnknown);
  }

  public final Histogram<TransactionType> getTransactionRetryHistogram() {
    return (this.txnRetry);
  }

  public final Histogram<TransactionType> getTransactionAbortHistogram() {
    return (this.txnAbort);
  }

  public final Histogram<TransactionType> getTransactionErrorHistogram() {
    return (this.txnErrors);
  }

  public final Histogram<TransactionType> getTransactionRetryDifferentHistogram() {
    return (this.txtRetryDifferent);
  }

  public Map<String, Integer> getLocalSuccessByPriority() {
    return localSuccessByPriority;
  }

  public long getActualStartTimeMs() {
    return actualStartTimeMs;
  }

  public long getActualEndTimeMs() {
    return actualEndTimeMs;
  }

  /** Stop executing the current statement. */
  public synchronized void cancelStatement() {
    try {
      if (this.currStatement != null) {
        this.currStatement.cancel();
      }
    } catch (SQLException e) {
      LOG.error("Failed to cancel statement: {}", e.getMessage());
    }
  }

  @Override
  public final void run() {

    // if (!this.configuration.getNewConnectionPerTxn()) {
    //   try {
    //     this.conn = this.benchmark.makeConnection(workerPriorityClass);
    //     this.conn.setAutoCommit(false);
    //     this.conn.setTransactionIsolation(this.configuration.getIsolationMode());
    //   } catch (SQLException ex) {
    //     throw new RuntimeException("Failed to connect to database", ex);
    //   }
    // }
    this.actualStartTimeMs = System.currentTimeMillis();

    Thread t = Thread.currentThread();
    t.setName(this.toString());

    ACTIVE_WORKERS.incrementAndGet();

    // In case of reuse reset the measurements
    latencies = new LatencyRecord(workloadState.getTestStartNs());

    // Invoke initialize callback
    try {
      this.initialize();
    } catch (Throwable ex) {
      throw new RuntimeException("Unexpected error when initializing " + this, ex);
    }

    // wait for start
    // workloadState.blockForStart();

    long minTime = 10 * 1000L; // 10 seconds
    // long maxTime = configuration.getPhases().stream().mapToInt(Phase::getTime).sum() * 1000L;
    long maxTime = 3 * 10 * 1000L; // 30 sec'
    double u = workerRng.nextDouble();
    long executionTime = minTime + (long) ((maxTime - minTime) * u); // Uniform distribution

    long startTime = System.currentTimeMillis();

    this.hardDeadlineMs = startTime + executionTime;

    LOG.info("Worker {} will run for {} ms", id, executionTime);

    try {
      while (true) {
        long currentTime = System.currentTimeMillis();

        // Stop worker if execution time is reached
        if ((currentTime - startTime) >= executionTime) {
          LOG.info("Worker {} stopping after {} ms", id, executionTime);
          // workloadState.signalDone();
          break;
        }
        // PART 1: Init and check if done

        State preState = workloadState.getGlobalState();

        // if (preState == State.DONE) {
        //   if (!seenDone) {
        //     // This is the first time we have observed that the
        //     // test is done notify the global test state, then
        //     // continue applying load
        //     seenDone = true;
        //     workloadState.signalDone();
        //     break;
        //   }
        // }

        // PART 2: Wait for work
        LOG.debug("waiting for work");

        // Sleep if there's nothing to do.
        // workloadState.stayAwake(); // this makes workers stuck in the WARMUP state and never
        // transition to the MEASURE

        Phase prePhase = workloadState.getCurrentPhase();
        if (prePhase == null) {
          continue;
        }

        // Grab some work and update the state, in case it changed while we
        // waited.
        SubmittedProcedure pieceOfWork = workloadState.fetchWork();

        if (pieceOfWork == null) {
          // No more work to do, exit gracefully
          LOG.info("Worker {} has no more work. Exiting.", this.id);
          break;
        }

        prePhase = workloadState.getCurrentPhase();
        if (prePhase == null) {
          continue;
        }

        preState = workloadState.getGlobalState();

        switch (preState) {
          case DONE, EXIT, LATENCY_COMPLETE -> {
            // Once a latency run is complete, we wait until the next
            // phase or until DONE.
            LOG.warn("preState is {}? will continue...", preState);
            continue;
          }
          default -> {}
            // Do nothing
        }

        // PART 3: Execute work

        TransactionType transactionType =
            getTransactionType(pieceOfWork, prePhase, preState, workloadState);

        if (abandonedByPolicy && "freemium".equalsIgnoreCase(workerPriorityClass)) {
          // Drop load: don't execute, don't count attempts/latency
          transactionType = TransactionType.INVALID;
        }

        if (!transactionType.equals(TransactionType.INVALID)) {
          // TODO: Measuring latency when not rate limited is ... a little
          // weird because if you add more simultaneous clients, you will
          // increase latency (queue delay) but we do this anyway since it is
          // useful sometimes

          // Wait before transaction if specified
          long preExecutionWaitInMillis = getPreExecutionWaitInMillis(transactionType);

          if (preExecutionWaitInMillis > 0) {
            try {
              LOG.debug(
                  "{} will sleep for {} ms before executing",
                  transactionType.getName(),
                  preExecutionWaitInMillis);

              Thread.sleep(preExecutionWaitInMillis);
            } catch (InterruptedException e) {
              LOG.error("Pre-execution sleep interrupted", e);
            }
          }

          // Track all attempted transactions during MEASURE (before executing)
          if (preState == MEASURE) {
            attemptedRequests.incrementAndGet();
            attemptedRequestsByPriority.merge(workerPriorityClass, 1, Integer::sum);
            incTs("query_attempt");
          }

          long start = System.nanoTime();

          doWork(configuration.getDatabaseType(), transactionType);

          long end = System.nanoTime();

          // PART 4: Record results
          if (lastDoWorkAbandonedByPolicy) {
            // We did not execute a real transaction (we abandoned during connect retries)
            // => do NOT count success, do NOT record latency
            LOG.warn(
                "Worker {} abandoned by policy during connect; skipping result recording for {}.",
                id,
                transactionType.getName());
          } else {
            State postState = workloadState.getGlobalState();
            switch (postState) {
              case MEASURE, DONE, EXIT:
                Phase postPhase = workloadState.getCurrentPhase();
                if (postPhase == null) break;

                if (preState == MEASURE && postPhase.getId() == prePhase.getId()) {
                  this.txnSuccess.put(transactionType);
                  localSuccessByPriority.merge(workerPriorityClass, 1, Integer::sum);
                  latencies.addLatency(
                      transactionType.getId(),
                      start,
                      end,
                      this.id,
                      prePhase.getId(),
                      workerPriorityClass);
                  intervalRequests.incrementAndGet();
                  incTs("query_success");
                }
                if (prePhase.isLatencyRun()) {
                  workloadState.startColdQuery();
                }
                break;

              case COLD_QUERY:
                if (preState == State.COLD_QUERY) {
                  workloadState.startHotQuery();
                }
                break;

              default:
                // Do nothing
            }
          }

          // wait after transaction if specified
          long postExecutionWaitInMillis = getPostExecutionWaitInMillis(transactionType);

          if (postExecutionWaitInMillis > 0) {
            try {
              LOG.debug(
                  "{} will sleep for {} ms after executing",
                  transactionType.getName(),
                  postExecutionWaitInMillis);

              Thread.sleep(postExecutionWaitInMillis);
            } catch (InterruptedException e) {
              LOG.error("Post-execution sleep interrupted", e);
            }
          }
        }

        workloadState.finishedWork();
        if (stopDueToPolicy && "freemium".equalsIgnoreCase(workerPriorityClass)) {
          incTs("freemium_abandon");
          LOG.warn("Worker {} exiting thread due to policy abandonment.", id);
          break;
        }
      }
    } finally {
      LOG.debug("worker calling teardown");
      workloadState.signalDone();
      tearDown();

      int left = ACTIVE_WORKERS.decrementAndGet();
      // if (left == 0) {
      //   synchronized (TS_DUMP_LOCK) {
      //     // double-check in case of races
      //     if (ACTIVE_WORKERS.get() == 0) {
      //       dumpTimeSeriesToLog(LOG);
      //     }
      //   }
      // }
    }
    this.actualEndTimeMs = System.currentTimeMillis();
  }

  private TransactionType getTransactionType(
      SubmittedProcedure pieceOfWork, Phase phase, State state, WorkloadState workloadState) {
    TransactionType type = TransactionType.INVALID;

    try {
      type = transactionTypes.getType(pieceOfWork.getType());
    } catch (IndexOutOfBoundsException e) {
      if (phase.isThroughputRun()) {
        LOG.error("Thread tried executing disabled phase!");
        throw e;
      }
      if (phase.getId() == workloadState.getCurrentPhase().getId()) {
        switch (state) {
          case WARMUP -> {
            // Don't quit yet: we haven't even begun!
            LOG.info("[Serial] Resetting serial for phase.");
            phase.resetSerial();
          }
          case COLD_QUERY, MEASURE -> {
            // The serial phase is over. Finish the run early.
            LOG.info("[Serial] Updating workload state to {}.", State.LATENCY_COMPLETE);
            workloadState.signalLatencyComplete();
          }
          default -> throw e;
        }
      }
    }

    return type;
  }

  /**
   * Called in a loop in the thread to exercise the system under test. Each implementing worker
   * should return the TransactionType handle that was executed.
   *
   * @param databaseType TODO
   * @param transactionType TODO
   */
  protected final void doWork(DatabaseType databaseType, TransactionType transactionType) {

    try {
      int retryCount = 0;
      int maxRetryCount = configuration.getMaxRetries();
      lastDoWorkAbandonedByPolicy = false;

      while (retryCount < maxRetryCount && System.currentTimeMillis() < hardDeadlineMs) {

        long remainingMs = hardDeadlineMs - System.currentTimeMillis();
        if (remainingMs <= 0) break;

        TransactionStatus status = TransactionStatus.UNKNOWN;

        if (this.conn == null) {
          try {
            if (retryCount > 0) {
              long remMs = hardDeadlineMs - System.currentTimeMillis();
              if (remMs <= 0) {
                break;
              }
              long backoffMs =
                  Math.min(Duration.ofSeconds(Math.min(retryCount, 5)).toMillis(), remMs);
              try {
                Thread.sleep(backoffMs);
              } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
              }
            }
<<<<<<< HEAD

            this.conn = this.benchmark.makeConnection(workerPriorityClass);
            this.conn.setAutoCommit(false);
            this.conn.setTransactionIsolation(this.configuration.getIsolationMode());

            long remainingAfterReconnect = hardDeadlineMs - System.currentTimeMillis();
            if (remainingAfterReconnect <= 0) {
              break; // don't proceed to start a txn if time already expired
            }
=======
            incTs("connect_attempt");
            this.conn = this.benchmark.makeConnection(workerPriorityClass);
            this.conn.setAutoCommit(false);
            this.conn.setTransactionIsolation(this.configuration.getIsolationMode());
            incTs("connect_success");
>>>>>>> d168bf6 (add per-second tier time-series (connect/query/reject/abandon) and dump to CSV)
          } catch (SQLException ex) {
            // Policy reject handling
            if ("freemium".equalsIgnoreCase(workerPriorityClass) && isPlbFreemiumReject(ex)) {
              incTs("connect_reject");
              policyRejects++;
              LOG.warn(
                  "Worker {} priority {} rejected by PLB ({}/{}). sqlState={} msg={}",
                  this.id,
                  this.workerPriorityClass,
                  policyRejects,
                  maxPolicyRejects,
                  ex.getSQLState(),
                  ex.getMessage());

              if (policyRejects >= maxPolicyRejects) {
                abandonedByPolicy = true;
                stopDueToPolicy = true;
                lastDoWorkAbandonedByPolicy = true;

                try {
                  if (this.conn != null) this.conn.close();
                } catch (SQLException ignore) {
                }
                this.conn = null;

                LOG.warn(
                    "Worker {} priority {} abandoning (policy reject).",
                    this.id,
                    this.workerPriorityClass);

                break;
              }
            } else {
              incTs("connect_fail");
            }

            if (LOG.isDebugEnabled()) {
              LOG.debug(String.format("%s failed to open a connection...", this));
            }
            retryCount++;
            continue;
          }
        }

        try {

          if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("%s %s attempting...", this, transactionType));
          }

          long remainingNow = hardDeadlineMs - System.currentTimeMillis();
          if (remainingNow <= 0) {
            break; // don't start a txn we can't finish
          }

          try (Statement timeoutStmt = conn.createStatement()) {
            long clamp = Math.max(1, remainingNow);
            timeoutStmt.execute("SET LOCAL statement_timeout = " + clamp);
          }

          status = this.executeWork(conn, transactionType);

          if (LOG.isDebugEnabled()) {
            LOG.debug(
                String.format(
                    "%s %s completed with status [%s]...", this, transactionType, status.name()));
          }

          if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("%s %s committing...", this, transactionType));
          }

          conn.commit();

          break;

        } catch (UserAbortException ex) {
          try {
            conn.rollback();
          } catch (SQLException ex2) {
            LOG.error("SQLException caught while rolling back transaction.", ex2);
            // force a reconnection
            conn = null;
          }

          ABORT_LOG.debug(String.format("%s Aborted", transactionType), ex);

          status = TransactionStatus.USER_ABORTED;

          break;

        } catch (SQLException ex) {
<<<<<<< HEAD

          if ("57014".equals(ex.getSQLState())) { // query_canceled
            try {
              if (conn != null && !conn.getAutoCommit()) {
                conn.rollback();
              }
            } catch (SQLException ignore) {

            }
            LOG.info("Worker {} hit statement_timeout, stopping early", this.id);
            status = TransactionStatus.USER_ABORTED;
            break; // stop retries; worker's life budget is exhausted
          }
=======
          incTs("query_fail");
          if (isTimeout(ex)) incTs("query_timeout");

>>>>>>> d168bf6 (add per-second tier time-series (connect/query/reject/abandon) and dump to CSV)
          // check if we should attempt to ignore connection errors and reconnect
          boolean isConnectionErrorException = SQLUtil.isConnectionErrorException(ex);

          if (indicatesReadOnly(ex)) {
            if (SQLUtil.isConnectionOK(conn)) {
              conn.setReadOnly(true);
            }
          }

          // if the connection is closed, we can't rollback
          if (!isConnectionErrorException && SQLUtil.isConnectionOK(conn)) {
            // if the error is that we're attempting a write transaction to a read-only secondary,
            // then we can't rollback anyways, so don't bother trying
            if (conn.isReadOnly()) {
              // in that case, we should close the connection and possibly try again
              LOG.debug(
                  String.format(
                      "Won't attempt a rollback since the SQL connection looks read-only during [%s]... current retry attempt [%d], max retry attempts [%d], sql state [%s], error code [%d].",
                      transactionType,
                      retryCount,
                      maxRetryCount,
                      ex.getSQLState(),
                      ex.getErrorCode()),
                  ex);
              try {
                conn.close();
              } catch (SQLException ex2) {
                LOG.error("SQLException caught while closing connection.", ex2);
              }
              // force a reconnection
              conn = null;
            }
            // otherwise, we should attempt a rollback
            else {
              LOG.debug(
                  String.format(
                      "Attempting a rollback since a problem was detected during [%s]... current retry attempt [%d], max retry attempts [%d], sql state [%s], error code [%d].",
                      transactionType,
                      retryCount,
                      maxRetryCount,
                      ex.getSQLState(),
                      ex.getErrorCode()),
                  ex);
              try {
                conn.rollback();
              } catch (SQLException ex2) {
                LOG.error("SQLException caught while attempting to rollback transaction.", ex2);
                // force a reconnection
                conn = null;
              }
            }
          }
          // connection is closed, try a reconnect
          else {
            if (this.configuration.getReconnectOnConnectionFailure()) {
              LOG.debug(
                  String.format(
                      "Won't attempt a rollback since a problem with the SQL connection was detected during [%s]... current retry attempt [%d], max retry attempts [%d], sql state [%s], error code [%d].",
                      transactionType,
                      retryCount,
                      maxRetryCount,
                      ex.getSQLState(),
                      ex.getErrorCode()),
                  ex);
            } else {
              // old behavior, will likley result in an exception thrown
              // and an aborted benchmark due to the connection problem
              LOG.debug(
                  String.format(
                      "Attempting a rollback since a problem was detected during [%s] (despite connection error detection - see reconnectOnConnectionFailure setting)... current retry attempt [%d], max retry attempts [%d], sql state [%s], error code [%d].",
                      transactionType,
                      retryCount,
                      maxRetryCount,
                      ex.getSQLState(),
                      ex.getErrorCode()),
                  ex);
              try {
                conn.rollback();
              } catch (SQLException ex2) {
                LOG.error("SQLException caught while attempting to rollback transaction.", ex2);
                // force a reconnection
                conn = null;
              }
            }
          }

          // check the connection (after possible reconnection) again
          if ((isConnectionErrorException || !SQLUtil.isConnectionOK(conn))
              && this.configuration.getReconnectOnConnectionFailure()) {
            LOG.debug(
                String.format(
                    "Retryable SQL connection exception occurred during [%s]... current retry attempt [%d], max retry attempts [%d], sql state [%s], error code [%d].",
                    transactionType,
                    retryCount,
                    maxRetryCount,
                    ex.getSQLState(),
                    ex.getErrorCode()),
                ex);

            // force a reconnection
            try {
              if (conn != null) {
                conn.close();
              }
            } catch (Exception e) {
              LOG.warn("Failed to close faulty connection (somewhat expected).", e);
            } finally {
              conn = null;
            }

            status = TransactionStatus.RETRY_DIFFERENT;

            retryCount++;
          } else if (isRetryable(ex)) {
            LOG.debug(
                String.format(
                    "Retryable SQLException occurred during [%s]... current retry attempt [%d], max retry attempts [%d], sql state [%s], error code [%d].",
                    transactionType,
                    retryCount,
                    maxRetryCount,
                    ex.getSQLState(),
                    ex.getErrorCode()),
                ex);

            status = TransactionStatus.RETRY;

            retryCount++;
          } else {
            LOG.warn(
                String.format(
                    "SQLException occurred during [%s] and will not be retried... sql state [%s], error code [%d].",
                    transactionType, ex.getSQLState(), ex.getErrorCode()),
                ex);

            status = TransactionStatus.ERROR;

            break;
          }
        } catch (RuntimeException ex) {
          Throwable c = ex.getCause();
          if (c instanceof SQLException && "57014".equals(((SQLException) c).getSQLState())) {
            try {
              if (conn != null && !conn.getAutoCommit()) conn.rollback();
            } catch (SQLException ignore) {
              /* best effort */
            }
            LOG.info(
                "Worker {}: statement_timeout (wrapped). Stopping this worker's loop.", this.id);
            status = TransactionStatus.USER_ABORTED;
            break;
          }
          throw ex;
        } finally {
          if (this.configuration.getNewConnectionPerTxn() && this.conn != null) {
            try {
              this.conn.close();
              this.conn = null;
            } catch (SQLException e) {
              LOG.error("Connection couldn't be closed.", e);
            }
          } else if (this.conn == null) {
            LOG.debug("Connection error detected.");
          }

          switch (status) {
            case UNKNOWN -> {
              this.txnUnknown.put(transactionType);
            }
            case USER_ABORTED -> {
              this.txnAbort.put(transactionType);
            }
            case RETRY -> {
              this.txnRetry.put(transactionType);
            }
            case RETRY_DIFFERENT -> {
              this.txtRetryDifferent.put(transactionType);
            }
            case ERROR -> {
              this.txnErrors.put(transactionType);
            }
          }
        }
      }
    } catch (SQLException ex) {
      String msg =
          String.format(
              "Unexpected SQLException in '%s' when executing '%s' on [%s]",
              this, transactionType, databaseType.name());

      throw new RuntimeException(msg, ex);
    }
  }

  /**
   * Checks to see if the exception indicates that the current connection is read-only.
   *
   * @param ex
   * @return
   */
  private boolean indicatesReadOnly(SQLException ex) {
    String sqlState = ex.getSQLState();
    int errorCode = ex.getErrorCode();

    LOG.debug("sql state [{}] and error code [{}]", sqlState, errorCode);

    if (sqlState == null) {
      return false;
    }

    // ------------------
    // SqlServer: "SELECT TOP 10 * FROM sys.messages"
    // ------------------
    if (errorCode == 3906 && sqlState.equals("S0002")) {
      return true;
    }

    // ------------------
    // MYSQL:
    // https://dev.mysql.com/doc/connector-j/8.0/en/connector-j-reference-error-sqlstates.html
    // ------------------
    // TODO

    // ------------------
    // POSTGRES: https://www.postgresql.org/docs/current/errcodes-appendix.html
    // ------------------
    // TODO

    return false;
  }

  private boolean isRetryable(SQLException ex) {

    String sqlState = ex.getSQLState();
    int errorCode = ex.getErrorCode();

    LOG.debug("sql state [{}] and error code [{}]", sqlState, errorCode);

    if (sqlState == null) {
      return false;
    }

    if (ex instanceof SQLRecoverableException) {
      return true;
    }

    // ------------------
    // SqlServer: "SELECT TOP 10 * FROM sys.messages"
    // ------------------
    if (errorCode == 12222 && sqlState.equals("S0051")) {
      // Lock request time out period exceeded.
      return true;
    } else if (errorCode == 0 && sqlState.equals("HY008")) {
      // The query has timed out.
      return true;
    }

    // ------------------
    // MYSQL:
    // https://dev.mysql.com/doc/connector-j/8.0/en/connector-j-reference-error-sqlstates.html
    // ------------------
    if (errorCode == 1213 && sqlState.equals("40001")) {
      // MySQL ER_LOCK_DEADLOCK
      return true;
    } else if (errorCode == 1205 && sqlState.equals("40001")) {
      // MySQL ER_LOCK_WAIT_TIMEOUT
      return true;
    }

    // ------------------
    // POSTGRES: https://www.postgresql.org/docs/current/errcodes-appendix.html
    // ------------------
    // Postgres serialization_failure
    return errorCode == 0 && sqlState.equals("40001");
  }

  private boolean isTimeout(SQLException ex) {
    if (ex == null) return false;
    String s = ex.getSQLState();
    if (s == null) return false;

    // SQLServer query timeout
    if ("HY008".equals(s)) return true;

    // PostgreSQL: query_canceled
    if ("57014".equals(s)) return true;

    String msg = ex.getMessage();
    return msg != null && msg.toLowerCase().contains("timeout");
  }

  /**
   * Optional callback that can be used to initialize the Worker right before the benchmark
   * execution begins
   */
  protected void initialize() {
    // The default is to do nothing
  }

  /**
   * Invoke a single transaction for the given TransactionType
   *
   * @param conn TODO
   * @param txnType TODO
   * @return TODO
   * @throws UserAbortException TODO
   * @throws SQLException TODO
   */
  protected abstract TransactionStatus executeWork(Connection conn, TransactionType txnType)
      throws UserAbortException, SQLException;

  /** Called at the end of the test to do any clean up that may be required. */
  public void tearDown() {
    if (!this.configuration.getNewConnectionPerTxn() && this.conn != null) {
      try {
        conn.close();
      } catch (SQLException e) {
        LOG.error("Connection couldn't be closed.", e);
      }
    }
  }

  public void initializeState() {
    this.workloadState = this.configuration.getWorkloadState();
  }

  protected long getPreExecutionWaitInMillis(TransactionType type) {
    return 0;
  }

  protected long getPostExecutionWaitInMillis(TransactionType type) {
    return 0;
  }

  public int getAttemptedRequests() {
    return attemptedRequests.get();
  }

  public Map<String, Integer> getAttemptedRequestsByPriority() {
    return attemptedRequestsByPriority;
  }

  private boolean isPlbFreemiumReject(SQLException ex) {
    if (ex == null) return false;
    if ("08004".equals(ex.getSQLState())) return true;
    String m = ex.getMessage();
    return m != null && m.startsWith("PLB_REJECT_FREEMIUM");
  }

  private int nowSec() {
    long base = workloadState.getTestStartNs();
    long now = System.nanoTime();
    long diff = now - base;
    if (diff < 0) diff = 0;
    long sec = diff / 1_000_000_000L;
    if (sec > Integer.MAX_VALUE) sec = Integer.MAX_VALUE;
    return (int) sec;
  }

  private void incTs(String metric) {
    String tier = (workerPriorityClass == null) ? "unknown" : workerPriorityClass.toLowerCase();
    String key = metric + "|" + tier;
    int s = nowSec();
    TS.computeIfAbsent(key, k -> new ConcurrentHashMap<>())
        .computeIfAbsent(s, k -> new AtomicInteger(0))
        .incrementAndGet();
  }

  private static int getTs(String metric, String tier, int sec) {
    String key = metric + "|" + tier.toLowerCase();
    ConcurrentHashMap<Integer, AtomicInteger> m = TS.get(key);
    if (m == null) return 0;
    AtomicInteger v = m.get(sec);
    return (v == null) ? 0 : v.get();
  }

  private static String normalizeTier(String t) {
    if (t == null) return "unknown";
    t = t.trim().toLowerCase();
    if (t.length() >= 2) {
      if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))) {
        t = t.substring(1, t.length() - 1);
      }
    }
    return t;
  }

  private static int getTsRaw(String metric, String rawTier, int sec) {
    String key = metric + "|" + rawTier;
    ConcurrentHashMap<Integer, AtomicInteger> m = TS.get(key);
    if (m == null) return 0;
    AtomicInteger v = m.get(sec);
    return (v == null) ? 0 : v.get();
  }

  private static void dumpTimeSeriesToLog(Logger log) {
    java.util.Set<String> tiersSet = new java.util.HashSet<>();
    for (String key : TS.keySet()) {
      int idx = key.lastIndexOf('|');
      if (idx >= 0 && idx + 1 < key.length()) {
        tiersSet.add(key.substring(idx + 1));
      }
    }
    java.util.List<String> tiers = new java.util.ArrayList<>(tiersSet);
    java.util.Collections.sort(tiers);

    java.util.Set<Integer> secs = new java.util.HashSet<>();
    for (var e : TS.entrySet()) secs.addAll(e.getValue().keySet());

    java.util.List<Integer> sorted = new java.util.ArrayList<>(secs);
    java.util.Collections.sort(sorted);

    for (String tier : tiers) {
      for (int sec : sorted) {
        int ca = getTs("connect_attempt", tier, sec);
        int cs = getTs("connect_success", tier, sec);
        int cf = getTs("connect_fail", tier, sec);
        int cr = getTs("connect_reject", tier, sec);

        int qa = getTs("query_attempt", tier, sec);
        int qs = getTs("query_success", tier, sec);
        int qf = getTs("query_fail", tier, sec);
        int qt = getTs("query_timeout", tier, sec);

        int ab = getTs("freemium_abandon", tier, sec);

        if ((ca | cs | cf | cr | qa | qs | qf | qt | ab) == 0) continue;

        log.info(
            "BB_TS sec={} tier={} connect(a={},s={},f={},r={}) query(a={},s={},f={},t={}) abandon={}",
            sec,
            tier,
            ca,
            cs,
            cf,
            cr,
            qa,
            qs,
            qf,
            qt,
            ab);
      }
    }
  }

  public static void dumpTimeSeriesToCsv(java.io.PrintStream ps) {
    if (ps == null) return;

    java.util.Map<String, java.util.List<String>> aliases = new java.util.HashMap<>();
    for (String key : TS.keySet()) {
      int idx = key.lastIndexOf('|');
      if (idx < 0 || idx + 1 >= key.length()) continue;
      String rawTier = key.substring(idx + 1);
      String norm = normalizeTier(rawTier);
      aliases.computeIfAbsent(norm, k -> new java.util.ArrayList<>()).add(rawTier);
    }

    java.util.Set<Integer> secs = new java.util.HashSet<>();
    for (var e : TS.entrySet()) secs.addAll(e.getValue().keySet());
    java.util.List<Integer> sorted = new java.util.ArrayList<>(secs);
    java.util.Collections.sort(sorted);
    int maxSec = sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1);

    java.util.function.BiFunction<String, Integer, Integer> P =
        (metric, sec) -> {
          int sum = 0;
          for (String raw : aliases.getOrDefault("premium", java.util.List.of("premium"))) {
            sum += getTsRaw(metric, raw, sec);
          }
          return sum;
        };
    java.util.function.BiFunction<String, Integer, Integer> F =
        (metric, sec) -> {
          int sum = 0;
          for (String raw : aliases.getOrDefault("freemium", java.util.List.of("freemium"))) {
            sum += getTsRaw(metric, raw, sec);
          }
          return sum;
        };

    ps.println(
        "sec,"
            + "premium_connect_attempt,premium_connect_success,premium_connect_fail,premium_connect_reject,"
            + "premium_query_attempt,premium_query_success,premium_query_fail,premium_query_timeout,premium_freemium_abandon,"
            + "freemium_connect_attempt,freemium_connect_success,freemium_connect_fail,freemium_connect_reject,"
            + "freemium_query_attempt,freemium_query_success,freemium_query_fail,freemium_query_timeout,freemium_freemium_abandon");

    for (int sec = 0; sec <= maxSec; sec++) {
      int p_ca = P.apply("connect_attempt", sec);
      int p_cs = P.apply("connect_success", sec);
      int p_cf = P.apply("connect_fail", sec);
      int p_cr = P.apply("connect_reject", sec);

      int p_qa = P.apply("query_attempt", sec);
      int p_qs = P.apply("query_success", sec);
      int p_qf = P.apply("query_fail", sec);
      int p_qt = P.apply("query_timeout", sec);

      int p_ab = P.apply("freemium_abandon", sec);

      int f_ca = F.apply("connect_attempt", sec);
      int f_cs = F.apply("connect_success", sec);
      int f_cf = F.apply("connect_fail", sec);
      int f_cr = F.apply("connect_reject", sec);

      int f_qa = F.apply("query_attempt", sec);
      int f_qs = F.apply("query_success", sec);
      int f_qf = F.apply("query_fail", sec);
      int f_qt = F.apply("query_timeout", sec);

      int f_ab = F.apply("freemium_abandon", sec);

      // if
      // ((p_ca|p_cs|p_cf|p_cr|p_qa|p_qs|p_qf|p_qt|p_ab|f_ca|f_cs|f_cf|f_cr|f_qa|f_qs|f_qf|f_qt|f_ab) == 0) {
      //   continue;
      // }

      ps.printf(
          "%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
          sec, p_ca, p_cs, p_cf, p_cr, p_qa, p_qs, p_qf, p_qt, p_ab, f_ca, f_cs, f_cf, f_cr, f_qa,
          f_qs, f_qf, f_qt, f_ab);
    }

    ps.flush();
  }
}
