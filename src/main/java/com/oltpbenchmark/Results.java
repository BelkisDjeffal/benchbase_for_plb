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
  // TransactionTypes transactionTypes;
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
      Map<String, Integer> terminalCounts) {

    this.startTimestampMs = startTimestampMs;
    this.nanoseconds = elapsedNanoseconds;
    this.measuredRequests = measuredRequests;
    this.attemptedRequests = attemptedRequests;
    // this.attemptedRequestsByPriority.putAll(attemptedRequestsByPriority);
    this.distributionStatistics = distributionStatistics;
    this.state = state;
    this.terminalCounts.putAll(terminalCounts);

    if (distributionStatistics == null) {
      this.latencySamples = null;
    } else {
      this.latencySamples = List.copyOf(latencySamples);
    }
    // Group latency samples by priority
    Map<String, List<LatencyRecord.Sample>> samplesByPriority =
        latencySamples.stream()
            .collect(
                Collectors.groupingBy(
                    sample -> sample.getWorkerPriority().replace("\"", "").trim()));

    for (Map.Entry<String, List<LatencyRecord.Sample>> entry1 : samplesByPriority.entrySet()) {
      String priority = entry1.getKey();
      List<LatencyRecord.Sample> prioritySamples = entry1.getValue();

      int[] latenciesArray = new int[prioritySamples.size()];
      for (int i = 0; i < prioritySamples.size(); i++) {
        latenciesArray[i] = prioritySamples.get(i).getLatencyMicrosecond();
      }

      latencyStatsByPriority.put(
          priority, DistributionStatistics.computeStatistics(latenciesArray));
      requestsByPriority.put(priority, prioritySamples.size());

      Map<String, Integer> cleanedSuccessByPriority = new HashMap<>();
      for (Map.Entry<String, Integer> entry3 : aggregatedSuccessByPriority.entrySet()) {
        cleanedSuccessByPriority.put(entry3.getKey().replace("\"", "").trim(), entry3.getValue());
      }
      this.successByPriority.putAll(cleanedSuccessByPriority);

      Map<String, Integer> cleanedattemptedRequestsByPriority = new HashMap<>();
      for (Map.Entry<String, Integer> entry4 : attemptedRequestsByPriority.entrySet()) {
        cleanedattemptedRequestsByPriority.put(
            entry4.getKey().replace("\"", "").trim(), entry4.getValue());
      }
      this.attemptedRequestsByPriority.putAll(cleanedattemptedRequestsByPriority);
    }
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

  public double getThroughputForPriority(String priority) {
    return (double) requestsByPriority.getOrDefault(priority, 0);
  }

  public double getGoodputForPriority(String priority) {
    return (double) successByPriority.getOrDefault(priority, 0);
  }

  public DistributionStatistics getLatencyStatsForPriority(String priority) {
    return latencyStatsByPriority.getOrDefault(
        priority, new DistributionStatistics(0, new long[] {-1}, -1, -1));
  }

  public Map<String, DistributionStatistics> getLatencyStatsByPriority() {
    return Collections.unmodifiableMap(latencyStatsByPriority);
  }

  public double getNewThroughputForPriority(String priority) {
    return (double) attemptedRequestsByPriority.getOrDefault(priority, 0);
  }

  public double getPerTerminalThroughputForPriority(String priority) {
    int count = terminalCounts.getOrDefault(priority, 0);
    if (count == 0) {
      return 0.0;
    }
    return getThroughputForPriority(priority) / count;
  }

  public double getPerTerminalGoodputForPriority(String priority) {
    int count = terminalCounts.getOrDefault(priority, 0);
    if (count == 0) {
      return 0.0;
    }
    return getGoodputForPriority(priority) / count;
  }

  public double getPerTerminalAttemptedThroughputForPriority(String priority) {
    int count = terminalCounts.getOrDefault(priority, 0);
    if (count == 0) {
      return 0.0;
    }
    return getNewThroughputForPriority(priority) / count;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("\nResults(state=");
    sb.append(state);
    sb.append(", nanoSeconds=");
    sb.append(nanoseconds);

    sb.append(", measuredRequests=");
    sb.append(attemptedRequests);
    sb.append(", ");
    sb.append("succeededRequests=");
    sb.append(success.getSampleCount());

    sb.append(") = ");
    sb.append(getNewThroughput());
    sb.append("  (throughput)");
    sb.append(", ");

    sb.append(requestsPerSecondGoodput());
    sb.append("  (goodput)");

    for (String priority : latencyStatsByPriority.keySet()) {
      sb.append("\nPriority: ")
          .append(priority)
          .append("\n  Throughput: ")
          .append(getNewThroughputForPriority(priority))
          .append(" ")
          .append("\n  Goodput: ")
          .append(getGoodputForPriority(priority))
          .append(" ")
          .append("\n  Per Terminal Throughput: ")
          .append(getPerTerminalAttemptedThroughputForPriority(priority))
          .append(" ")
          .append("\n  Per Terminal Goodput: ")
          .append(getPerTerminalGoodputForPriority(priority))
          .append(" ")
          .append("\n  Latency Stats: ")
          .append(getLatencyStatsForPriority(priority));
    }
    return sb.toString();
  }
}
