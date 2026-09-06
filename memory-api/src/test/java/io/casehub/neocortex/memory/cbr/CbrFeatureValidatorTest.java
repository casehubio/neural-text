package io.casehub.neocortex.memory.cbr;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.casehub.neocortex.memory.cbr.FeatureValue.number;
import static io.casehub.neocortex.memory.cbr.FeatureValue.numberList;
import static io.casehub.neocortex.memory.cbr.FeatureValue.range;
import static io.casehub.neocortex.memory.cbr.FeatureValue.string;
import static io.casehub.neocortex.memory.cbr.FeatureValue.stringList;
import static io.casehub.neocortex.memory.cbr.FeatureValue.struct;
import static io.casehub.neocortex.memory.cbr.FeatureValue.structList;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CbrFeatureValidatorTest {

    private static final CbrFeatureSchema SCHEMA = CbrFeatureSchema.of("test",
        FeatureField.categorical("posture"),
        FeatureField.numeric("score", 0, 100),
        FeatureField.text("description"),
        FeatureField.categoricalList("phases"),
        FeatureField.nestedObject("economy",
            FeatureField.numeric("minute_3", 0, 100),
            FeatureField.categorical("tier")),
        FeatureField.objectList("moments",
            FeatureField.categorical("type"),
            FeatureField.numeric("minute", 0, 90)));

    @Test void validateStoreFeatures_validFlatFeatures() {
        CbrFeatureValidator.validateStoreFeatures(
            Map.of("posture", string("AGGRESSIVE"), "score", number(85)), SCHEMA);
    }

    @Test void validateStoreFeatures_validCategoricalList() {
        CbrFeatureValidator.validateStoreFeatures(
            Map.of("phases", stringList("EARLY", "MID")), SCHEMA);
    }

    @Test void validateStoreFeatures_categoricalListRequiresStringListVal() {
        assertThatThrownBy(() -> CbrFeatureValidator.validateStoreFeatures(
            Map.of("phases", string("not_a_list")), SCHEMA))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void validateStoreFeatures_categoricalListRejectsNumberListVal() {
        assertThatThrownBy(() -> CbrFeatureValidator.validateStoreFeatures(
            Map.of("phases", numberList(1.0, 2.0, 3.0)), SCHEMA))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void validateStoreFeatures_validNestedObject() {
        CbrFeatureValidator.validateStoreFeatures(
            Map.of("economy", struct(Map.of("minute_3", number(45), "tier", string("gold")))), SCHEMA);
    }

    @Test void validateStoreFeatures_nestedObjectRequiresStructVal() {
        assertThatThrownBy(() -> CbrFeatureValidator.validateStoreFeatures(
            Map.of("economy", string("not_a_map")), SCHEMA))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @SuppressWarnings("unchecked")
    @Test void validateStoreFeatures_validObjectList() {
        CbrFeatureValidator.validateStoreFeatures(
            Map.of("moments", structList(
                Map.of("type", string("FIRST_CONTACT"), "minute", number(3.2)),
                Map.of("type", string("BATTLE_WON"), "minute", number(5.1)))), SCHEMA);
    }

    @Test void validateStoreFeatures_objectListRequiresStructListVal() {
        assertThatThrownBy(() -> CbrFeatureValidator.validateStoreFeatures(
            Map.of("moments", stringList("not", "maps")), SCHEMA))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void validateStoreFeatures_unknownFieldsIgnored() {
        CbrFeatureValidator.validateStoreFeatures(
            Map.of("unknown_field", string("any_value")), SCHEMA);
    }

    @Test
    void validateQueryFeatures_acceptsCategoricalListFeature() {
        CbrFeatureValidator.validateQueryFeatures(
                Map.of("phases", stringList("A", "B")), SCHEMA);
    }

    @Test
    void validateQueryFeatures_categoricalListRejectsWrongType() {
        assertThatThrownBy(() -> CbrFeatureValidator.validateQueryFeatures(
                Map.of("phases", string("not_a_list")), SCHEMA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CategoricalList")
                .hasMessageContaining("StringListVal");
    }

    @Test
    void validateQueryFeatures_acceptsNestedObjectFeature() {
        CbrFeatureValidator.validateQueryFeatures(
                Map.of("economy", struct(Map.of("minute_3", number(50), "tier", string("HIGH")))), SCHEMA);
    }

    @Test
    void validateQueryFeatures_nestedObjectRejectsWrongType() {
        assertThatThrownBy(() -> CbrFeatureValidator.validateQueryFeatures(
                Map.of("economy", string("not_a_struct")), SCHEMA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NestedObject")
                .hasMessageContaining("StructVal");
    }

    @SuppressWarnings("unchecked")
    @Test
    void validateQueryFeatures_acceptsObjectListFeature() {
        CbrFeatureValidator.validateQueryFeatures(
                Map.of("moments", structList(Map.of("type", string("KILL"), "minute", number(15)))), SCHEMA);
    }

    @Test
    void validateQueryFeatures_objectListRejectsWrongType() {
        assertThatThrownBy(() -> CbrFeatureValidator.validateQueryFeatures(
                Map.of("moments", string("not_a_list")), SCHEMA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ObjectList")
                .hasMessageContaining("StructListVal");
    }

    @Test
    void validateQueryFeatures_acceptsNumericListFeature() {
        var schema = CbrFeatureSchema.of("test", FeatureField.numericList("stats", 0, 100));
        CbrFeatureValidator.validateQueryFeatures(
                Map.of("stats", numberList(10.0, 50.0, 90.0)), schema);
    }

    @Test
    void validateQueryFeatures_numericListRejectsWrongType() {
        var schema = CbrFeatureSchema.of("test", FeatureField.numericList("stats", 0, 100));
        assertThatThrownBy(() -> CbrFeatureValidator.validateQueryFeatures(
                Map.of("stats", string("not_a_list")), schema))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NumericList")
                .hasMessageContaining("NumberListVal");
    }

    @Test
    void validateQueryFeatures_numericListRejectsOutOfRange() {
        var schema = CbrFeatureSchema.of("test", FeatureField.numericList("stats", 0, 100));
        assertThatThrownBy(() -> CbrFeatureValidator.validateQueryFeatures(
                Map.of("stats", numberList(50.0, 150.0)), schema))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside range");
    }

    @Test void validateQueryFeatures_acceptsFlatFeatures() {
        CbrFeatureValidator.validateQueryFeatures(
            Map.of("posture", string("AGGRESSIVE")), SCHEMA);
    }

    @Test void validateFilters_containsOnCategoricalList() {
        CbrFeatureValidator.validateFilters(
            Map.of("phases", CbrFilter.contains("A")), SCHEMA);
    }

    @Test void validateFilters_containsAllOnCategoricalList() {
        CbrFeatureValidator.validateFilters(
            Map.of("phases", CbrFilter.containsAll(List.of("A", "B"))), SCHEMA);
    }

    @Test void validateFilters_containsAnyOnCategoricalList() {
        CbrFeatureValidator.validateFilters(
            Map.of("phases", CbrFilter.containsAny(List.of("A", "B"))), SCHEMA);
    }

    @Test void validateFilters_containsOnCategoricalFieldAccepted() {
        CbrFeatureValidator.validateFilters(
            Map.of("posture", CbrFilter.contains("A")), SCHEMA);
    }

    @Test void validateFilters_containsOnNumericFieldRejected() {
        assertThatThrownBy(() -> CbrFeatureValidator.validateFilters(
            Map.of("score", CbrFilter.contains("A")), SCHEMA))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void validateFilters_hasMatchOnObjectList() {
        CbrFeatureValidator.validateFilters(
            Map.of("moments", CbrFilter.hasMatch(Map.of("type", string("X")))), SCHEMA);
    }

    @Test void validateFilters_hasMatchOnNestedObject() {
        CbrFeatureValidator.validateFilters(
            Map.of("economy", CbrFilter.hasMatch(Map.of("tier", string("gold")))), SCHEMA);
    }

    @Test void validateFilters_hasMatchOnCategoricalListRejected() {
        assertThatThrownBy(() -> CbrFeatureValidator.validateFilters(
            Map.of("phases", CbrFilter.hasMatch(Map.of("x", string("y")))), SCHEMA))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void validateFilters_unknownFieldRejected() {
        assertThatThrownBy(() -> CbrFeatureValidator.validateFilters(
            Map.of("nonexistent", CbrFilter.contains("A")), SCHEMA))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void validateFilters_hasMatchUnknownSubFieldRejected() {
        assertThatThrownBy(() -> CbrFeatureValidator.validateFilters(
            Map.of("moments", CbrFilter.hasMatch(Map.of("nonexistent", string("val")))), SCHEMA))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not found in inner schema");
    }

    @Test void validateFilters_hasMatchWrongSubFieldTypeRejected_numericFieldGotString() {
        assertThatThrownBy(() -> CbrFeatureValidator.validateFilters(
            Map.of("moments", CbrFilter.hasMatch(Map.of("minute", string("not_a_number")))), SCHEMA))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("requires NumberVal or RangeVal");
    }

    @Test void validateFilters_hasMatchWrongSubFieldTypeRejected_categoricalFieldGotNumber() {
        assertThatThrownBy(() -> CbrFeatureValidator.validateFilters(
            Map.of("economy", CbrFilter.hasMatch(Map.of("tier", number(42)))), SCHEMA))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("requires StringVal");
    }

    @Test void validateFilters_hasMatchAcceptsNumericRange() {
        CbrFeatureValidator.validateFilters(
            Map.of("moments", CbrFilter.hasMatch(Map.of("minute", range(0, 10)))), SCHEMA);
    }

    private static final CbrFeatureSchema TEMPORAL_SCHEMA = CbrFeatureSchema.of("game",
        FeatureField.timeSeries("curve", "t",
            FeatureField.numeric("t", 0, 30),
            FeatureField.numeric("val", 0, 100),
            FeatureField.categorical("label")),
        FeatureField.discreteSequence("phases"),
        FeatureField.categorical("race"));

    @SuppressWarnings("unchecked")
    @Test void store_timeSeries_validAscending() {
        var features = Map.<String, FeatureValue>of("curve", structList(
            Map.of("t", number(1), "val", number(30), "label", string("A")),
            Map.of("t", number(3), "val", number(45), "label", string("B"))));
        assertThatNoException().isThrownBy(() ->
            CbrFeatureValidator.validateStoreFeatures(features, TEMPORAL_SCHEMA));
    }

    @SuppressWarnings("unchecked")
    @Test void store_timeSeries_nonAscending_rejected() {
        var features = Map.<String, FeatureValue>of("curve", structList(
            Map.of("t", number(3), "val", number(45), "label", string("B")),
            Map.of("t", number(1), "val", number(30), "label", string("A"))));
        assertThatThrownBy(() ->
            CbrFeatureValidator.validateStoreFeatures(features, TEMPORAL_SCHEMA))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ascending");
    }

    @SuppressWarnings("unchecked")
    @Test void store_timeSeries_equalTimestamps_rejected() {
        var features = Map.<String, FeatureValue>of("curve", structList(
            Map.of("t", number(1), "val", number(30), "label", string("A")),
            Map.of("t", number(1), "val", number(45), "label", string("B"))));
        assertThatThrownBy(() ->
            CbrFeatureValidator.validateStoreFeatures(features, TEMPORAL_SCHEMA))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ascending");
    }

    @SuppressWarnings("unchecked")
    @Test void store_timeSeries_missingTimestampField_rejected() {
        var features = Map.<String, FeatureValue>of("curve", structList(
            Map.of("val", number(30), "label", string("A"))));
        assertThatThrownBy(() ->
            CbrFeatureValidator.validateStoreFeatures(features, TEMPORAL_SCHEMA))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("timestamp");
    }

    @Test void store_timeSeries_wrongType_rejected() {
        var features = Map.<String, FeatureValue>of("curve", string("not-a-list"));
        assertThatThrownBy(() ->
            CbrFeatureValidator.validateStoreFeatures(features, TEMPORAL_SCHEMA))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @SuppressWarnings("unchecked")
    @Test void store_timeSeries_wrongInnerType_rejected() {
        var features = Map.<String, FeatureValue>of("curve", structList(
            Map.of("t", number(1), "val", string("not-a-number"), "label", string("A"))));
        assertThatThrownBy(() ->
            CbrFeatureValidator.validateStoreFeatures(features, TEMPORAL_SCHEMA))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void store_timeSeries_emptyList_accepted() {
        var features = Map.<String, FeatureValue>of("curve", structList(List.of()));
        assertThatNoException().isThrownBy(() ->
            CbrFeatureValidator.validateStoreFeatures(features, TEMPORAL_SCHEMA));
    }

    @Test void store_discreteSequence_valid() {
        var features = Map.<String, FeatureValue>of("phases", stringList("A", "B", "C"));
        assertThatNoException().isThrownBy(() ->
            CbrFeatureValidator.validateStoreFeatures(features, TEMPORAL_SCHEMA));
    }

    @Test void store_discreteSequence_wrongType_rejected() {
        var features = Map.<String, FeatureValue>of("phases", string("not-a-list"));
        assertThatThrownBy(() ->
            CbrFeatureValidator.validateStoreFeatures(features, TEMPORAL_SCHEMA))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void store_discreteSequence_wrongVariant_rejected() {
        var features = Map.<String, FeatureValue>of("phases", numberList(42.0));
        assertThatThrownBy(() ->
            CbrFeatureValidator.validateStoreFeatures(features, TEMPORAL_SCHEMA))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void store_discreteSequence_emptyList_accepted() {
        var features = Map.<String, FeatureValue>of("phases", stringList());
        assertThatNoException().isThrownBy(() ->
            CbrFeatureValidator.validateStoreFeatures(features, TEMPORAL_SCHEMA));
    }

    @SuppressWarnings("unchecked")
    @Test void query_timeSeries_allowed() {
        var features = Map.<String, FeatureValue>of("curve", structList(
            Map.of("t", number(1), "val", number(30), "label", string("A"))));
        assertThatNoException().isThrownBy(() ->
            CbrFeatureValidator.validateQueryFeatures(features, TEMPORAL_SCHEMA));
    }

    @Test void query_discreteSequence_allowed() {
        var features = Map.<String, FeatureValue>of("phases", stringList("A", "B"));
        assertThatNoException().isThrownBy(() ->
            CbrFeatureValidator.validateQueryFeatures(features, TEMPORAL_SCHEMA));
    }

    @SuppressWarnings("unchecked")
    @Test void query_timeSeries_nonAscending_rejected() {
        var features = Map.<String, FeatureValue>of("curve", structList(
            Map.of("t", number(3), "val", number(45), "label", string("B")),
            Map.of("t", number(1), "val", number(30), "label", string("A"))));
        assertThatThrownBy(() ->
            CbrFeatureValidator.validateQueryFeatures(features, TEMPORAL_SCHEMA))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ascending");
    }

    @Test void filter_onTimeSeries_rejected() {
        assertThatThrownBy(() ->
            CbrFeatureValidator.validateFilters(
                Map.of("curve", CbrFilter.contains("X")), TEMPORAL_SCHEMA))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void filter_onDiscreteSequence_rejected() {
        assertThatThrownBy(() ->
            CbrFeatureValidator.validateFilters(
                Map.of("phases", CbrFilter.contains("X")), TEMPORAL_SCHEMA))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
