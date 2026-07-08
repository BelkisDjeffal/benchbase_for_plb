# Query calibration pipeline

This pipeline runs a single-replica TPC-H Q1 calibration workload.

Run from the Ansible folder:

```bash
cd /root/benchbase/ansible/ansible2_3_query_calibration_q1
ansible-playbook -i inventory_monitor.yml playbook_query_calibration.yml
```

Measured outputs are written under:

```text
/root/benchbase/measurements/data/query_calibration_q1/
```

Each measured repetition writes BenchBase files in:

```text
benchbase_outputs/run_<id>/
```

The workload uses direct PostgreSQL on `source_host`, Q1 only, unlimited rate, and multiple terminal counts.
