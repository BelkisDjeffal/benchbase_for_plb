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

package com.oltpbenchmark.util;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.configuration2.XMLConfiguration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.configuration2.io.FileHandler;

import com.oltpbenchmark.DistributionStatistics;
import com.oltpbenchmark.LatencyRecord;
import com.oltpbenchmark.Results;
import com.oltpbenchmark.ThreadBench;
import com.oltpbenchmark.api.TransactionType;
import com.oltpbenchmark.api.collectors.DBParameterCollector;
import com.oltpbenchmark.api.collectors.DBParameterCollectorGen;
import com.oltpbenchmark.types.DatabaseType;

public class ResultWriter {

  public static final double MILLISECONDS_FACTOR = 1e3;

  private static final String[] IGNORE_CONF = {"type", "driver", "url", "username", "password"};

  private static final String[] BENCHMARK_KEY_FIELD = {
    "isolation", "scalefactor", "terminals", "terminalClassesWeights", "time"
  };

  private final XMLConfiguration expConf;
  private final DBParameterCollector collector;
  private final Results results;
  private final DatabaseType dbType;
  private final String benchType;

  public ResultWriter(Results r, XMLConfiguration conf, CommandLine argsLine) {
    this.expConf = conf;
    this.results = r;
    this.dbType = DatabaseType.valueOf(expConf.getString("type").toUpperCase());
    this.benchType = argsLine.getOptionValue("b");

    String dbUrl = expConf.getString("url");
    String username = expConf.getString("username");
    String password = expConf.getString("password");

    this.collector = DBParameterCollectorGen.getCollector(dbType, dbUrl, username, password);
  }

  private Map<String, Integer> getTerminalCounts() {
    String weights = expConf.getString("terminalClassesWeights");
    if (weights == null || weights.trim().isEmpty()) {
      throw new IllegalStateException("Missing terminalClassesWeights configuration");
    }

    String[] parts = weights.split(",");
    if (parts.length < 2) {
      throw new IllegalStateException("Expected at least two values in terminalClassesWeights");
    }

    Map<String, Integer> counts = new HashMap<>();
    try {
      counts.put("premium", Integer.parseInt(parts[0].trim()));
      counts.put("freemium", Integer.parseInt(parts[1].trim()));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid format in terminalClassesWeights: " + weights, e);
    }

    return counts;
  }

  public void writeParams(PrintStream os) {
    String dbConf = collector.collectParameters();
    os.print(dbConf);
  }

  public void writeMetrics(PrintStream os) {
    os.print(collector.collectMetrics());
  }

  public boolean hasMetrics() {
    return collector.hasMetrics();
  }

  public void writeConfig(PrintStream os) throws ConfigurationException {

    XMLConfiguration outputConf = (XMLConfiguration) expConf.clone();
    for (String key : IGNORE_CONF) {
      outputConf.clearProperty(key);
    }

    FileHandler handler = new FileHandler(outputConf);
    handler.save(os);
  }

  private static String normalizePriority(String priority) {
    if (priority == null) {
      return "unknown";
    }
    return priority.replace("\"", "").trim().toLowerCase();
  }

  private static void addLatencySample(
      Map<Integer, Map<String, List<Integer>>> buckets,
      int sec,
      String priority,
      int latencyUs) {

    buckets
        .computeIfAbsent(sec, k -> new TreeMap<>())
        .computeIfAbsent(priority, k -> new ArrayList<>())
        .add(latencyUs);
  }

  private static void appendLatencyStats(StringBuilder row, List<Integer> latenciesUs) {
    row.append(",");

    if (latenciesUs == null || latenciesUs.isEmpty()) {
      row.append("0,,,,");
      return;
    }

    int[] values = new int[latenciesUs.size()];
    for (int i = 0; i < latenciesUs.size(); i++) {
      values[i] = latenciesUs.get(i);
    }

    DistributionStatistics stats = DistributionStatistics.computeStatistics(values);

    row.append(stats.getCount()).append(",");
    row.append(formatMs(stats.getAverage())).append(",");
    row.append(formatMs(stats.getMedian())).append(",");
    row.append(formatMs(stats.get95thPercentile())).append(",");
    row.append(formatMs(stats.get99thPercentile()));
  }

  private static String formatMs(double microseconds) {
    return String.format("%.3f", microseconds / MILLISECONDS_FACTOR);
  }

  public void writeSummary(PrintStream os) {
    Map<String, Object> summaryMap = new LinkedHashMap<>();
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    Date now = new Date();
    String[] timeValues = expConf.getStringArray("works.work.time");
    String[] rateValues = expConf.getStringArray("works.work.rate");
    Map<String, Integer> terminalCounts = getTerminalCounts();

    summaryMap.put("Start timestamp (milliseconds)", results.getStartTimestampMs());
    summaryMap.put("Current Timestamp (milliseconds)", now.getTime());
    summaryMap.put("Elapsed Time (nanoseconds)", results.getNanoseconds());
    summaryMap.put("DBMS Type", dbType);
    summaryMap.put("DBMS Version", collector.collectVersion());
    summaryMap.put("Benchmark Type", benchType);
    summaryMap.put("time", timeValues.length > 0 ? timeValues[0] : "NOT FOUND");
    summaryMap.put("rate", rateValues.length > 0 ? rateValues[0] : "NOT FOUND");
    summaryMap.put("Final State", results.getState());
    summaryMap.put("Measured Requests", results.getAttemptedRequests());
    for (String field : BENCHMARK_KEY_FIELD) {
      summaryMap.put(field, expConf.getString(field));
    }
    summaryMap.put("Throughput Distribution (requests/second)", results.getOverallThroughput());
    summaryMap.put("Goodput Distribution (requests/second)", results.getOverallGoodput());
    summaryMap.put("Latency Distribution", results.getDistributionStatistics());
    summaryMap.put("Per-Priority Metrics", new HashMap<>());
    for (String priority : terminalCounts.keySet()) {
      Map<String, Object> priorityMetrics = new HashMap<>();

      priorityMetrics.put(
          "Throughput Distribution (req/s)", results.getThroughputStatsForPriority(priority));
      priorityMetrics.put(
          "Goodput Distribution (req/s)", results.getGoodputStatsForPriority(priority));
      priorityMetrics.put("Latency Distribution", results.getLatencyStatsForPriority(priority));

      @SuppressWarnings("unchecked")
      Map<String, Object> perPriorityMetrics =
          (Map<String, Object>) summaryMap.get("Per-Priority Metrics");

      if (perPriorityMetrics == null) {
        perPriorityMetrics = new HashMap<>(); // Initialize if null
        summaryMap.put("Per-Priority Metrics", perPriorityMetrics);
      }

      perPriorityMetrics.put(priority, priorityMetrics);
    }
    os.println(JSONUtil.format(JSONUtil.toJSONString(summaryMap)));
  }

  public void writeResults(int windowSizeSeconds, PrintStream out) {
    writeResults(windowSizeSeconds, out, TransactionType.INVALID);
  }

  public void writeResults(int windowSizeSeconds, PrintStream out, TransactionType txType) {
    String[] header = {
      "Time (seconds)",
      "Throughput (requests/second)",
      "Average Latency (millisecond)",
      "Minimum Latency (millisecond)",
      "25th Percentile Latency (millisecond)",
      "Median Latency (millisecond)",
      "75th Percentile Latency (millisecond)",
      "90th Percentile Latency (millisecond)",
      "95th Percentile Latency (millisecond)",
      "99th Percentile Latency (millisecond)",
      "Maximum Latency (millisecond)",
      "tp (req/s) scaled"
    };
    out.println(StringUtil.join(",", header));
    int i = 0;
    for (DistributionStatistics s :
        new ThreadBench.TimeBucketIterable(
            results.getLatencySamples(), windowSizeSeconds, txType)) {
      out.printf(
          "%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f\n",
          i * windowSizeSeconds,
          (double) s.getCount() / windowSizeSeconds,
          s.getAverage() / MILLISECONDS_FACTOR,
          s.getMinimum() / MILLISECONDS_FACTOR,
          s.get25thPercentile() / MILLISECONDS_FACTOR,
          s.getMedian() / MILLISECONDS_FACTOR,
          s.get75thPercentile() / MILLISECONDS_FACTOR,
          s.get90thPercentile() / MILLISECONDS_FACTOR,
          s.get95thPercentile() / MILLISECONDS_FACTOR,
          s.get99thPercentile() / MILLISECONDS_FACTOR,
          s.getMaximum() / MILLISECONDS_FACTOR,
          MILLISECONDS_FACTOR / s.getAverage());
      i += 1;
    }
  }

  public void writeSamples(PrintStream out) {
    writeSamples(1, out, TransactionType.INVALID);
  }

  public void writeSamples(int windowSizeSeconds, PrintStream out, TransactionType txType) {
    String[] header = {
      "Time (seconds)",
      "Requests",
      "Throughput (requests/second)",
      "Minimum Latency (microseconds)",
      "25th Percentile Latency (microseconds)",
      "Median Latency (microseconds)",
      "Average Latency (microseconds)",
      "75th Percentile Latency (microseconds)",
      "90th Percentile Latency (microseconds)",
      "95th Percentile Latency (microseconds)",
      "99th Percentile Latency (microseconds)",
      "Maximum Latency (microseconds)"
    };
    out.println(StringUtil.join(",", header));
    int i = 0;
    for (DistributionStatistics s :
        new ThreadBench.TimeBucketIterable(
            results.getLatencySamples(), windowSizeSeconds, txType)) {
      out.printf(
          "%d,%d,%.3f,%d,%d,%d,%d,%d,%d,%d,%d,%d\n",
          i * windowSizeSeconds,
          s.getCount(),
          (double) s.getCount() / windowSizeSeconds,
          (int) s.getMinimum(),
          (int) s.get25thPercentile(),
          (int) s.getMedian(),
          (int) s.getAverage(),
          (int) s.get75thPercentile(),
          (int) s.get90thPercentile(),
          (int) s.get95thPercentile(),
          (int) s.get99thPercentile(),
          (int) s.getMaximum());
      i += 1;
    }
  }

  public void writeLatencyTimeSeries(PrintStream out) {
    String[] header = {
      "sec",

      "premium_count",
      "premium_avg_ms",
      "premium_median_ms",
      "premium_p95_ms",
      "premium_p99_ms",

      "freemium_count",
      "freemium_avg_ms",
      "freemium_median_ms",
      "freemium_p95_ms",
      "freemium_p99_ms",

      "overall_count",
      "overall_avg_ms",
      "overall_median_ms",
      "overall_p95_ms",
      "overall_p99_ms"
    };

    out.println(StringUtil.join(",", header));

    List<LatencyRecord.Sample> samples = results.getLatencySamples();
    if (samples == null || samples.isEmpty()) {
      return;
    }

    long baseNs = results.getTestStartNanosecond();

    Map<Integer, Map<String, List<Integer>>> buckets = new TreeMap<>();
    int maxSec = 0;

    for (LatencyRecord.Sample sample : samples) {
      long endNs = sample.getStartNanosecond() + sample.getLatencyMicrosecond() * 1000L;
      long relativeNs = endNs - baseNs;

      if (relativeNs < 0) {
        relativeNs = 0;
      }

      int sec = (int) (relativeNs / 1_000_000_000L);
      maxSec = Math.max(maxSec, sec);

      String priority = normalizePriority(sample.getWorkerPriority());
      int latencyUs = sample.getLatencyMicrosecond();

      addLatencySample(buckets, sec, "overall", latencyUs);

      if ("premium".equals(priority) || "freemium".equals(priority)) {
        addLatencySample(buckets, sec, priority, latencyUs);
      }
    }

    for (int sec = 0; sec <= maxSec; sec++) {
      Map<String, List<Integer>> byPriority = buckets.get(sec);

      StringBuilder row = new StringBuilder();
      row.append(sec);

      appendLatencyStats(row, byPriority == null ? null : byPriority.get("premium"));
      appendLatencyStats(row, byPriority == null ? null : byPriority.get("freemium"));
      appendLatencyStats(row, byPriority == null ? null : byPriority.get("overall"));

      out.println(row);
    }
  }

  public void writeRaw(List<TransactionType> activeTXTypes, PrintStream out) {

    // This is needed because nanTime does not guarantee offset... we
    // ground it (and round it) to ms from 1970-01-01 like currentTime
    double x = ((double) System.nanoTime() / (double) 1000000000);
    double y = ((double) System.currentTimeMillis() / (double) 1000);
    double offset = x - y;

    // long startNs = latencySamples.get(0).startNs;
    String[] header = {
      "Transaction Type Index",
      "Transaction Name",
      "Start Time (microseconds)",
      "Latency (microseconds)",
      "Worker Id (start number)",
      "Phase Id (index in config file)"
    };
    out.println(StringUtil.join(",", header));
    for (LatencyRecord.Sample s : results.getLatencySamples()) {
      double startUs = ((double) s.getStartNanosecond() / (double) 1000000000);
      String[] row = {
        Integer.toString(s.getTransactionType()),
        // Important!
        // The TxnType offsets start at 1!
        activeTXTypes.get(s.getTransactionType() - 1).getName(),
        String.format("%10.6f", startUs - offset),
        Integer.toString(s.getLatencyMicrosecond()),
        Integer.toString(s.getWorkerId()),
        Integer.toString(s.getPhaseId()),
      };
      out.println(StringUtil.join(",", row));
    }
  }
}
