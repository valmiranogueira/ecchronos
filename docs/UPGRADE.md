# Upgrade to 3.x

## From versions 2.x

The REST interface has been significantly reworked.
Schedules and repairs are now split into two separate resources.
Config is now part of full Schedules.
Query parameters are used for filtering instead of path parameters.


| Old                                                                          | New                                                                                     | Description                                                                              |
|------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------|
| /repair-management/v1/status                                                 | /repair-management/v2/[repairs,schedules]                                               | Status has been split into `repairs` for on demand repairs and `schedules` for schedules |
| /repair-management/v1/status/ids                                             | /repair-management/v2/[repairs,schedules]/&lt;id&gt;                                    | Id can now be searched for on repairs or schedules specifically                          |
| /repair-management/v1/status/keyspaces/&lt;keyspace&gt;/tables/&lt;table&gt; | /repair-management/v2/[repairs,schedules]?keyspace=&lt;keyspace&gt;&table=&lt;table&gt; | `keyspace` and `table` are now query parameters                                          |
| /repair-management/v1/config                                                 | -                                                                                       | Config has been removed and is part of `schedules`                                       |
| /repair-management/v1/schedule/keyspaces/&lt;keyspace&gt;                    | /repair-management/v2/repairs?keyspace=&lt;keyspace&gt;&table=&lt;table&gt;             | Triggering can be done by using `POST` to `repairs` with query parameters                |

For more information about REST interface, refer to [REST documentation](REST.md).

# Upgrade to 2.x

## From 2.0.0

A new column has been added to the table `ecchronos.on_demand_repair_status`, this must be added before upgrading.

The command to add the column is shown below:
```
ALTER TABLE ecchronos.on_demand_repair_status ADD completed_time timestamp;
```

Note: Make sure that you create the column with the cql_type timestamp since its not possible to change cql_type on an existing column.

## From versions before 2.0.0

A new table has been introduced and must be present before upgrading.

The required table is shown below:
```
CREATE TABLE IF NOT EXISTS ecchronos.on_demand_repair_status (
    host_id uuid,
    job_id uuid,
    table_reference frozen<table_reference>,
    token_map_hash int,
    repaired_tokens frozen<set<frozen<token_range>>>,
    status text,
    completed_time timestamp,
    PRIMARY KEY(host_id, job_id))
    WITH default_time_to_live = 2592000
    AND gc_grace_seconds = 0;
```

An optional configuration parameter for remote routing has been introduced, the default value is true.

This can be configured in `conf/ecc.yml`:
```
cql:
  remoteRouting: false
```