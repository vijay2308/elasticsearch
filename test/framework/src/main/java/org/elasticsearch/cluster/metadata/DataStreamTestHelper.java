/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.cluster.metadata;

import org.apache.lucene.util.LuceneTestCase;
import org.elasticsearch.Version;
import org.elasticsearch.action.admin.indices.rollover.MetadataRolloverService;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.CheckedFunction;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexMode;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.IndexSettingProvider;
import org.elasticsearch.index.IndexSettingProviders;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.mapper.DataStreamTimestampFieldMapper;
import org.elasticsearch.index.mapper.DateFieldMapper;
import org.elasticsearch.index.mapper.MapperBuilderContext;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.Mapping;
import org.elasticsearch.index.mapper.MappingLookup;
import org.elasticsearch.index.mapper.MappingParserContext;
import org.elasticsearch.index.mapper.MetadataFieldMapper;
import org.elasticsearch.index.mapper.RootObjectMapper;
import org.elasticsearch.index.shard.IndexEventListener;
import org.elasticsearch.indices.EmptySystemIndices;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.ShardLimitValidator;
import org.elasticsearch.script.ScriptCompiler;
import org.elasticsearch.test.ClusterServiceUtils;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

import static org.elasticsearch.cluster.metadata.DataStream.BACKING_INDEX_PREFIX;
import static org.elasticsearch.cluster.metadata.DataStream.DATE_FORMATTER;
import static org.elasticsearch.cluster.metadata.DataStream.getDefaultBackingIndexName;
import static org.elasticsearch.cluster.metadata.IndexMetadata.SETTING_INDEX_UUID;
import static org.elasticsearch.test.ESTestCase.generateRandomStringArray;
import static org.elasticsearch.test.ESTestCase.randomAlphaOfLength;
import static org.elasticsearch.test.ESTestCase.randomBoolean;
import static org.elasticsearch.test.ESTestCase.randomFrom;
import static org.elasticsearch.test.ESTestCase.randomMap;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class DataStreamTestHelper {

    private static final Version DATE_IN_BACKING_INDEX_VERSION = Version.V_7_11_0;
    private static final Settings.Builder SETTINGS = ESTestCase.settings(Version.CURRENT).put("index.hidden", true);
    private static final int NUMBER_OF_SHARDS = 1;
    private static final int NUMBER_OF_REPLICAS = 1;

    public static DataStream newInstance(String name, DataStream.TimestampField timeStampField, List<Index> indices) {
        return newInstance(name, timeStampField, indices, indices.size(), null);
    }

    public static DataStream newInstance(
        String name,
        DataStream.TimestampField timeStampField,
        List<Index> indices,
        long generation,
        Map<String, Object> metadata
    ) {
        return newInstance(name, timeStampField, indices, generation, metadata, false);
    }

    public static DataStream newInstance(
        String name,
        DataStream.TimestampField timeStampField,
        List<Index> indices,
        long generation,
        Map<String, Object> metadata,
        boolean replicated
    ) {
        return new DataStream(name, timeStampField, indices, generation, metadata, false, replicated, false, false, null);
    }

    public static String getLegacyDefaultBackingIndexName(
        String dataStreamName,
        long generation,
        long epochMillis,
        Version minNodeVersion
    ) {
        if (minNodeVersion.onOrAfter(DATE_IN_BACKING_INDEX_VERSION)) {
            return String.format(
                Locale.ROOT,
                BACKING_INDEX_PREFIX + "%s-%s-%06d",
                dataStreamName,
                DATE_FORMATTER.formatMillis(epochMillis),
                generation
            );
        } else {
            return getLegacyDefaultBackingIndexName(dataStreamName, generation);
        }
    }

    public static String getLegacyDefaultBackingIndexName(String dataStreamName, long generation) {
        return String.format(Locale.ROOT, BACKING_INDEX_PREFIX + "%s-%06d", dataStreamName, generation);
    }

    public static IndexMetadata.Builder createFirstBackingIndex(String dataStreamName) {
        return createBackingIndex(dataStreamName, 1, System.currentTimeMillis());
    }

    public static IndexMetadata.Builder createFirstBackingIndex(String dataStreamName, long epochMillis) {
        return createBackingIndex(dataStreamName, 1, epochMillis);
    }

    public static IndexMetadata.Builder createBackingIndex(String dataStreamName, int generation) {
        return createBackingIndex(dataStreamName, generation, System.currentTimeMillis());
    }

    public static IndexMetadata.Builder createBackingIndex(String dataStreamName, int generation, long epochMillis) {
        return IndexMetadata.builder(DataStream.getDefaultBackingIndexName(dataStreamName, generation, epochMillis))
            .settings(SETTINGS)
            .numberOfShards(NUMBER_OF_SHARDS)
            .numberOfReplicas(NUMBER_OF_REPLICAS);
    }

    public static IndexMetadata.Builder getIndexMetadataBuilderForIndex(Index index) {
        return IndexMetadata.builder(index.getName())
            .settings(Settings.builder().put(SETTINGS.build()).put(SETTING_INDEX_UUID, index.getUUID()))
            .numberOfShards(NUMBER_OF_SHARDS)
            .numberOfReplicas(NUMBER_OF_REPLICAS);
    }

    public static DataStream.TimestampField createTimestampField(String fieldName) {
        return new DataStream.TimestampField(fieldName);
    }

    public static String generateMapping(String timestampFieldName) {
        return """
            {
              "_doc":{
                "properties": {
                  "%s": {
                    "type": "date"
                  }
                }
              }
            }""".formatted(timestampFieldName);
    }

    public static String generateMapping(String timestampFieldName, String type) {
        return "{\n"
            + "      \""
            + DataStreamTimestampFieldMapper.NAME
            + "\": {\n"
            + "        \"enabled\": true\n"
            + "      },"
            + "      \"properties\": {\n"
            + "        \""
            + timestampFieldName
            + "\": {\n"
            + "          \"type\": \""
            + type
            + "\"\n"
            + "        }\n"
            + "      }\n"
            + "    }";
    }

    public static List<Index> randomIndexInstances() {
        int numIndices = ESTestCase.randomIntBetween(0, 128);
        List<Index> indices = new ArrayList<>(numIndices);
        for (int i = 0; i < numIndices; i++) {
            indices.add(new Index(randomAlphaOfLength(10).toLowerCase(Locale.ROOT), UUIDs.randomBase64UUID(LuceneTestCase.random())));
        }
        return indices;
    }

    public static DataStream randomInstance() {
        return randomInstance(System::currentTimeMillis);
    }

    public static DataStream randomInstance(String name) {
        return randomInstance(name, System::currentTimeMillis);
    }

    public static DataStream randomInstance(LongSupplier timeProvider) {
        String dataStreamName = randomAlphaOfLength(10).toLowerCase(Locale.ROOT);
        return randomInstance(dataStreamName, timeProvider);
    }

    public static DataStream randomInstance(String dataStreamName, LongSupplier timeProvider) {
        List<Index> indices = randomIndexInstances();
        long generation = indices.size() + ESTestCase.randomLongBetween(1, 128);
        indices.add(new Index(getDefaultBackingIndexName(dataStreamName, generation), UUIDs.randomBase64UUID(LuceneTestCase.random())));
        Map<String, Object> metadata = null;
        if (randomBoolean()) {
            metadata = Map.of("key", "value");
        }

        return new DataStream(
            dataStreamName,
            createTimestampField("@timestamp"),
            indices,
            generation,
            metadata,
            randomBoolean(),
            randomBoolean(),
            false, // Some tests don't work well with system data streams, since these data streams require special handling
            timeProvider,
            randomBoolean(),
            randomBoolean() ? IndexMode.STANDARD : null // IndexMode.TIME_SERIES triggers validation that many unit tests doesn't pass
        );
    }

    public static DataStreamAlias randomAliasInstance() {
        List<String> dataStreams = List.of(generateRandomStringArray(5, 5, false, false));
        return new DataStreamAlias(
            randomAlphaOfLength(5),
            dataStreams,
            randomBoolean() ? randomFrom(dataStreams) : null,
            randomBoolean() ? randomMap(1, 4, () -> new Tuple<>("term", Map.of("year", "2022"))) : null
        );
    }

    /**
     * Constructs {@code ClusterState} with the specified data streams and indices.
     *
     * @param dataStreams The names of the data streams to create with their respective number of backing indices
     * @param indexNames  The names of indices to create that do not back any data streams
     */
    public static ClusterState getClusterStateWithDataStreams(List<Tuple<String, Integer>> dataStreams, List<String> indexNames) {
        return getClusterStateWithDataStreams(dataStreams, indexNames, 1);
    }

    /**
     * Constructs {@code ClusterState} with the specified data streams and indices.
     *
     * @param dataStreams The names of the data streams to create with their respective number of backing indices
     * @param indexNames  The names of indices to create that do not back any data streams
     * @param replicas number of replicas
     */
    public static ClusterState getClusterStateWithDataStreams(
        List<Tuple<String, Integer>> dataStreams,
        List<String> indexNames,
        int replicas
    ) {
        return getClusterStateWithDataStreams(dataStreams, indexNames, System.currentTimeMillis(), Settings.EMPTY, replicas);
    }

    public static ClusterState getClusterStateWithDataStreams(
        List<Tuple<String, Integer>> dataStreams,
        List<String> indexNames,
        long currentTime,
        Settings settings,
        int replicas
    ) {
        return getClusterStateWithDataStreams(dataStreams, indexNames, currentTime, settings, replicas, false);
    }

    public static ClusterState getClusterStateWithDataStreams(
        List<Tuple<String, Integer>> dataStreams,
        List<String> indexNames,
        long currentTime,
        Settings settings,
        int replicas,
        boolean replicated
    ) {
        Metadata.Builder builder = Metadata.builder();

        List<IndexMetadata> allIndices = new ArrayList<>();
        for (Tuple<String, Integer> dsTuple : dataStreams) {
            List<IndexMetadata> backingIndices = new ArrayList<>();
            for (int backingIndexNumber = 1; backingIndexNumber <= dsTuple.v2(); backingIndexNumber++) {
                backingIndices.add(
                    createIndexMetadata(getDefaultBackingIndexName(dsTuple.v1(), backingIndexNumber, currentTime), true, settings, replicas)
                );
            }
            allIndices.addAll(backingIndices);

            DataStream ds = DataStreamTestHelper.newInstance(
                dsTuple.v1(),
                createTimestampField("@timestamp"),
                backingIndices.stream().map(IndexMetadata::getIndex).collect(Collectors.toList()),
                dsTuple.v2(),
                null,
                replicated
            );
            builder.put(ds);
        }

        for (String indexName : indexNames) {
            allIndices.add(createIndexMetadata(indexName, false, settings, replicas));
        }

        for (IndexMetadata index : allIndices) {
            builder.put(index, false);
        }

        return ClusterState.builder(new ClusterName("_name")).metadata(builder).build();
    }

    public static ClusterState getClusterStateWithDataStream(String dataStream, List<Tuple<Instant, Instant>> timeSlices) {
        Metadata.Builder builder = Metadata.builder();

        List<IndexMetadata> backingIndices = new ArrayList<>();
        int generation = 1;
        for (Tuple<Instant, Instant> tuple : timeSlices) {
            Instant start = tuple.v1();
            Instant end = tuple.v2();
            Settings settings = Settings.builder()
                .put("index.mode", "time_series")
                .put("index.routing_path", "uid")
                .put(IndexSettings.TIME_SERIES_START_TIME.getKey(), DateFieldMapper.DEFAULT_DATE_TIME_FORMATTER.format(start))
                .put(IndexSettings.TIME_SERIES_END_TIME.getKey(), DateFieldMapper.DEFAULT_DATE_TIME_FORMATTER.format(end))
                .build();
            var im = createIndexMetadata(getDefaultBackingIndexName(dataStream, generation, start.toEpochMilli()), true, settings, 0);
            builder.put(im, true);
            backingIndices.add(im);
            generation++;
        }
        DataStream ds = new DataStream(
            dataStream,
            createTimestampField("@timestamp"),
            backingIndices.stream().map(IndexMetadata::getIndex).collect(Collectors.toList()),
            backingIndices.size(),
            null,
            false,
            false,
            false,
            false,
            IndexMode.TIME_SERIES
        );
        builder.put(ds);

        return ClusterState.builder(new ClusterName("_name")).metadata(builder).build();
    }

    private static IndexMetadata createIndexMetadata(String name, boolean hidden, Settings settings, int replicas) {
        Settings.Builder b = Settings.builder()
            .put(settings)
            .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
            .put("index.hidden", hidden);

        return IndexMetadata.builder(name).settings(b).numberOfShards(1).numberOfReplicas(replicas).build();
    }

    public static String backingIndexPattern(String dataStreamName, long generation) {
        return String.format(Locale.ROOT, "\\.ds-%s-(\\d{4}\\.\\d{2}\\.\\d{2}-)?%06d", dataStreamName, generation);
    }

    public static Matcher<String> backingIndexEqualTo(String dataStreamName, int generation) {
        return new TypeSafeMatcher<>() {

            @Override
            protected boolean matchesSafely(String backingIndexName) {
                if (backingIndexName == null) {
                    return false;
                }

                int indexOfLastDash = backingIndexName.lastIndexOf('-');
                String actualDataStreamName = parseDataStreamName(backingIndexName, indexOfLastDash);
                int actualGeneration = parseGeneration(backingIndexName, indexOfLastDash);
                return actualDataStreamName.equals(dataStreamName) && actualGeneration == generation;
            }

            @Override
            protected void describeMismatchSafely(String backingIndexName, Description mismatchDescription) {
                int indexOfLastDash = backingIndexName.lastIndexOf('-');
                String dataStreamName = parseDataStreamName(backingIndexName, indexOfLastDash);
                int generation = parseGeneration(backingIndexName, indexOfLastDash);
                mismatchDescription.appendText(" was data stream name ")
                    .appendValue(dataStreamName)
                    .appendText(" and generation ")
                    .appendValue(generation);
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("expected data stream name ")
                    .appendValue(dataStreamName)
                    .appendText(" and expected generation ")
                    .appendValue(generation);
            }

            private String parseDataStreamName(String backingIndexName, int indexOfLastDash) {
                return backingIndexName.substring(4, backingIndexName.lastIndexOf('-', indexOfLastDash - 1));
            }

            private int parseGeneration(String backingIndexName, int indexOfLastDash) {
                return Integer.parseInt(backingIndexName.substring(indexOfLastDash + 1));
            }
        };
    }

    public static MetadataRolloverService getMetadataRolloverService(
        DataStream dataStream,
        ThreadPool testThreadPool,
        Set<IndexSettingProvider> providers,
        NamedXContentRegistry registry
    ) throws Exception {
        DateFieldMapper dateFieldMapper = new DateFieldMapper.Builder(
            "@timestamp",
            DateFieldMapper.Resolution.MILLISECONDS,
            null,
            ScriptCompiler.NONE,
            false,
            Version.CURRENT
        ).build(MapperBuilderContext.ROOT);
        ClusterService clusterService = ClusterServiceUtils.createClusterService(testThreadPool);
        Environment env = mock(Environment.class);
        when(env.sharedDataFile()).thenReturn(null);
        AllocationService allocationService = mock(AllocationService.class);
        when(allocationService.reroute(any(ClusterState.class), any(String.class))).then(i -> i.getArguments()[0]);
        MappingLookup mappingLookup = null;
        if (dataStream != null) {
            RootObjectMapper.Builder root = new RootObjectMapper.Builder("_doc");
            root.add(
                new DateFieldMapper.Builder(
                    dataStream.getTimeStampField().getName(),
                    DateFieldMapper.Resolution.MILLISECONDS,
                    DateFieldMapper.DEFAULT_DATE_TIME_FORMATTER,
                    ScriptCompiler.NONE,
                    true,
                    Version.CURRENT
                )
            );
            MetadataFieldMapper dtfm = getDataStreamTimestampFieldMapper();
            Mapping mapping = new Mapping(
                root.build(MapperBuilderContext.ROOT),
                new MetadataFieldMapper[] { dtfm },
                Collections.emptyMap()
            );
            mappingLookup = MappingLookup.fromMappers(mapping, List.of(dtfm, dateFieldMapper), List.of(), List.of());
        }
        IndicesService indicesService = mockIndicesServices(mappingLookup);

        ShardLimitValidator shardLimitValidator = new ShardLimitValidator(Settings.EMPTY, clusterService);
        MetadataCreateIndexService createIndexService = new MetadataCreateIndexService(
            Settings.EMPTY,
            clusterService,
            indicesService,
            allocationService,
            shardLimitValidator,
            env,
            IndexScopedSettings.DEFAULT_SCOPED_SETTINGS,
            testThreadPool,
            null,
            EmptySystemIndices.INSTANCE,
            false,
            new IndexSettingProviders(providers)
        );
        MetadataIndexAliasesService indexAliasesService = new MetadataIndexAliasesService(clusterService, indicesService, null, registry);
        return new MetadataRolloverService(testThreadPool, createIndexService, indexAliasesService, EmptySystemIndices.INSTANCE);
    }

    private static MetadataFieldMapper getDataStreamTimestampFieldMapper() {
        Map<String, Object> fieldsMapping = new HashMap<>();
        fieldsMapping.put("type", DataStreamTimestampFieldMapper.NAME);
        fieldsMapping.put("enabled", true);
        MappingParserContext mockedParserContext = mock(MappingParserContext.class);
        return DataStreamTimestampFieldMapper.PARSER.parse("field", fieldsMapping, mockedParserContext).build();
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static IndicesService mockIndicesServices(MappingLookup mappingLookup) throws Exception {
        /*
         * Throws Exception because Eclipse uses the lower bound for
         * CheckedFunction's exception type so it thinks the "when" call
         * can throw Exception. javac seems to be ok inferring something
         * else.
         */
        IndicesService indicesService = mock(IndicesService.class);
        when(indicesService.withTempIndexService(any(IndexMetadata.class), any(CheckedFunction.class))).then(invocationOnMock -> {
            IndexService indexService = mock(IndexService.class);
            IndexMetadata indexMetadata = (IndexMetadata) invocationOnMock.getArguments()[0];
            when(indexService.index()).thenReturn(indexMetadata.getIndex());
            MapperService mapperService = mock(MapperService.class);
            when(indexService.mapperService()).thenReturn(mapperService);
            when(mapperService.mappingLookup()).thenReturn(mappingLookup);
            when(indexService.getIndexEventListener()).thenReturn(new IndexEventListener() {
            });
            when(indexService.getIndexSortSupplier()).thenReturn(() -> null);
            return ((CheckedFunction<IndexService, ?, ?>) invocationOnMock.getArguments()[1]).apply(indexService);
        });
        return indicesService;
    }

}
