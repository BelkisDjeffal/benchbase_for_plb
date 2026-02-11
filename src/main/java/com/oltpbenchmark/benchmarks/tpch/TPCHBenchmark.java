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

/***
 *   TPC-H implementation
 *
 *   Ben Reilly (bd.reilly@gmail.com)
 *   Ippokratis Pandis (ipandis@us.ibm.com)
 *
 ***/

package com.oltpbenchmark.benchmarks.tpch;

import com.oltpbenchmark.WorkloadConfiguration;
import com.oltpbenchmark.api.BenchmarkModule;
import com.oltpbenchmark.api.Loader;
import com.oltpbenchmark.api.Worker;
import com.oltpbenchmark.benchmarks.tpch.procedures.Q1;
import com.oltpbenchmark.util.SeedUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TPCHBenchmark extends BenchmarkModule {
  private static final Logger LOG = LoggerFactory.getLogger(TPCHBenchmark.class);

  public TPCHBenchmark(WorkloadConfiguration workConf) {
    super(workConf);
  }

  @Override
  protected Package getProcedurePackageImpl() {
    return (Q1.class.getPackage());
  }

  @Override
  protected List<Worker<? extends BenchmarkModule>> makeWorkersImpl() {
    List<Worker<? extends BenchmarkModule>> workers = new ArrayList<>();
    int numTerminals = workConf.getTerminals();
    List<String> priorityClasses = workConf.getTerminalPriorityClasses();
    List<Integer> priorityWeights = workConf.getTerminalPriorityClassesWeights();

    // Ensure the total weights match the number of terminals
    int totalWeights = priorityWeights.stream().mapToInt(Integer::intValue).sum();
    if (totalWeights != numTerminals) {
      throw new IllegalArgumentException(
          "The total of priority weights must equal the number of terminals");
    }

    // Assign terminals based on the exact weight values
    int workerIdx = 0;
    for (int classIdx = 0; classIdx < priorityClasses.size(); classIdx++) {
      String priorityClass = priorityClasses.get(classIdx);
      int weight = priorityWeights.get(classIdx);

      for (int j = 0; j < weight; j++) {
        workers.add(new TPCHWorker(this, workerIdx++, priorityClass));
      }
    }


    Collections.shuffle(workers, new Random(SeedUtil.derive("tpch.shuffle")));
    return workers;
  }

  @Override
  protected Loader<TPCHBenchmark> makeLoaderImpl() {
    return new TPCHLoader(this);
  }
}
