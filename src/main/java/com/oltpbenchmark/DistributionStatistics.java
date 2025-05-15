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

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DistributionStatistics {
  @SuppressWarnings("unused")
  private static final Logger LOG = LoggerFactory.getLogger(DistributionStatistics.class);

  private static final double[] PERCENTILES = {0.0, 0.25, 0.5, 0.75, 0.9, 0.95, 0.99, 1.0};

  private static final int MINIMUM = 0;
  private static final int PERCENTILE_25TH = 1;
  private static final int MEDIAN = 2;
  private static final int PERCENTILE_75TH = 3;
  private static final int PERCENTILE_90TH = 4;
  private static final int PERCENTILE_95TH = 5;
  private static final int PERCENTILE_99TH = 6;
  private static final int MAXIMUM = 7;

  private final int count;
  private final double[] percentiles;
  private final double average;
  private final double standardDeviation;

  public DistributionStatistics(
      int count, double[] percentiles, double average, double standardDeviation) {
    this.count = count;
    this.percentiles = Arrays.copyOfRange(percentiles, 0, PERCENTILES.length);
    this.average = average;
    this.standardDeviation = standardDeviation;
  }

  /** Computes distribution statistics over values. WARNING: This will sort values. */
  public static DistributionStatistics computeStatistics(int[] valuesAsMicroseconds) {
    if (valuesAsMicroseconds.length == 0) {
      double[] percentiles = new double[PERCENTILES.length];
      Arrays.fill(percentiles, -1);
      return new DistributionStatistics(0, percentiles, -1, -1);
    }

    Arrays.sort(valuesAsMicroseconds);

    double sum = 0;
    for (int value1 : valuesAsMicroseconds) {
      sum += value1;
    }
    double average = sum / valuesAsMicroseconds.length;

    double sumDiffsSquared = 0;
    for (int value : valuesAsMicroseconds) {
      double v = value - average;
      sumDiffsSquared += v * v;
    }
    double standardDeviation = 0;
    if (valuesAsMicroseconds.length > 1) {
      standardDeviation = Math.sqrt(sumDiffsSquared / (valuesAsMicroseconds.length - 1));
    }

    // NOTE: NIST recommends interpolating. This just selects the closest
    // value, which is described as another common technique.
    // http://www.itl.nist.gov/div898/handbook/prc/section2/prc252.htm
    double[] percentiles = new double[PERCENTILES.length];
    for (int i = 0; i < percentiles.length; ++i) {
      int index = (int) (PERCENTILES[i] * valuesAsMicroseconds.length);
      if (index == valuesAsMicroseconds.length) {
        index = valuesAsMicroseconds.length - 1;
      }
      percentiles[i] = valuesAsMicroseconds[index];
    }

    // Convert to seconds
    double[] valuesInSeconds =
        Arrays.stream(valuesAsMicroseconds).mapToDouble(v -> v / 1_000_000.0).toArray();

    return computeStatistics(valuesInSeconds);
  }

  public static DistributionStatistics computeStatistics(double[] values) {
    if (values.length == 0) {
      double[] percentiles = new double[PERCENTILES.length];
      Arrays.fill(percentiles, -1);
      return new DistributionStatistics(0, percentiles, -1, -1);
    }

    Arrays.sort(values);

    double average = Arrays.stream(values).average().orElse(-1);
    double stddev = 0;
    if (values.length > 1) {
      double variance =
          Arrays.stream(values).map(v -> (v - average) * (v - average)).sum() / (values.length - 1);
      stddev = Math.sqrt(variance);
    }

    double[] percentiles = new double[PERCENTILES.length];
    for (int i = 0; i < PERCENTILES.length; i++) {
      int index = (int) (PERCENTILES[i] * values.length);
      if (index == values.length) index = values.length - 1;
      percentiles[i] = values[index];
    }

    return new DistributionStatistics(values.length, percentiles, average, stddev);
  }

  public int getCount() {
    return count;
  }

  public double getAverage() {
    return average;
  }

  public double getStandardDeviation() {
    return standardDeviation;
  }

  public double getMinimum() {
    return percentiles[MINIMUM];
  }

  public double get25thPercentile() {
    return percentiles[PERCENTILE_25TH];
  }

  public double getMedian() {
    return percentiles[MEDIAN];
  }

  public double get75thPercentile() {
    return percentiles[PERCENTILE_75TH];
  }

  public double get90thPercentile() {
    return percentiles[PERCENTILE_90TH];
  }

  public double get95thPercentile() {
    return percentiles[PERCENTILE_95TH];
  }

  public double get99thPercentile() {
    return percentiles[PERCENTILE_99TH];
  }

  public double getMaximum() {
    return percentiles[MAXIMUM];
  }

  @Override
  public String toString() {
    return "[min="
        + String.format(Locale.US, "%.6f", getMinimum())
        + ", 25th="
        + String.format(Locale.US, "%.6f", get25thPercentile())
        + ", median="
        + String.format(Locale.US, "%.6f", getMedian())
        + ", avg="
        + String.format(Locale.US, "%.6f", getAverage())
        + ", stddev="
        + String.format(Locale.US, "%.6f", getStandardDeviation())
        + ", 75th="
        + String.format(Locale.US, "%.6f", get75thPercentile())
        + ", 90th="
        + String.format(Locale.US, "%.6f", get90thPercentile())
        + ", 95th="
        + String.format(Locale.US, "%.6f", get95thPercentile())
        + ", 99th="
        + String.format(Locale.US, "%.6f", get99thPercentile())
        + ", max="
        + String.format(Locale.US, "%.6f", getMaximum())
        + "]";
  }

  public String toPlainString() {
    return "[count="
        + count
        + ", min="
        + String.format(Locale.US, "%.6f", getMinimum())
        + ", 25th="
        + String.format(Locale.US, "%.6f", get25thPercentile())
        + ", median="
        + String.format(Locale.US, "%.6f", getMedian())
        + ", avg="
        + String.format(Locale.US, "%.6f", getAverage())
        + ", stdv="
        + String.format(Locale.US, "%.6f", getStandardDeviation())
        + ", 75th="
        + String.format(Locale.US, "%.6f", get75thPercentile())
        + ", 90th="
        + String.format(Locale.US, "%.6f", get90thPercentile())
        + ", 95th="
        + String.format(Locale.US, "%.6f", get95thPercentile())
        + ", 99th="
        + String.format(Locale.US, "%.6f", get99thPercentile())
        + ", max="
        + String.format(Locale.US, "%.6f", getMaximum())
        + "]";
  }

  // public Map<String, Integer> toMap() {
  //   Map<String, Integer> distMap = new LinkedHashMap<>();
  //   distMap.put("Minimum Latency (seconds)", (int) getMinimum());
  //   distMap.put("25th Percentile Latency (seconds)", (int) get25thPercentile());
  //   distMap.put("Median Latency (seconds)", (int) getMedian());
  //   distMap.put("Average Latency (seconds)", (int) getAverage());
  //   distMap.put("75th Percentile Latency (seconds)", (int) get75thPercentile());
  //   distMap.put("90th Percentile Latency (seconds)", (int) get90thPercentile());
  //   distMap.put("95th Percentile Latency (seconds)", (int) get95thPercentile());
  //   distMap.put("99th Percentile Latency (seconds)", (int) get99thPercentile());
  //   distMap.put("Maximum Latency (seconds)", (int) getMaximum());
  //   return distMap;
  // }

  public Map<String, Double> toMap() {
    Map<String, Double> distMap = new LinkedHashMap<>();
    distMap.put("Minimum Latency (seconds)", getMinimum());
    distMap.put("25th Percentile Latency (seconds)", get25thPercentile());
    distMap.put("Median Latency (seconds)", getMedian());
    distMap.put("Average Latency (seconds)", getAverage());
    distMap.put("75th Percentile Latency (seconds)", get75thPercentile());
    distMap.put("90th Percentile Latency (seconds)", get90thPercentile());
    distMap.put("95th Percentile Latency (seconds)", get95thPercentile());
    distMap.put("99th Percentile Latency (seconds)", get99thPercentile());
    distMap.put("Maximum Latency (seconds)", getMaximum());
    return distMap;
  }
}
