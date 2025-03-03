#!/bin/bash

# Get the directory where the script is located
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Check if a directory argument is provided
if [ -z "$1" ]; then
    echo "Usage: $0 <directory>"
    exit 1
fi

# Set the input directory from the first argument
INPUT_DIR="$1"

# Check if the directory exists
if [ ! -d "$INPUT_DIR" ]; then
    echo "Error: Directory '$INPUT_DIR' does not exist."
    exit 1
fi

# Define the output CSV file in the same directory as the script
OUTPUT_FILE="$SCRIPT_DIR/summary_after_spawn.csv"

# Check if the CSV file exists
if [ ! -f "$OUTPUT_FILE" ]; then
    # If the file doesn't exist, write the header
    header="Experiment Number,Final State,Measured Requests,Isolation,Scale Factor,Terminals,Terminal Classes Weights,Throughput,Goodput,Average Latency,95th Percentile Latency,Maximum Latency,Median Latency,Minimum Latency,25th Percentile Latency,90th Percentile Latency,99th Percentile Latency,75th Percentile Latency,premium_Throughput,premium_Goodput,premium_PerTerminalThroughput,premium_PerTerminalGoodput,premium_LatencyStats,freemium_Throughput,freemium_Goodput,freemium_PerTerminalThroughput,freemium_PerTerminalGoodput,freemium_LatencyStats"
    echo "$header" > "$OUTPUT_FILE"
    experiment_number=1
else
    echo "Appending to existing CSV: $OUTPUT_FILE"
    # Count existing experiments (excluding header)
    existing_lines=$(tail -n +2 "$OUTPUT_FILE" | wc -l)
    experiment_number=$((existing_lines + 1))
fi

# Loop through each .summary.json file in the given directory
for file in "$INPUT_DIR"/*.summary.json; do
    if [[ -f "$file" ]]; then
        # Extract top-level fields using jq
        final_state=$(jq -r '."Final State"' "$file")
        measured_requests=$(jq -r '."Measured Requests"' "$file")
        isolation=$(jq -r '."isolation"' "$file")
        scalefactor=$(jq -r '."scalefactor"' "$file")
        terminals=$(jq -r '."terminals"' "$file")
        terminal_weights=$(jq -r '."terminalClassesWeights"' "$file")
        throughput=$(jq -r '."Throughput (requests/second)"' "$file")
        goodput=$(jq -r '."Goodput (requests/second)"' "$file")
        latency_avg=$(jq -r '."Latency Distribution"."Average Latency (microseconds)"' "$file")
        latency_95=$(jq -r '."Latency Distribution"."95th Percentile Latency (microseconds)"' "$file")
        latency_max=$(jq -r '."Latency Distribution"."Maximum Latency (microseconds)"' "$file")
        latency_median=$(jq -r '."Latency Distribution"."Median Latency (microseconds)"' "$file")
        latency_min=$(jq -r '."Latency Distribution"."Minimum Latency (microseconds)"' "$file")
        latency_25=$(jq -r '."Latency Distribution"."25th Percentile Latency (microseconds)"' "$file")
        latency_90=$(jq -r '."Latency Distribution"."90th Percentile Latency (microseconds)"' "$file")
        latency_99=$(jq -r '."Latency Distribution"."99th Percentile Latency (microseconds)"' "$file")
        latency_75=$(jq -r '."Latency Distribution"."75th Percentile Latency (microseconds)"' "$file")
        
        # Extract per-priority metrics using jq
        premium_throughput=$(jq -r '."Per-Priority Metrics"."premium"."Throughput (req/s)"' "$file")
        premium_goodput=$(jq -r '."Per-Priority Metrics"."premium"."Goodput (req/s)"' "$file")
        premium_per_terminal_throughput=$(jq -r '."Per-Priority Metrics"."premium"."Per-Terminal Throughput (req/s)"' "$file")
        premium_per_terminal_goodput=$(jq -r '."Per-Priority Metrics"."premium"."Per-Terminal Goodput (req/s)"' "$file")
        premium_latency=$(jq -r '."Per-Priority Metrics"."premium"."Latency Stats"' "$file" | sed 's/,/;/g')

        freemium_throughput=$(jq -r '."Per-Priority Metrics"."freemium"."Throughput (req/s)"' "$file")
        freemium_goodput=$(jq -r '."Per-Priority Metrics"."freemium"."Goodput (req/s)"' "$file")
        freemium_per_terminal_throughput=$(jq -r '."Per-Priority Metrics"."freemium"."Per-Terminal Throughput (req/s)"' "$file")
        freemium_per_terminal_goodput=$(jq -r '."Per-Priority Metrics"."freemium"."Per-Terminal Goodput (req/s)"' "$file")
        freemium_latency=$(jq -r '."Per-Priority Metrics"."freemium"."Latency Stats"' "$file" | sed 's/,/;/g')

        # Append the extracted data to the CSV file
        echo "$experiment_number,$final_state,$measured_requests,$isolation,$scalefactor,$terminals,$terminal_weights,$throughput,$goodput,$latency_avg,$latency_95,$latency_max,$latency_median,$latency_min,$latency_25,$latency_90,$latency_99,$latency_75,$premium_throughput,$premium_goodput,$premium_per_terminal_throughput,$premium_per_terminal_goodput,\"$premium_latency\",$freemium_throughput,$freemium_goodput,$freemium_per_terminal_throughput,$freemium_per_terminal_goodput,\"$freemium_latency\"" >> "$OUTPUT_FILE"
        
        # Increment experiment number
        ((experiment_number++))
    fi
done

# Print the number of processed files
echo "Processed $((experiment_number - 1)) files."
echo "Extraction completed. Data saved in $OUTPUT_FILE"
