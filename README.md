# BenchBase Extensions for PLB Experiments

This repository contains a research customization of [BenchBase](https://github.com/cmu-db/benchbase) used for the experimental evaluation of [PLB](https://github.com/BelkisDjeffal/plb).

The extensions support service-class-aware workloads, controlled arrivals and session lifecycles, worker/query outcome tracking, and additional measurements used for PLB experiments and simulation calibration.

The `ansible/` directory contains the deployment and experiment orchestration used for distributed PostgreSQL experiments.

Different branches preserve experiment-specific variants, including timeout handling, worker rejection, failure scenarios and calibration workloads.

## References

D. E. Difallah, A. Pavlo, C. Curino, and P. Cudré-Mauroux,
“OLTP-Bench: An Extensible Testbed for Benchmarking Relational Databases,”
PVLDB, vol. 7, no. 4, pp. 277–288, 2013.

See the original [BenchBase repository](https://github.com/cmu-db/benchbase) for documentation, licensing and upstream development.
