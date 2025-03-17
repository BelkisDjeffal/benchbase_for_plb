#!/bin/bash

if [ -z "$1" ]; then
  echo "Usage: $0 <number_of_iterations>"
  exit 1
fi

n=$1

cd measurements/data || { echo "Directory not found!"; exit 1; }

mkdir -p "runs_outputs"

timestamp=$(date +"%Y%m%d_%H%M%S")
output_dir="runs_outputs/runs_outputs_${timestamp}"
mkdir "$output_dir"

cd ../../target/benchbase-postgres || { echo "Directory not found!"; exit 1; }

for ((i=1; i<=n; i++))
do
  echo "Running iteration $i at $(date)..."
  java -cp benchbase.jar:lib/plb-0.0.1-SNAPSHOT.jar -Djava.util.logging.config.file=src/main/resources/logging.properties com.oltpbenchmark.DBWorkload -b tpch -c config/postgres/sample_tpch_config.xml --create=false --load=false --execute=true > "../../measurements/data/$output_dir/run_output_$i.txt"
done

echo "Completed $n iterations. Results saved in $output_dir."

