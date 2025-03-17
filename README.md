# **Quick Guide**
For now, please work on the `spawn_poisson` branch instead of `master`. The master branch is not up to date, 

1. **Build BenchBase**:
   ```sh
   ./mvnw clean package -P postgres -DskipTests
   ```

2. **Extract the built package**:
   ```sh
   cd ./target && tar xvzf benchbase-postgres.tgz
   ```

3. **Run the Benchmark**:
   ```sh
   java -jar benchbase.jar -b tpch -c config/postgres/sample_tpch_config.xml --create=false --load=false --execute=true
   ```
