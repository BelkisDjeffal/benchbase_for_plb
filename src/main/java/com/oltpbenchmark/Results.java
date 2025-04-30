package com.oltpbenchmark;

import com.oltpbenchmark.LatencyRecord.Sample;
import com.oltpbenchmark.api.TransactionType;
import com.oltpbenchmark.types.State;
import com.oltpbenchmark.util.Histogram;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class Results {

  private final State state;
  private final long startTimestampMs;
  private final long nanoseconds;
  private final int measuredRequests;
  private final int attemptedRequests;
  private final DistributionStatistics distributionStatistics;
  private final List<LatencyRecord.Sample> latencySamples;
  private final Map<String, DistributionStatistics> latencyStatsByPriority = new HashMap<>();
  private final Map<String, Integer> attemptedRequestsByPriority = new HashMap<>();
  private final Map<String, Integer> requestsByPriority = new HashMap<>();
  private final Map<String, Integer> successByPriority = new HashMap<>();
  private final Histogram<TransactionType> unknown = new Histogram<>(false);
  private final Histogram<TransactionType> success = new Histogram<>(true);
  private final Histogram<TransactionType> abort = new Histogram<>(false);
  private final Histogram<TransactionType> retry = new Histogram<>(false);
  private final Histogram<TransactionType> error = new Histogram<>(false);
  private final Histogram<TransactionType> retryDifferent = new Histogram<>(false);
  private final Map<TransactionType, Histogram<String>> abortMessages = new HashMap<>();
  private final Map<String, Integer> terminalCounts = new HashMap<>();
  private final Map<String, Long> workerExecutionTimes;

  public Results(
      State state,
      long startTimestampMs,
      long elapsedNanoseconds,
      int measuredRequests,
      int attemptedRequests,
      Map<String, Integer> attemptedRequestsByPriority,
      DistributionStatistics distributionStatistics,
      final List<LatencyRecord.Sample> latencySamples,
      Map<String, Integer> aggregatedSuccessByPriority,
      Map<String, Integer> terminalCounts,
      Map<String, Long> workerExecutionTimes) {

    this.startTimestampMs = startTimestampMs;
    this.nanoseconds = elapsedNanoseconds;
    this.measuredRequests = measuredRequests;
    this.attemptedRequests = attemptedRequests;
    this.distributionStatistics = distributionStatistics;
    this.state = state;
    this.terminalCounts.putAll(normalizeKeys(terminalCounts));
    this.workerExecutionTimes = normalizeLongMap(workerExecutionTimes);

    if (distributionStatistics == null) {
      this.latencySamples = null;
    } else {
      this.latencySamples = List.copyOf(latencySamples);
    }

    // Group latency samples by normalized priority
    Map<String, List<LatencyRecord.Sample>> samplesByPriority =
        latencySamples.stream()
            .collect(Collectors.groupingBy(sample -> normalize(sample.getWorkerPriority())));

    for (Map.Entry<String, List<LatencyRecord.Sample>> entry : samplesByPriority.entrySet()) {
      String priority = entry.getKey();
      List<LatencyRecord.Sample> prioritySamples = entry.getValue();

      int[] latenciesArray = new int[prioritySamples.size()];
      for (int i = 0; i < prioritySamples.size(); i++) {
        latenciesArray[i] = prioritySamples.get(i).getLatencyMicrosecond();
      }

      latencyStatsByPriority.put(
          priority, DistributionStatistics.computeStatistics(latenciesArray));
      requestsByPriority.put(priority, prioritySamples.size());
    }

    this.successByPriority.putAll(normalizeKeys(aggregatedSuccessByPriority));
    this.attemptedRequestsByPriority.putAll(normalizeKeys(attemptedRequestsByPriority));
  }

  private static String normalize(String s) {
    return s.replace("\"", "").trim().toLowerCase();
  }

  private static Map<String, Integer> normalizeKeys(Map<String, Integer> input) {
    Map<String, Integer> normalized = new HashMap<>();
    for (Map.Entry<String, Integer> entry : input.entrySet()) {
      normalized.merge(normalize(entry.getKey()), entry.getValue(), Integer::sum);
    }
    return normalized;
  }

  private static Map<String, Long> normalizeLongMap(Map<String, Long> input) {
    Map<String, Long> normalized = new HashMap<>();
    for (Map.Entry<String, Long> entry : input.entrySet()) {
      normalized.merge(normalize(entry.getKey()), entry.getValue(), Long::sum);
    }
    return normalized;
  }

  public Map<String, Long> getWorkerExecutionTimes() {
    return workerExecutionTimes;
  }

  public State getState() {
    return state;
  }

  public DistributionStatistics getDistributionStatistics() {
    return distributionStatistics;
  }

  public Histogram<TransactionType> getSuccess() {
    return success;
  }

  public Histogram<TransactionType> getUnknown() {
    return unknown;
  }

  public Histogram<TransactionType> getAbort() {
    return abort;
  }

  public Histogram<TransactionType> getRetry() {
    return retry;
  }

  public Histogram<TransactionType> getError() {
    return error;
  }

  public Histogram<TransactionType> getRetryDifferent() {
    return retryDifferent;
  }

  public Map<TransactionType, Histogram<String>> getAbortMessages() {
    return abortMessages;
  }

  public double requestsPerSecondThroughput() {
    return (double) measuredRequests;
  }

  public double requestsPerSecondGoodput() {
    return (double) success.getSampleCount();
  }

  public List<Sample> getLatencySamples() {
    return latencySamples;
  }

  public long getStartTimestampMs() {
    return startTimestampMs;
  }

  public long getNanoseconds() {
    return nanoseconds;
  }

  public int getMeasuredRequests() {
    return measuredRequests;
  }

  public int getAttemptedRequests() {
    return attemptedRequests;
  }

  public double getNewThroughput() {
    return (double) attemptedRequests;
  }

  public double getGoodputForPriority(String priority) {
    priority = normalize(priority);
    long timeMs = workerExecutionTimes.getOrDefault(priority, 0L);
    if (timeMs == 0) return 0.0;
    return (double) successByPriority.getOrDefault(priority, 0) / (timeMs / 1000.0);
  }

  public DistributionStatistics getLatencyStatsForPriority(String priority) {
    priority = normalize(priority);
    return latencyStatsByPriority.getOrDefault(
        priority, new DistributionStatistics(0, new long[] {-1}, -1, -1));
  }

  public Map<String, DistributionStatistics> getLatencyStatsByPriority() {
    return Collections.unmodifiableMap(latencyStatsByPriority);
  }

  public double getThroughputForPriority(String priority) {
    priority = normalize(priority);
    long timeMs = workerExecutionTimes.getOrDefault(priority, 0L);
    if (timeMs == 0) return 0.0;
    return (double) attemptedRequestsByPriority.getOrDefault(priority, 0) / (timeMs / 1000.0);
  }

  public double getPerTerminalGoodputForPriority(String priority) {
    priority = normalize(priority);
    int count = terminalCounts.getOrDefault(priority, 0);
    if (count == 0) return 0.0;
    return getGoodputForPriority(priority) / count;
  }

  public double getPerTerminalThroughputForPriority(String priority) {
    priority = normalize(priority);
    int count = terminalCounts.getOrDefault(priority, 0);
    if (count == 0) return 0.0;
    return getThroughputForPriority(priority) / count;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("\nResults(state=")
        .append(state)
        .append(", nanoSeconds=")
        .append(nanoseconds)
        .append(", measuredRequests=")
        .append(attemptedRequests)
        .append(", succeededRequests=")
        .append(success.getSampleCount())
        .append(") = ")
        .append(getNewThroughput())
        .append("  (throughput), ")
        .append(requestsPerSecondGoodput())
        .append("  (goodput)");

    for (String priority : latencyStatsByPriority.keySet()) {
      int attempted = attemptedRequestsByPriority.getOrDefault(priority, 0);
      int succeeded = successByPriority.getOrDefault(priority, 0);

      sb.append("\nPriority: ")
          .append(priority)
          .append("\n  Total Attempted Queries: ")
          .append(attempted)
          .append("\n  Total Succeeded Queries: ")
          .append(succeeded)
          .append("\n  Throughput: ")
          .append(getThroughputForPriority(priority))
          .append("\n  Goodput: ")
          .append(getGoodputForPriority(priority))
          .append("\n  Per Terminal Throughput: ")
          .append(getPerTerminalThroughputForPriority(priority))
          .append("\n  Per Terminal Goodput: ")
          .append(getPerTerminalGoodputForPriority(priority))
          .append("\n  Latency Stats: ")
          .append(getLatencyStatsForPriority(priority));
    }

    return sb.toString();
  }
}
