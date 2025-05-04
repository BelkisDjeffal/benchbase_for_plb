package com.oltpbenchmark;

public class WorkerMetrics {
  public final int workerId;
  public final String priority;
  public final double throughput;
  public final double goodput;

  public WorkerMetrics(int workerId, String priority, double throughput, double goodput) {
    this.workerId = workerId;
    this.priority = priority;
    this.throughput = throughput;
    this.goodput = goodput;
  }
}
