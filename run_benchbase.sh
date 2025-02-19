#!/bin/bash

# Check if the number of iterations is provided
if [ -z "$1" ]; then
  echo "Usage: $0 <number_of_iterations>"
  exit 1
fi

# Assign the number of iterations
n=$1

# Change to the target directory
cd target/benchbase-postgres || { echo "Directory not found!"; exit 1; }

# Run the command n times
for ((i=1; i<=n; i++))
do
  echo "Running iteration $i at $(date)..."
  java -cp benchbase.jar:lib/plb-0.0.1-SNAPSHOT.jar -Djava.util.logging.config.file=src/main/resources/logging.properties com.oltpbenchmark.DBWorkload -b tpch -c config/postgres/sample_tpch_config.xml --create=false --load=false --execute=true > output.txt
done

echo "Completed $n iterations."
