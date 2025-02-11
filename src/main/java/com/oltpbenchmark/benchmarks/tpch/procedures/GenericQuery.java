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

package com.oltpbenchmark.benchmarks.tpch.procedures;

import com.oltpbenchmark.api.Procedure;
import com.oltpbenchmark.util.RandomGenerator;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.text.SimpleDateFormat;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class GenericQuery extends Procedure {

  protected static final Logger LOG = LoggerFactory.getLogger(GenericQuery.class);

  // Static variable to hold the timestamp of the first query execution
  private static String firstTimestamp = null;

  private int workerId; // Field to store the worker ID

  public void setWorkerId(int workerId) {
    this.workerId = workerId;
  }

  // Abstract method to get a PreparedStatement for the query
  protected abstract PreparedStatement getStatement(
      Connection conn, RandomGenerator rand, double scaleFactor) throws SQLException;

  // Main method to run the query and write its execution plan to a file
  public void run(Connection conn, RandomGenerator rand, double scaleFactor) throws SQLException {

    PreparedStatement stmt = null;
    try {
      // Set the timestamp if this is the first query execution
      if (firstTimestamp == null) {
        firstTimestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
      }
      stmt = getStatement(conn, rand, scaleFactor);
      try (ResultSet rs = stmt.executeQuery()) {
        StringBuilder explainOutput = new StringBuilder();
        while (rs.next()) {
          explainOutput.append(rs.getString(1)).append("\n");
        }
        // Generate a single filename using the first query's timestamp
        String fileName = generateFileName();
        // Append the execution plan to the file
        writeExecutionPlanToFile(fileName, explainOutput.toString(), getClass().getSimpleName());
      }
    } catch (SQLSyntaxErrorException ex) {
      if (LOG.isDebugEnabled()) {
        LOG.debug(
            this.getClass().getName() + ": stmt: " + (stmt != null ? stmt.toString() : "null"));
      }
      throw ex;
    }
  }

  // Method to generate the filename for the execution plans file
  private String generateFileName() {
    // Use the static timestamp for file naming
    return String.format("worker_%d_execution_plans_%s.txt", workerId, firstTimestamp);
  }

  // Method to write the execution plan to the file
  private void writeExecutionPlanToFile(String fileName, String executionPlan, String queryName) {
    try (BufferedWriter writer =
        new BufferedWriter(new FileWriter(fileName, true))) { // Open file in append mode
      writer.write("Query: " + queryName + "\n");
      writer.write("Query output:\n");
      writer.write(executionPlan);
      writer.write("\n\n");
    } catch (IOException e) {
      LOG.error("Failed to write execution plan to file", e);
    }
  }
}
