/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.druid.query;

import com.fasterxml.jackson.databind.Module;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import io.druid.data.input.Row;
import io.druid.data.input.impl.CSVParseSpec;
import io.druid.data.input.impl.DimensionsSpec;
import io.druid.data.input.impl.StringInputRowParser;
import io.druid.data.input.impl.TimestampSpec;
import io.druid.java.util.common.DateTimes;
import io.druid.java.util.common.granularity.Granularities;
import io.druid.java.util.common.guava.Sequence;
import io.druid.query.aggregation.AggregationTestHelper;
import io.druid.query.aggregation.CountAggregatorFactory;
import io.druid.query.dimension.DefaultDimensionSpec;
import io.druid.query.dimension.DimensionSpec;
import io.druid.query.dimension.ListFilteredDimensionSpec;
import io.druid.query.dimension.RegexFilteredDimensionSpec;
import io.druid.query.filter.SelectorDimFilter;
import io.druid.query.groupby.GroupByQuery;
import io.druid.query.groupby.GroupByQueryConfig;
import io.druid.query.groupby.GroupByQueryRunnerTest;
import io.druid.query.groupby.GroupByQueryRunnerTestHelper;
import io.druid.query.spec.LegacySegmentSpec;
import io.druid.query.topn.TopNQuery;
import io.druid.query.topn.TopNQueryBuilder;
import io.druid.query.topn.TopNQueryConfig;
import io.druid.query.topn.TopNQueryQueryToolChest;
import io.druid.query.topn.TopNQueryRunnerFactory;
import io.druid.query.topn.TopNResultValue;
import io.druid.segment.IncrementalIndexSegment;
import io.druid.segment.IndexSpec;
import io.druid.segment.QueryableIndex;
import io.druid.segment.QueryableIndexSegment;
import io.druid.segment.Segment;
import io.druid.segment.TestHelper;
import io.druid.segment.incremental.IncrementalIndex;
import io.druid.segment.writeout.OffHeapMemorySegmentWriteOutMediumFactory;
import io.druid.segment.writeout.SegmentWriteOutMediumFactory;
import io.druid.segment.writeout.TmpFileSegmentWriteOutMediumFactory;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 */
@RunWith(Parameterized.class)
public class MultiValuedDimensionTest
{
  @Parameterized.Parameters(name = "{0}")
  public static Collection<?> constructorFeeder()
  {
    final List<Object[]> constructors = Lists.newArrayList();
    for (GroupByQueryConfig config : GroupByQueryRunnerTest.testConfigs()) {
      constructors.add(new Object[]{config, TmpFileSegmentWriteOutMediumFactory.instance()});
      constructors.add(new Object[]{config, OffHeapMemorySegmentWriteOutMediumFactory.instance()});
    }
    return constructors;
  }

  private final AggregationTestHelper helper;
  private final SegmentWriteOutMediumFactory segmentWriteOutMediumFactory;

  private IncrementalIndex incrementalIndex;
  private QueryableIndex queryableIndex;

  private File persistedSegmentDir;

  public MultiValuedDimensionTest(final GroupByQueryConfig config, SegmentWriteOutMediumFactory segmentWriteOutMediumFactory)
  {
    helper = AggregationTestHelper.createGroupByQueryAggregationTestHelper(
        ImmutableList.<Module>of(),
        config,
        null
    );
    this.segmentWriteOutMediumFactory = segmentWriteOutMediumFactory;
  }

  @Before
  public void setup() throws Exception
  {
    incrementalIndex = new IncrementalIndex.Builder()
        .setSimpleTestingIndexSchema(new CountAggregatorFactory("count"))
        .setMaxRowCount(5000)
        .buildOnheap();

    StringInputRowParser parser = new StringInputRowParser(
        new CSVParseSpec(
            new TimestampSpec("timestamp", "iso", null),
            new DimensionsSpec(DimensionsSpec.getDefaultSchemas(ImmutableList.of("product", "tags")), null, null),
            "\t",
            ImmutableList.of("timestamp", "product", "tags"),
            false,
            0
        ),
        "UTF-8"
    );

    String[] rows = new String[]{
        "2011-01-12T00:00:00.000Z,product_1,t1\tt2\tt3",
        "2011-01-13T00:00:00.000Z,product_2,t3\tt4\tt5",
        "2011-01-14T00:00:00.000Z,product_3,t5\tt6\tt7",
        "2011-01-14T00:00:00.000Z,product_4"
    };

    for (String row : rows) {
      incrementalIndex.add(parser.parse(row));
    }

    persistedSegmentDir = Files.createTempDir();
    TestHelper.getTestIndexMergerV9(segmentWriteOutMediumFactory)
              .persist(incrementalIndex, persistedSegmentDir, new IndexSpec(), null);

    queryableIndex = TestHelper.getTestIndexIO(segmentWriteOutMediumFactory).loadIndex(persistedSegmentDir);
  }

  @Test
  public void testGroupByNoFilter()
  {
    GroupByQuery query = GroupByQuery
        .builder()
        .setDataSource("xx")
        .setQuerySegmentSpec(new LegacySegmentSpec("1970/3000"))
        .setGranularity(Granularities.ALL)
        .setDimensions(Lists.<DimensionSpec>newArrayList(new DefaultDimensionSpec("tags", "tags")))
        .setAggregatorSpecs(Collections.singletonList(new CountAggregatorFactory("count")))
        .build();

    Sequence<Row> result = helper.runQueryOnSegmentsObjs(
        ImmutableList.<Segment>of(
            new QueryableIndexSegment("sid1", queryableIndex),
            new IncrementalIndexSegment(incrementalIndex, "sid2")
        ),
        query
    );

    List<Row> expectedResults = Arrays.asList(
        GroupByQueryRunnerTestHelper.createExpectedRow("1970-01-01T00:00:00.000Z", "tags", null, "count", 2L),
        GroupByQueryRunnerTestHelper.createExpectedRow("1970-01-01T00:00:00.000Z", "tags", "t1", "count", 2L),
        GroupByQueryRunnerTestHelper.createExpectedRow("1970-01-01T00:00:00.000Z", "tags", "t2", "count", 2L),
        GroupByQueryRunnerTestHelper.createExpectedRow("1970-01-01T00:00:00.000Z", "tags", "t3", "count", 4L),
        GroupByQueryRunnerTestHelper.createExpectedRow("1970-01-01T00:00:00.000Z", "tags", "t4", "count", 2L),
        GroupByQueryRunnerTestHelper.createExpectedRow("1970-01-01T00:00:00.000Z", "tags", "t5", "count", 4L),
        GroupByQueryRunnerTestHelper.createExpectedRow("1970-01-01T00:00:00.000Z", "tags", "t6", "count", 2L),
        GroupByQueryRunnerTestHelper.createExpectedRow("1970-01-01T00:00:00.000Z", "tags", "t7", "count", 2L)
    );

    TestHelper.assertExpectedObjects(expectedResults, result.toList(), "");
  }

  @Test
  public void testGroupByWithDimFilter()
  {
    GroupByQuery query = GroupByQuery
        .builder()
        .setDataSource("xx")
        .setQuerySegmentSpec(new LegacySegmentSpec("1970/3000"))
        .setGranularity(Granularities.ALL)
        .setDimensions(Lists.<DimensionSpec>newArrayList(new DefaultDimensionSpec("tags", "tags")))
        .setAggregatorSpecs(Collections.singletonList(new CountAggregatorFactory("count")))
        .setDimFilter(new SelectorDimFilter("tags", "t3", null))
        .build();

    Sequence<Row> result = helper.runQueryOnSegmentsObjs(
        ImmutableList.<Segment>of(
            new QueryableIndexSegment("sid1", queryableIndex),
            new IncrementalIndexSegment(incrementalIndex, "sid2")
        ),
        query
    );

    List<Row> expectedResults = Arrays.asList(
        GroupByQueryRunnerTestHelper.createExpectedRow("1970-01-01T00:00:00.000Z", "tags", "t1", "count", 2L),
        GroupByQueryRunnerTestHelper.createExpectedRow("1970-01-01T00:00:00.000Z", "tags", "t2", "count", 2L),
        GroupByQueryRunnerTestHelper.createExpectedRow("1970-01-01T00:00:00.000Z", "tags", "t3", "count", 4L),
        GroupByQueryRunnerTestHelper.createExpectedRow("1970-01-01T00:00:00.000Z", "tags", "t4", "count", 2L),
        GroupByQueryRunnerTestHelper.createExpectedRow("1970-01-01T00:00:00.000Z", "tags", "t5", "count", 2L)
    );

    TestHelper.assertExpectedObjects(expectedResults, result.toList(), "");
  }

  @Test
  public void testGroupByWithDimFilterAndWithFilteredDimSpec()
  {
    GroupByQuery query = GroupByQuery
        .builder()
        .setDataSource("xx")
        .setQuerySegmentSpec(new LegacySegmentSpec("1970/3000"))
        .setGranularity(Granularities.ALL)
        .setDimensions(
            Lists.<DimensionSpec>newArrayList(
                new RegexFilteredDimensionSpec(
                    new DefaultDimensionSpec("tags", "tags"),
                    "t3"
                )
            )
        )
        .setAggregatorSpecs(Collections.singletonList(new CountAggregatorFactory("count")))
        .setDimFilter(new SelectorDimFilter("tags", "t3", null))
        .build();

    Sequence<Row> result = helper.runQueryOnSegmentsObjs(
        ImmutableList.<Segment>of(
            new QueryableIndexSegment("sid1", queryableIndex),
            new IncrementalIndexSegment(incrementalIndex, "sid2")
        ),
        query
    );

    List<Row> expectedResults = Arrays.asList(
        GroupByQueryRunnerTestHelper.createExpectedRow("1970-01-01T00:00:00.000Z", "tags", "t3", "count", 4L)
    );

    TestHelper.assertExpectedObjects(expectedResults, result.toList(), "");
  }

  @Test
  public void testTopNWithDimFilterAndWithFilteredDimSpec()
  {
    TopNQuery query = new TopNQueryBuilder()
        .dataSource("xx")
        .granularity(Granularities.ALL)
        .dimension(new ListFilteredDimensionSpec(
            new DefaultDimensionSpec("tags", "tags"),
            ImmutableSet.of("t3"),
            null
        ))
        .metric("count")
        .intervals(QueryRunnerTestHelper.fullOnInterval)
        .aggregators(Collections.singletonList(new CountAggregatorFactory("count")))
        .threshold(5)
        .filters(new SelectorDimFilter("tags", "t3", null)).build();

    QueryRunnerFactory factory = new TopNQueryRunnerFactory(
        TestQueryRunners.getPool(),
        new TopNQueryQueryToolChest(
            new TopNQueryConfig(),
            QueryRunnerTestHelper.NoopIntervalChunkingQueryRunnerDecorator()
        ),
        QueryRunnerTestHelper.NOOP_QUERYWATCHER
    );
    QueryRunner<Result<TopNResultValue>> runner = QueryRunnerTestHelper.makeQueryRunner(
        factory,
        new QueryableIndexSegment("sid1", queryableIndex),
        null
    );
    Map<String, Object> context = Maps.newHashMap();
    Sequence<Result<TopNResultValue>> result = runner.run(QueryPlus.wrap(query), context);
    List<Result<TopNResultValue>> expectedResults = Arrays.asList(
        new Result<TopNResultValue>(
            DateTimes.of("2011-01-12T00:00:00.000Z"),
            new TopNResultValue(
                Arrays.<Map<String, Object>>asList(
                    ImmutableMap.<String, Object>of(
                        "tags", "t3",
                        "count", 2L
                    )
                )
            )
        )
    );
    TestHelper.assertExpectedObjects(expectedResults, result.toList(), "");
  }

  @After
  public void cleanup() throws Exception
  {
    queryableIndex.close();
    incrementalIndex.close();
    FileUtils.deleteDirectory(persistedSegmentDir);
  }
}
