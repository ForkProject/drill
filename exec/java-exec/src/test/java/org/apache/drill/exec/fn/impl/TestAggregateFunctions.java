/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.fn.impl;

import org.apache.drill.BaseTestQuery;
import org.apache.drill.common.types.TypeProtos;
import org.junit.Ignore;
import org.junit.Test;

public class TestAggregateFunctions extends BaseTestQuery {

  /*
   * Test checks the count of a nullable column within a map
   * and verifies count is equal only to the number of times the
   * column appears and doesn't include the null count
   */
  @Test
  public void testCountOnNullableColumn() throws Exception {
    testBuilder()
        .sqlQuery("select count(t.x.y)  as cnt1, count(`integer`) as cnt2 from cp.`/jsoninput/input2.json` t")
        .ordered()
        .baselineColumns("cnt1", "cnt2")
        .baselineValues(3l, 4l)
        .build().run();
  }

  @Test
  public void testCountDistinctOnBoolColumn() throws Exception {
    testBuilder()
        .sqlQuery("select count(distinct `bool_val`) as cnt from `sys`.`options`")
        .ordered()
        .baselineColumns("cnt")
        .baselineValues(2l)
        .build().run();
  }

  @Test
  public void testMaxWithZeroInput() throws Exception {
    testBuilder()
        .sqlQuery("select max(employee_id * 0.0) as max_val from cp.`employee.json`")
        .unOrdered()
        .baselineColumns("max_val")
        .baselineValues(0.0d)
        .go();
  }

  @Ignore
  @Test // DRILL-2092: count distinct, non distinct aggregate with group-by
  public void testDrill2092() throws Exception {
    String query = "select a1, b1, count(distinct c1) as dist1, \n"
        + "sum(c1) as sum1, count(c1) as cnt1, count(*) as cnt \n"
        + "from cp.`agg/bugs/drill2092/input.json` \n"
        + "group by a1, b1 order by a1, b1";

    String baselineQuery =
        "select case when columns[0]='null' then cast(null as bigint) else cast(columns[0] as bigint) end as a1, \n"
        + "case when columns[1]='null' then cast(null as bigint) else cast(columns[1] as bigint) end as b1, \n"
        + "case when columns[2]='null' then cast(null as bigint) else cast(columns[2] as bigint) end as dist1, \n"
        + "case when columns[3]='null' then cast(null as bigint) else cast(columns[3] as bigint) end as sum1, \n"
        + "case when columns[4]='null' then cast(null as bigint) else cast(columns[4] as bigint) end as cnt1, \n"
        + "case when columns[5]='null' then cast(null as bigint) else cast(columns[5] as bigint) end as cnt \n"
        + "from cp.`agg/bugs/drill2092/result.tsv`";


    // NOTE: this type of query gets rewritten by Calcite into an inner join of subqueries, so
    // we need to test with both hash join and merge join

    testBuilder()
        .sqlQuery(query)
        .ordered()
        .optionSettingQueriesForTestQuery("alter system set `planner.enable_hashjoin` = true")
        .sqlBaselineQuery(baselineQuery)
        .build().run();

    testBuilder()
    .sqlQuery(query)
    .ordered()
    .optionSettingQueriesForTestQuery("alter system set `planner.enable_hashjoin` = false")
    .sqlBaselineQuery(baselineQuery)
    .build().run();

  }

  @Test // DRILL-2170: Subquery has group-by, order-by on aggregate function and limit
  public void testDrill2170() throws Exception {
    String query =
        "select count(*) as cnt from "
        + "cp.`tpch/orders.parquet` o inner join\n"
        + "(select l_orderkey, sum(l_quantity), sum(l_extendedprice) \n"
        + "from cp.`tpch/lineitem.parquet` \n"
        + "group by l_orderkey order by 3 limit 100) sq \n"
        + "on sq.l_orderkey = o.o_orderkey";

    testBuilder()
    .sqlQuery(query)
    .ordered()
    .optionSettingQueriesForTestQuery("alter system set `planner.slice_target` = 1000")
    .baselineColumns("cnt")
    .baselineValues(100l)
    .build().run();
  }

  @Test // DRILL-2168
  public void testGBExprWithDrillFunc() throws Exception {
    testBuilder()
        .ordered()
        .sqlQuery("select concat(n_name, cast(n_nationkey as varchar(10))) as name, count(*) as cnt " +
            "from cp.`tpch/nation.parquet` " +
            "group by concat(n_name, cast(n_nationkey as varchar(10))) " +
            "having concat(n_name, cast(n_nationkey as varchar(10))) > 'UNITED'" +
            "order by concat(n_name, cast(n_nationkey as varchar(10)))")
        .baselineColumns("name", "cnt")
        .baselineValues("UNITED KINGDOM23", 1L)
        .baselineValues("UNITED STATES24", 1L)
        .baselineValues("VIETNAM21", 1L)
        .build().run();
  }

  @Test //DRILL-2242
  public void testDRILLNestedGBWithSubsetKeys() throws Exception {
    String sql = " select count(*) as cnt from (select l_partkey from\n" +
        "   (select l_partkey, l_suppkey from cp.`tpch/lineitem.parquet`\n" +
        "      group by l_partkey, l_suppkey) \n" +
        "   group by l_partkey )";

    test("alter session set `planner.slice_target` = 1; alter session set `planner.enable_multiphase_agg` = false ;");

    testBuilder()
        .ordered()
        .sqlQuery(sql)
        .baselineColumns("cnt")
        .baselineValues(2000L)
        .build().run();

    test("alter session set `planner.slice_target` = 1; alter session set `planner.enable_multiphase_agg` = true ;");

    testBuilder()
        .ordered()
        .sqlQuery(sql)
        .baselineColumns("cnt")
        .baselineValues(2000L)
        .build().run();

    test("alter session set `planner.slice_target` = 100000");
  }

  @Test
  public void testAvgWithNullableScalarFunction() throws Exception {
    String query = " select avg(length(b1)) as col from cp.`jsoninput/nullable1.json`";
    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("col")
        .baselineValues(3.0d)
        .go();
  }

  @Test
  public void testCountWithAvg() throws Exception {
    testBuilder()
        .sqlQuery("select count(a) col1, avg(b) col2 from cp.`jsoninput/nullable3.json`")
        .unOrdered()
        .baselineColumns("col1", "col2")
        .baselineValues(2l, 3.0d)
        .go();

    testBuilder()
        .sqlQuery("select count(a) col1, avg(a) col2 from cp.`jsoninput/nullable3.json`")
        .unOrdered()
        .baselineColumns("col1", "col2")
        .baselineValues(2l, 1.0d)
        .go();
  }

  @Test
  public void testAvgOnKnownType() throws Exception {
    testBuilder()
        .sqlQuery("select avg(cast(employee_id as bigint)) as col from cp.`employee.json`")
        .unOrdered()
        .baselineColumns("col")
        .baselineValues(578.9982683982684d)
        .go();
  }

  @Test
  public void testStddevOnKnownType() throws Exception {
    testBuilder()
        .sqlQuery("select stddev_samp(cast(employee_id as int)) as col from cp.`employee.json`")
        .unOrdered()
        .baselineColumns("col")
        .baselineValues(333.56708470261117d)
        .go();
  }

  @Test // test aggregates when input is empty and data type is optional
  public void testAggregateWithEmptyInput() throws Exception {
    String query = "select " +
        "count(employee_id) col1, avg(employee_id) col2, sum(employee_id) col3 " +
        "from cp.`employee.json` where 1 = 0";

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("col1", "col2", "col3")
        .baselineValues(0l, null, null)
        .go();
  }

  @Test // test aggregates when input is empty and data type is required
  public void testAggregateWithEmptyRequiredInput() throws Exception {
    // test min function on required type
    String query = "select " +
        "min(bool_col) col1, min(int_col) col2, min(bigint_col) col3, min(float4_col) col4, min(float8_col) col5, " +
        "min(date_col) col6, min(time_col) col7, min(timestamp_col) col8, min(interval_year_col) col9, " +
        "min(varhcar_col) col10 " +
        "from cp.`parquet/alltypes_required.parquet` where 1 = 0";

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("col1", "col2", "col3", "col4", "col5", "col6", "col7", "col8", "col9", "col10")
        .baselineValues(null, null, null, null, null, null, null, null, null, null)
        .go();

    // test max function
    query = "select " +
        "max(int_col) col1, max(bigint_col) col2, max(float4_col) col3, max(float8_col) col4, " +
        "max(date_col) col5, max(time_col) col6, max(timestamp_col) col7, max(interval_year_col) col8, " +
        "max(varhcar_col) col9 " +
        "from cp.`parquet/alltypes_required.parquet` where 1 = 0";

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("col1", "col2", "col3", "col4", "col5", "col6", "col7", "col8", "col9")
        .baselineValues(null, null, null, null, null, null, null, null, null)
        .go();

    // test sum function
    query = "select " +
        "sum(int_col) col1, sum(bigint_col) col2, sum(float4_col) col3, sum(float8_col) col4, sum(interval_year_col) col5 " +
        "from cp.`employee.json` where 1 = 0";

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("col1", "col2", "col3", "col4", "col5")
        .baselineValues(null, null, null, null, null)
        .go();

    // test avg function
    query = "select " +
        "avg(int_col) col1, avg(bigint_col) col2, avg(float4_col) col3, avg(float8_col) col4, avg(interval_year_col) col5 " +
        "from cp.`employee.json` where 1 = 0";

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("col1", "col2", "col3", "col4", "col5")
        .baselineValues(null, null, null, null, null)
        .go();

    // test stddev function
    query = "select " +
        "stddev_pop(int_col) col1, stddev_pop(bigint_col) col2, stddev_pop(float4_col) col3, " +
        "stddev_pop(float8_col) col4, stddev_pop(interval_year_col) col5 " +
        "from cp.`employee.json` where 1 = 0";

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("col1", "col2", "col3", "col4", "col5")
        .baselineValues(null, null, null, null, null)
        .go();

  }
}
