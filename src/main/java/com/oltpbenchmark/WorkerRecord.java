package com.oltpbenchmark;

public class WorkerRecord {
  public final int workerId;
  public final String priority;
  public final int attempted;
  public final int succeeded;
  public final long startTimeMs;
  public final long endTimeMs;

  public WorkerRecord(
      int workerId,
      String priority,
      int attempted,
      int succeeded,
      long startTimeMs,
      long endTimeMs) {
    this.workerId = workerId;
    this.priority = priority;
    this.attempted = attempted;
    this.succeeded = succeeded;
    this.startTimeMs = startTimeMs;
    this.endTimeMs = endTimeMs;
  }
}
