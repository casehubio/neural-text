package io.casehub.neocortex.memory.cbr;

import java.util.List;
import java.util.Map;

public final class CbrFeatureValidator {

    private CbrFeatureValidator() {}

    public static void validateStoreFeatures(Map<String, FeatureValue> features, CbrFeatureSchema schema) {
        for (var entry : features.entrySet()) {
            FeatureField field = findField(schema, entry.getKey());
            if (field == null) {continue;}
            FeatureValue value = entry.getValue();
            switch (field) {
                case FeatureField.Categorical c -> requireType(entry.getKey(), value, FeatureValue.StringVal.class, "Categorical");
                case FeatureField.Numeric n -> {
                    if (!(value instanceof FeatureValue.NumberVal)) {
                        throw new IllegalArgumentException(
                                "Numeric field '" + entry.getKey() + "' requires NumberVal, got: "
                                + value.getClass().getSimpleName());
                    }
                }
                case FeatureField.Text t -> requireType(entry.getKey(), value, FeatureValue.StringVal.class, "Text");
                case FeatureField.CategoricalList cl -> {
                    if (!(value instanceof FeatureValue.StringListVal)) {
                        throw new IllegalArgumentException(
                                "CategoricalList field '" + entry.getKey() + "' requires StringListVal, got: "
                                + value.getClass().getSimpleName());
                    }
                }
                case FeatureField.NumericList nl -> {
                    if (!(value instanceof FeatureValue.NumberListVal nlv)) {
                        throw new IllegalArgumentException(
                                "NumericList field '" + entry.getKey() + "' requires NumberListVal, got: "
                                + value.getClass().getSimpleName());
                    }
                    for (Double d : nlv.values()) {
                        if (d < nl.min() || d > nl.max()) {
                            throw new IllegalArgumentException(
                                    "NumericList field '" + entry.getKey() + "' element " + d
                                    + " outside range [" + nl.min() + ", " + nl.max() + "]");
                        }
                    }
                }
                case FeatureField.NestedObject no -> {
                    if (!(value instanceof FeatureValue.StructVal sv)) {
                        throw new IllegalArgumentException(
                                "NestedObject field '" + entry.getKey() + "' requires StructVal, got: "
                                + value.getClass().getSimpleName());
                    }
                    validateInnerValues(entry.getKey(), sv.fields(), no.innerFields());
                }
                case FeatureField.ObjectList ol -> {
                    if (!(value instanceof FeatureValue.StructListVal sl)) {
                        throw new IllegalArgumentException(
                                "ObjectList field '" + entry.getKey() + "' requires StructListVal, got: "
                                + value.getClass().getSimpleName());
                    }
                    for (Map<String, FeatureValue> item : sl.items()) {
                        validateInnerValues(entry.getKey(), item, ol.innerFields());
                    }
                }
                case FeatureField.TimeSeries ts -> validateTimeSeries(entry.getKey(), value, ts);
                case FeatureField.DiscreteSequence ds -> validateDiscreteSequence(entry.getKey(), value);
            }
        }
    }

    public static void validateQueryFeatures(Map<String, FeatureValue> features, CbrFeatureSchema schema) {
        for (var entry : features.entrySet()) {
            FeatureField field = findField(schema, entry.getKey());
            if (field == null) {continue;}
            FeatureValue value = entry.getValue();
            switch (field) {
                case FeatureField.Categorical c -> requireType(entry.getKey(), value, FeatureValue.StringVal.class, "Categorical");
                case FeatureField.Numeric n -> {
                    if (!(value instanceof FeatureValue.NumberVal) && !(value instanceof FeatureValue.RangeVal)) {
                        throw new IllegalArgumentException(
                                "Numeric field '" + entry.getKey() + "' requires NumberVal or RangeVal, got: "
                                + value.getClass().getSimpleName());
                    }
                }
                case FeatureField.Text t -> requireType(entry.getKey(), value, FeatureValue.StringVal.class, "Text");
                case FeatureField.CategoricalList cl -> {
                    if (!(value instanceof FeatureValue.StringListVal)) {
                        throw new IllegalArgumentException(
                                "CategoricalList field '" + entry.getKey() + "' requires StringListVal, got: "
                                + value.getClass().getSimpleName());
                    }
                }
                case FeatureField.NumericList nl -> {
                    if (!(value instanceof FeatureValue.NumberListVal nlv)) {
                        throw new IllegalArgumentException(
                                "NumericList field '" + entry.getKey() + "' requires NumberListVal, got: "
                                + value.getClass().getSimpleName());
                    }
                    for (Double d : nlv.values()) {
                        if (d < nl.min() || d > nl.max()) {
                            throw new IllegalArgumentException(
                                    "NumericList field '" + entry.getKey() + "' element " + d
                                    + " outside range [" + nl.min() + ", " + nl.max() + "]");
                        }
                    }
                }
                case FeatureField.NestedObject no -> {
                    if (!(value instanceof FeatureValue.StructVal sv)) {
                        throw new IllegalArgumentException(
                                "NestedObject field '" + entry.getKey() + "' requires StructVal, got: "
                                + value.getClass().getSimpleName());
                    }
                    validateInnerValues(entry.getKey(), sv.fields(), no.innerFields());
                }
                case FeatureField.ObjectList ol -> {
                    if (!(value instanceof FeatureValue.StructListVal sl)) {
                        throw new IllegalArgumentException(
                                "ObjectList field '" + entry.getKey() + "' requires StructListVal, got: "
                                + value.getClass().getSimpleName());
                    }
                    for (Map<String, FeatureValue> item : sl.items()) {
                        validateInnerValues(entry.getKey(), item, ol.innerFields());
                    }
                }
                case FeatureField.TimeSeries ts -> validateTimeSeries(entry.getKey(), value, ts);
                case FeatureField.DiscreteSequence ds -> validateDiscreteSequence(entry.getKey(), value);
            }
        }}

    public static void validateFilters(Map<String, CbrFilter> filters, CbrFeatureSchema schema) {
        for (var entry : filters.entrySet()) {
            String       name   = entry.getKey();
            CbrFilter    filter = entry.getValue();
            FeatureField field  = findField(schema, name);
            if (field == null) {throw new IllegalArgumentException("Filter field '" + name + "' not found in schema");}

            validateSingleFilter(name, filter, field);
        }}

    private static void validateSingleFilter(String name, CbrFilter filter, FeatureField field) {
        switch (filter) {
            case CbrFilter.Contains c -> requireCategorical(name, field);
            case CbrFilter.ContainsAll ca -> requireCategorical(name, field);
            case CbrFilter.ContainsAny ca -> requireCategorical(name, field);
            case CbrFilter.NotContains nc -> requireCategorical(name, field);
            case CbrFilter.NotContainsAny nca -> requireCategorical(name, field);
            case CbrFilter.ContainsRange cr -> requireNumericList(name, field);
            case CbrFilter.HasMatch hm -> {
                if (!(field instanceof FeatureField.NestedObject) && !(field instanceof FeatureField.ObjectList)) {
                    throw new IllegalArgumentException(
                            "HasMatch filter on '" + name + "' requires NestedObject or ObjectList field, got: "
                            + field.getClass().getSimpleName());
                }
                List<FeatureField> innerFields = field instanceof FeatureField.NestedObject no
                                                 ? no.innerFields() : ((FeatureField.ObjectList) field).innerFields();
                validateHasMatchSubFields(name, hm, innerFields);
            }
            case CbrFilter.AllOf allOf -> {
                for (CbrFilter inner : allOf.filters()) {
                    validateSingleFilter(name, inner, field);
                }
            }
        }
    }


    private static void validateHasMatchSubFields(String fieldName, CbrFilter.HasMatch hm,
                                                  List<FeatureField> innerFields) {
        for (var sub : hm.subFields().entrySet()) {
            FeatureField inner = null;
            for (FeatureField f : innerFields) {
                if (f.name().equals(sub.getKey())) {
                    inner = f;
                    break;
                }
            }
            if (inner == null) {
                throw new IllegalArgumentException(
                        "HasMatch sub-field '" + sub.getKey() + "' not found in inner schema of '" + fieldName + "'");
            }

            FeatureValue value = sub.getValue();
            switch (inner) {
                case FeatureField.Categorical c -> {
                    if (!(value instanceof FeatureValue.StringVal)) {
                        throw new IllegalArgumentException(
                                "HasMatch sub-field '" + sub.getKey() + "' on '" + fieldName
                                + "' requires StringVal, got: " + value.getClass().getSimpleName());
                    }
                }
                case FeatureField.Text t -> {
                    if (!(value instanceof FeatureValue.StringVal)) {
                        throw new IllegalArgumentException(
                                "HasMatch sub-field '" + sub.getKey() + "' on '" + fieldName
                                + "' requires StringVal, got: " + value.getClass().getSimpleName());
                    }
                }
                case FeatureField.Numeric n -> {
                    if (!(value instanceof FeatureValue.NumberVal) && !(value instanceof FeatureValue.RangeVal)) {
                        throw new IllegalArgumentException(
                                "HasMatch sub-field '" + sub.getKey() + "' on '" + fieldName
                                + "' requires NumberVal or RangeVal, got: " + value.getClass().getSimpleName());
                    }
                }
                default -> throw new IllegalStateException("Unexpected inner field type: " + inner);
            }
        }
    }

    private static void requireCategorical(String name, FeatureField field) {
        if (field instanceof FeatureField.TimeSeries || field instanceof FeatureField.DiscreteSequence)
            throw new IllegalArgumentException(
                "Temporal field '" + name + "' does not support filters");
        if (!(field instanceof FeatureField.CategoricalList) && !(field instanceof FeatureField.Categorical))
            throw new IllegalArgumentException(
                "Contains/ContainsAll/ContainsAny filter on '" + name
                + "' requires Categorical or CategoricalList field, got: " + field.getClass().getSimpleName());
    }

    private static void requireNumericList(String name, FeatureField field) {
        if (field instanceof FeatureField.TimeSeries || field instanceof FeatureField.DiscreteSequence) {
            throw new IllegalArgumentException(
                    "Temporal field '" + name + "' does not support filters");
        }
        if (!(field instanceof FeatureField.NumericList)) {
            throw new IllegalArgumentException(
                    "ContainsRange filter on '" + name
                    + "' requires NumericList field, got: " + field.getClass().getSimpleName());
        }
    }


    private static void validateTimeSeries(String fieldName, FeatureValue value, FeatureField.TimeSeries ts) {
        if (!(value instanceof FeatureValue.StructListVal sl)) {
            throw new IllegalArgumentException(
                    "TimeSeries field '" + fieldName + "' requires StructListVal, got: " + value.getClass().getSimpleName());
        }
        if (sl.items().isEmpty()) {return;}
        double prevTimestamp = Double.NEGATIVE_INFINITY;
        for (Map<String, FeatureValue> obs : sl.items()) {
            FeatureValue tsVal = obs.get(ts.timestampField());
            if (tsVal == null) {
                throw new IllegalArgumentException(
                        "TimeSeries field '" + fieldName + "': observation missing timestamp field '"
                        + ts.timestampField() + "'");
            }
            if (!(tsVal instanceof FeatureValue.NumberVal num)) {
                throw new IllegalArgumentException(
                        "TimeSeries field '" + fieldName + "': timestamp field '"
                        + ts.timestampField() + "' requires NumberVal, got: " + tsVal.getClass().getSimpleName());
            }
            double currentTs = num.value();
            if (currentTs <= prevTimestamp) {
                throw new IllegalArgumentException(
                        "TimeSeries field '" + fieldName + "': observations must be in strictly ascending timestamp order");
            }
            prevTimestamp = currentTs;
            validateInnerValues(fieldName, obs, ts.innerFields());
        }
    }

    private static void validateDiscreteSequence(String fieldName, FeatureValue value) {
        if (!(value instanceof FeatureValue.StringListVal)) {
            throw new IllegalArgumentException(
                    "DiscreteSequence field '" + fieldName + "' requires StringListVal, got: " + value.getClass().getSimpleName());
        }
    }

    private static void requireType(String name, FeatureValue value, Class<? extends FeatureValue> expected, String fieldTypeName) {
        if (!expected.isInstance(value)) {
            throw new IllegalArgumentException(
                    fieldTypeName + " field '" + name + "' requires " + expected.getSimpleName()
                    + ", got: " + value.getClass().getSimpleName());
        }
    }

    private static void validateInnerValues(String parentName, Map<String, FeatureValue> map,
                                            List<FeatureField> innerFields) {
        for (var entry : map.entrySet()) {
            String       key   = entry.getKey();
            FeatureField inner = null;
            for (FeatureField f : innerFields) {
                if (f.name().equals(key)) {
                    inner = f;
                    break;
                }
            }
            if (inner == null) {continue;}
            FeatureValue value = entry.getValue();
            switch (inner) {
                case FeatureField.Categorical c -> requireType(parentName + "." + key, value, FeatureValue.StringVal.class, "Categorical");
                case FeatureField.Numeric n -> {
                    if (!(value instanceof FeatureValue.NumberVal)) {
                        throw new IllegalArgumentException(
                                "Inner field '" + parentName + "." + key + "' requires NumberVal, got: "
                                + value.getClass().getSimpleName());
                    }
                }
                case FeatureField.Text t -> requireType(parentName + "." + key, value, FeatureValue.StringVal.class, "Text");
                default -> {}
            }
        }
    }

    public static FeatureField findField(CbrFeatureSchema schema, String name) {
        for (FeatureField f : schema.fields()) {
            if (f.name().equals(name)) return f;
        }
        return null;
    }
}
