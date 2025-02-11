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
import com.oltpbenchmark.api.TransactionTypes;
import com.oltpbenchmark.types.State;
import com.oltpbenchmark.util.Histogram;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class Results {

  private final State state;
  private final long startTimestampMs;
  private final long nanoseconds;
  private final int measuredRequests;
  private final DistributionStatistics distributionStatistics;
  private final List<LatencyRecord.Sample> latencySamples;
  // TransactionTypes transactionTypes;
  private final Map<String, DistributionStatistics> latencyStatsByPriority = new HashMap<>();
  private final Map<String, Integer> requestsByPriority = new HashMap<>();
  private final Map<String, Integer> successByPriority = new HashMap<>();
  private final Histogram<TransactionType> unknown = new Histogram<>(false);
  private final Histogram<TransactionType> success = new Histogram<>(true);
  private final Histogram<TransactionType> abort = new Histogram<>(false);
  private final Histogram<TransactionType> retry = new Histogram<>(false);
  private final Histogram<TransactionType> error = new Histogram<>(false);
  private final Histogram<TransactionType> retryDifferent = new Histogram<>(false);
  private final Map<TransactionType, Histogram<String>> abortMessages = new HashMap<>();

  public Results(
      State state,
      long startTimestampMs,
      long elapsedNanoseconds,
      int measuredRequests,
      DistributionStatistics distributionStatistics,
      final List<LatencyRecord.Sample> latencySamples,
      TransactionTypes transactionTypes) {

    this.startTimestampMs = startTimestampMs;
    this.nanoseconds = elapsedNanoseconds;
    this.measuredRequests = measuredRequests;
    this.distributionStatistics = distributionStatistics;
    this.state = state;

    if (distributionStatistics == null) {
      this.latencySamples = null;
    } else {
      this.latencySamples = List.copyOf(latencySamples);
    }
    // Group latency samples by priority
    Map<String, List<LatencyRecord.Sample>> samplesByPriority =
        latencySamples.stream().collect(Collectors.groupingBy(Sample::getWorkerPriority));

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

    // Compute success counts for each transaction type
    for (LatencyRecord.Sample sample : latencySamples) {
      String priority = sample.getWorkerPriority();
      TransactionType txnType = transactionTypes.getType(sample.getTransactionType());

      // Get the number of successful executions for this transaction type
      Integer successCount = success.get(txnType);
      successCount = (successCount == null) ? 0 : successCount;

      // Update success count per priority
      successByPriority.put(priority, successByPriority.getOrDefault(priority, 0) + successCount);

      System.out.println(
          "Priority: "
              + priority
              + ", Transaction: "
              + txnType.getName()
              + ", Success Count: "
              + successCount
              + ", Updated successByPriority: "
              + successByPriority.get(priority));
    }

    // Map<String, List<LatencyRecord.Sample>> samplesByPriority =
    //     latencySamples.stream().collect(Collectors.groupingBy(Sample::getWorkerPriority));

    // for (Map.Entry<String, List<LatencyRecord.Sample>> entry : samplesByPriority.entrySet()) {
    //   String priority = entry.getKey();
    //   List<LatencyRecord.Sample> prioritySamples = entry.getValue();

    //   int[] latenciesArray = new int[prioritySamples.size()];
    //   for (int i = 0; i < prioritySamples.size(); i++) {
    //     latenciesArray[i] = prioritySamples.get(i).getLatencyMicrosecond();
    //   }

    //   latencyStatsByPriority.put(
    //       priority, DistributionStatistics.computeStatistics(latenciesArray));

    //   requestsByPriority.put(priority, prioritySamples.size());
    // }

    // for (LatencyRecord.Sample sample : latencySamples) {
    //   String priority = sample.getWorkerPriority();
    //   TransactionType txnType =
    //       transactionTypes.getType(sample.getTransactionType()); // ✅ Correct lookup

    //   // Ensure priority exists in the success map
    //   successByPriority.putIfAbsent(priority, 0);

    //   // Get successful executions for this transaction type
    //   Integer successCount = success.get(txnType);
    //   successCount = (successCount == null) ? 0 : successCount;

    //   // If this transaction type has recorded successes, update the counter
    //   successByPriority.put(priority, successByPriority.get(priority) + successCount);

    //   // Debugging: Print transaction details
    //   System.out.println(
    //       "Priority: "
    //           + priority
    //           + ", Transaction: "
    //           + txnType.getName()
    //           + ", Success Count: "
    //           + successCount);
    // }

    // // for (LatencyRecord.Sample sample : latencySamples) {
    // //   String priority = sample.getWorkerPriority();
    // //   if (!successByPriority.containsKey(priority)) {
    // //     successByPriority.put(priority, 0);
    // //   }
    // //   if (success.getSampleCount() > 0) {
    // //     successByPriority.put(priority, successByPriority.get(priority) + 1);
    // //   }
    // // }
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
    return (double) measuredRequests / (double) nanoseconds * 1e9;
  }

  public double requestsPerSecondGoodput() {
    return (double) success.getSampleCount() / (double) nanoseconds * 1e9;
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

  public double getThroughputForPriority(String priority) {
    return (double) requestsByPriority.getOrDefault(priority, 0) / nanoseconds * 1e9;
  }

  public double getGoodputForPriority(String priority) {
    return (double) successByPriority.getOrDefault(priority, 0) / nanoseconds * 1e9;
  }

  public DistributionStatistics getLatencyStatsForPriority(String priority) {
    return latencyStatsByPriority.getOrDefault(
        priority, new DistributionStatistics(0, new long[] {-1}, -1, -1));
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("\nResults(state=");
    sb.append(state);
    sb.append(", nanoSeconds=");
    sb.append(nanoseconds);
    sb.append(", measuredRequests=");
    sb.append(measuredRequests);
    sb.append(") = ");
    sb.append(requestsPerSecondThroughput());
    sb.append(" requests/sec (throughput)");
    sb.append(", ");
    sb.append("succeededRequests=");
    sb.append(success.getSampleCount());
    sb.append(", ");
    sb.append(requestsPerSecondGoodput());
    sb.append(" requests/sec (goodput)");

    for (String priority : latencyStatsByPriority.keySet()) {
      sb.append("\nPriority: ").append(priority);
      sb.append("\n  Throughput: ")
          .append(getThroughputForPriority(priority))
          .append(" requests/sec");
      sb.append("\n  Goodput: ").append(getGoodputForPriority(priority)).append(" requests/sec");
      // sb.append("\n  Latency Stats: ").append(getLatencyStatsForPriority(priority));
    }
    return sb.toString();
  }
}
