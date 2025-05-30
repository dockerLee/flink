/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.formats.json;

import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.serialization.SerializationSchema;
import org.apache.flink.formats.common.TimestampFormat;
import org.apache.flink.table.api.ValidationException;
import org.apache.flink.table.connector.sink.DynamicTableSink;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.factories.TestDynamicTableFactory;
import org.apache.flink.table.factories.utils.FactoryMocks;
import org.apache.flink.table.runtime.connector.sink.SinkRuntimeProviderContext;
import org.apache.flink.table.runtime.connector.source.ScanRuntimeProviderContext;
import org.apache.flink.table.runtime.typeutils.InternalTypeInfo;

import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.apache.flink.core.testutils.FlinkAssertions.anyCauseMatches;
import static org.apache.flink.table.factories.utils.FactoryMocks.PHYSICAL_DATA_TYPE;
import static org.apache.flink.table.factories.utils.FactoryMocks.PHYSICAL_TYPE;
import static org.apache.flink.table.factories.utils.FactoryMocks.SCHEMA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for the {@link JsonFormatFactory}. */
class JsonFormatFactoryTest {

    @Test
    void testSeDeSchema() {
        final Map<String, String> tableOptions = getAllOptions();

        testSchemaSerializationSchema(tableOptions);
        testSchemaDeserializationSchema(tableOptions);
    }

    @Test
    void testFailOnMissingField() {
        final Map<String, String> tableOptions =
                getModifyOptions(options -> options.put("json.fail-on-missing-field", "true"));

        assertThatCreateRuntimeDecoder(tableOptions)
                .satisfies(
                        anyCauseMatches(
                                ValidationException.class,
                                "fail-on-missing-field and ignore-parse-errors shouldn't both be true."));
    }

    @Test
    void testInvalidOptionForIgnoreParseErrors() {
        final Map<String, String> tableOptions =
                getModifyOptions(options -> options.put("json.ignore-parse-errors", "abc"));

        assertThatCreateRuntimeDecoder(tableOptions)
                .satisfies(
                        anyCauseMatches(
                                IllegalArgumentException.class,
                                "Unrecognized option for boolean: abc. Expected either true or false(case insensitive)"));
    }

    @Test
    void testInvalidOptionForTimestampFormat() {
        final Map<String, String> tableOptions =
                getModifyOptions(options -> options.put("json.timestamp-format.standard", "test"));

        assertThatCreateRuntimeDecoder(tableOptions)
                .satisfies(
                        anyCauseMatches(
                                ValidationException.class,
                                "Unsupported value 'test' for timestamp-format.standard. Supported values are [SQL, ISO-8601]."));
    }

    @Test
    void testLowerCaseOptionForTimestampFormat() {
        final Map<String, String> tableOptions =
                getModifyOptions(
                        options -> options.put("json.timestamp-format.standard", "iso-8601"));

        assertThatCreateRuntimeDecoder(tableOptions)
                .satisfies(
                        anyCauseMatches(
                                ValidationException.class,
                                "Unsupported value 'iso-8601' for timestamp-format.standard. Supported values are [SQL, ISO-8601]."));
    }

    @Test
    void testInvalidOptionForMapNullKeyMode() {
        final Map<String, String> tableOptions =
                getModifyOptions(options -> options.put("json.map-null-key.mode", "invalid"));

        assertThatCreateRuntimeEncoder(tableOptions)
                .satisfies(
                        anyCauseMatches(
                                ValidationException.class,
                                "Unsupported value 'invalid' for option map-null-key.mode. Supported values are [LITERAL, FAIL, DROP]."));
    }

    @Test
    void testLowerCaseOptionForMapNullKeyMode() {
        final Map<String, String> tableOptions =
                getModifyOptions(options -> options.put("json.map-null-key.mode", "fail"));

        testSchemaDeserializationSchema(tableOptions);
    }

    @Test
    void testDecodeJsonParseEnabled() {
        testJsonParserConfiguration(true, JsonParserRowDataDeserializationSchema.class);

        testJsonParserConfiguration(false, JsonRowDataDeserializationSchema.class);
    }

    // ------------------------------------------------------------------------
    //  Utilities
    // ------------------------------------------------------------------------

    private AbstractThrowableAssert<?, ? extends Throwable> assertThatCreateRuntimeDecoder(
            Map<String, String> options) {
        return assertThatThrownBy(
                () ->
                        createTableSource(options)
                                .valueFormat
                                .createRuntimeDecoder(
                                        ScanRuntimeProviderContext.INSTANCE,
                                        SCHEMA.toPhysicalRowDataType()));
    }

    private AbstractThrowableAssert<?, ? extends Throwable> assertThatCreateRuntimeEncoder(
            Map<String, String> options) {
        return assertThatThrownBy(
                () ->
                        createTableSink(options)
                                .valueFormat
                                .createRuntimeEncoder(
                                        new SinkRuntimeProviderContext(false), PHYSICAL_DATA_TYPE));
    }

    private void testSchemaDeserializationSchema(Map<String, String> options) {
        final JsonParserRowDataDeserializationSchema expectedDeser =
                new JsonParserRowDataDeserializationSchema(
                        PHYSICAL_TYPE,
                        InternalTypeInfo.of(PHYSICAL_TYPE),
                        false,
                        true,
                        TimestampFormat.ISO_8601);

        DeserializationSchema<RowData> actualDeser =
                createTableSource(options)
                        .valueFormat
                        .createRuntimeDecoder(
                                ScanRuntimeProviderContext.INSTANCE,
                                SCHEMA.toPhysicalRowDataType());

        assertThat(actualDeser).isEqualTo(expectedDeser);
    }

    private void testSchemaSerializationSchema(Map<String, String> options) {
        final JsonRowDataSerializationSchema expectedSer =
                new JsonRowDataSerializationSchema(
                        PHYSICAL_TYPE,
                        TimestampFormat.ISO_8601,
                        JsonFormatOptions.MapNullKeyMode.LITERAL,
                        "null",
                        true,
                        true);

        SerializationSchema<RowData> actualSer =
                createTableSink(options)
                        .valueFormat
                        .createRuntimeEncoder(
                                new SinkRuntimeProviderContext(false), PHYSICAL_DATA_TYPE);

        assertThat(actualSer).isEqualTo(expectedSer);
    }

    private TestDynamicTableFactory.DynamicTableSinkMock createTableSink(
            Map<String, String> options) {
        final DynamicTableSink actualSink = FactoryMocks.createTableSink(SCHEMA, options);
        assertThat(actualSink).isInstanceOf(TestDynamicTableFactory.DynamicTableSinkMock.class);

        return (TestDynamicTableFactory.DynamicTableSinkMock) actualSink;
    }

    private TestDynamicTableFactory.DynamicTableSourceMock createTableSource(
            Map<String, String> options) {
        final DynamicTableSource actualSource = FactoryMocks.createTableSource(SCHEMA, options);
        assertThat(actualSource).isInstanceOf(TestDynamicTableFactory.DynamicTableSourceMock.class);

        return (TestDynamicTableFactory.DynamicTableSourceMock) actualSource;
    }

    /**
     * Returns the full options modified by the given consumer {@code optionModifier}.
     *
     * @param optionModifier Consumer to modify the options
     */
    private Map<String, String> getModifyOptions(Consumer<Map<String, String>> optionModifier) {
        Map<String, String> options = getAllOptions();
        optionModifier.accept(options);
        return options;
    }

    private Map<String, String> getAllOptions() {
        final Map<String, String> options = new HashMap<>();
        options.put("connector", TestDynamicTableFactory.IDENTIFIER);
        options.put("target", "MyTarget");
        options.put("buffer-size", "1000");

        options.put("format", JsonFormatFactory.IDENTIFIER);
        options.put("json.fail-on-missing-field", "false");
        options.put("json.ignore-parse-errors", "true");
        options.put("json.timestamp-format.standard", "ISO-8601");
        options.put("json.map-null-key.mode", "LITERAL");
        options.put("json.map-null-key.literal", "null");
        options.put("json.encode.decimal-as-plain-number", "true");
        options.put("json.encode.ignore-null-fields", "true");
        options.put("json.decode.json-parser.enabled", "true");
        return options;
    }

    private void testJsonParserConfiguration(boolean enabled, Class<?> expectedClass) {
        Map<String, String> options =
                getModifyOptions(
                        opt -> opt.put("json.decode.json-parser.enabled", String.valueOf(enabled)));

        DeserializationSchema<RowData> actualDeser =
                createTableSource(options)
                        .valueFormat
                        .createRuntimeDecoder(
                                ScanRuntimeProviderContext.INSTANCE,
                                SCHEMA.toPhysicalRowDataType());

        DeserializationSchema<RowData> expectedDeser =
                enabled
                        ? new JsonParserRowDataDeserializationSchema(
                                PHYSICAL_TYPE,
                                InternalTypeInfo.of(PHYSICAL_TYPE),
                                false,
                                true,
                                TimestampFormat.ISO_8601)
                        : new JsonRowDataDeserializationSchema(
                                PHYSICAL_TYPE,
                                InternalTypeInfo.of(PHYSICAL_TYPE),
                                false,
                                true,
                                TimestampFormat.ISO_8601);

        assertThat(actualDeser).isInstanceOf(expectedClass);
        assertThat(actualDeser).isEqualTo(expectedDeser);
    }
}
