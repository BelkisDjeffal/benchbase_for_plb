package com.oltpbenchmark;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExecutionDistributionStatistics {

  public static Map<String, List<WorkerMetrics>> computeMetrics(List<WorkerRecord> records) {
    Map<String, List<WorkerMetrics>> result = new HashMap<>();
    for (WorkerRecord r : records) {
      // System.out.println(
      //     "WorkerRecord: id="
      //         + r.workerId
      //         + ", priority="
      //         + r.priority
      //         + ", attempted="
      //         + r.attempted
      //         + ", succeeded="
      //         + r.succeeded
      //         + ", startTimeMs="
      //         + r.startTimeMs
      //         + ", endTimeMs="
      //         + r.endTimeMs);

      long durationMs = r.endTimeMs - r.startTimeMs;
      if (durationMs <= 0) continue;
      double durationSec = durationMs / 1000.0;
      double throughput = r.attempted / durationSec;
      double goodput = r.succeeded / durationSec;

      WorkerMetrics wm = new WorkerMetrics(r.workerId, r.priority, throughput, goodput);
      result.computeIfAbsent(r.priority, k -> new ArrayList<>()).add(wm);
    }
    return result;
  }

  public static DistributionStatistics computeOverallThroughputStats(List<WorkerRecord> records) {
    double[] values =
        records.stream()
            .filter(r -> (r.endTimeMs - r.startTimeMs) > 0)
            .mapToDouble(
                r -> {
                  double durationSec = (r.endTimeMs - r.startTimeMs) / 1000.0;
                  return r.attempted / durationSec;
                })
            .toArray();

    return DistributionStatistics.computeStatistics(values);
  }

  public static DistributionStatistics computeOverallGoodputStats(List<WorkerRecord> records) {
    double[] values =
        records.stream()
            .filter(r -> (r.endTimeMs - r.startTimeMs) > 0)
            .mapToDouble(
                r -> {
                  double durationSec = (r.endTimeMs - r.startTimeMs) / 1000.0;
                  return r.succeeded / durationSec;
                })
            .toArray();

    return DistributionStatistics.computeStatistics(values);
  }

  public static Map<String, DistributionStatistics> computeThroughputStats(
      Map<String, List<WorkerMetrics>> metricsByPriority) {
    Map<String, DistributionStatistics> result = new HashMap<>();
    for (var entry : metricsByPriority.entrySet()) {
      double[] values = entry.getValue().stream().mapToDouble(m -> m.throughput).toArray();
      result.put(normalize(entry.getKey()), DistributionStatistics.computeStatistics(values));
    }
    return result;
  }

  public static Map<String, DistributionStatistics> computeGoodputStats(
      Map<String, List<WorkerMetrics>> metricsByPriority) {
    Map<String, DistributionStatistics> result = new HashMap<>();
    for (var entry : metricsByPriority.entrySet()) {
      double[] values = entry.getValue().stream().mapToDouble(m -> m.goodput).toArray();
      result.put(normalize(entry.getKey()), DistributionStatistics.computeStatistics(values));
    }
    return result;
  }

  private static String normalize(String s) {
    return s.replace("\"", "").trim().toLowerCase();
  }
}
