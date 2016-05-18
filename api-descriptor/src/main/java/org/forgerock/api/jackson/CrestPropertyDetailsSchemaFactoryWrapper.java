/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2016 ForgeRock AS.
 */

package org.forgerock.api.jackson;

import static org.forgerock.api.util.ValidationUtil.isEmpty;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.lang.annotation.Annotation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsonFormatVisitors.JsonObjectFormatVisitor;
import com.fasterxml.jackson.databind.type.SimpleType;
import com.fasterxml.jackson.module.jsonSchema.JsonSchema;
import com.fasterxml.jackson.module.jsonSchema.factories.ObjectVisitor;
import com.fasterxml.jackson.module.jsonSchema.factories.ObjectVisitorDecorator;
import com.fasterxml.jackson.module.jsonSchema.factories.SchemaFactoryWrapper;
import com.fasterxml.jackson.module.jsonSchema.factories.VisitorContext;
import com.fasterxml.jackson.module.jsonSchema.factories.WrapperFactory;
import com.fasterxml.jackson.module.jsonSchema.types.ArraySchema;
import com.fasterxml.jackson.module.jsonSchema.types.NumberSchema;
import com.fasterxml.jackson.module.jsonSchema.types.SimpleTypeSchema;
import com.fasterxml.jackson.module.jsonSchema.types.StringSchema;
import org.forgerock.api.annotations.Default;
import org.forgerock.api.annotations.EnumTitle;
import org.forgerock.api.annotations.Format;
import org.forgerock.api.annotations.MultipleOf;
import org.forgerock.api.annotations.PropertyOrder;
import org.forgerock.api.annotations.PropertyPolicies;
import org.forgerock.api.annotations.ReadOnly;
import org.forgerock.api.annotations.Title;
import org.forgerock.api.annotations.UniqueItems;
import org.forgerock.api.enums.WritePolicy;

/**
 * A {@code SchemaFactoryWrapper} that adds the extra CREST schema attributes once the Jackson schema generation has
 * been completed.
 */
public class CrestPropertyDetailsSchemaFactoryWrapper extends SchemaFactoryWrapper {

    private static final WrapperFactory WRAPPER_FACTORY = new WrapperFactory() {
        @Override
        public SchemaFactoryWrapper getWrapper(SerializerProvider provider) {
            SchemaFactoryWrapper wrapper = new CrestPropertyDetailsSchemaFactoryWrapper();
            wrapper.setProvider(provider);
            return wrapper;
        }

        @Override
        public SchemaFactoryWrapper getWrapper(SerializerProvider provider, VisitorContext rvc) {
            SchemaFactoryWrapper wrapper = new CrestPropertyDetailsSchemaFactoryWrapper();
            wrapper.setProvider(provider);
            wrapper.setVisitorContext(rvc);
            return wrapper;
        }
    };

    /**
     * Create a new wrapper. Sets the {@link CrestJsonSchemaFactory} in the parent class's {@code schemaProvider} so
     * that all of the schema objects that are created support the appropriate API Descriptor extensions.
     */
    public CrestPropertyDetailsSchemaFactoryWrapper() {
        super(WRAPPER_FACTORY);
        this.schemaProvider = new CrestJsonSchemaFactory();
    }

    @Override
    public JsonObjectFormatVisitor expectObjectFormat(JavaType convertedType) {
        return new ObjectVisitorDecorator((ObjectVisitor) super.expectObjectFormat(convertedType)) {
            @Override
            public JsonSchema getSchema() {
                return super.getSchema();
            }

            @Override
            public void optionalProperty(BeanProperty writer) throws JsonMappingException {
                super.optionalProperty(writer);
                JsonSchema schema = schemaFor(writer);
                addFieldPolicies(writer, schema);
                addPropertyOrder(writer, schema);
                addEnumTitles(writer, schema);
                addRequired(writer, schema);
                addStringPattern(writer, schema);
                addStringMinLength(writer, schema);
                addStringMaxLength(writer, schema);
                addArrayMinItems(writer, schema);
                addArrayMaxItems(writer, schema);
                addNumberMaximum(writer, schema);
                addNumberMinimum(writer, schema);
                addNumberExclusiveMinimum(writer, schema);
                addNumberExclusiveMaximum(writer, schema);
                addReadOnly(writer, schema);
                addTitle(writer, schema);
                addDefault(writer, schema);
                addUniqueItems(writer, schema);
                addMultipleOf(writer, schema);
                addFormat(writer, schema);
            }

            private void addEnumTitles(BeanProperty writer, JsonSchema schema) {
                JavaType type = writer.getType();
                if (type.isEnumType()) {
                    Class<? extends Enum> enumClass = type.getRawClass().asSubclass(Enum.class);
                    Enum[] enumConstants = enumClass.getEnumConstants();
                    List<String> titles = new ArrayList<>(enumConstants.length);
                    boolean foundTitle = false;
                    for (Enum<?> value : enumConstants) {
                        try {
                            EnumTitle title = enumClass.getField(value.name()).getAnnotation(EnumTitle.class);
                            if (title != null) {
                                titles.add(title.value());
                                foundTitle = true;
                            } else {
                                titles.add(null);
                            }
                        } catch (NoSuchFieldException e) {
                            throw new IllegalStateException("Enum doesn't have its own value as a field", e);
                        }
                    }
                    if (foundTitle) {
                        ((EnumSchema) schema).setEnumTitles(titles);
                    }
                }
            }

            private void addPropertyOrder(BeanProperty writer, JsonSchema schema) {
                PropertyOrder order = annotationFor(writer, PropertyOrder.class);
                if (order != null) {
                    ((OrderedFieldSchema) schema).setPropertyOrder(order.value());
                }
            }

            private void addFieldPolicies(BeanProperty writer, JsonSchema schema) {
                PropertyPolicies policies = annotationFor(writer, PropertyPolicies.class);
                if (policies != null) {
                    CrestReadWritePoliciesSchema schemaPolicies = (CrestReadWritePoliciesSchema) schema;
                    if (policies.write() != WritePolicy.WRITABLE) {
                        schemaPolicies.setWritePolicy(policies.write());
                        schemaPolicies.setErrorOnWritePolicyFailure(policies.errorOnWritePolicyFailure());
                    }
                    schemaPolicies.setReadPolicy(policies.read());
                    schemaPolicies.setReturnOnDemand(policies.returnOnDemand());
                }
            }

            private void addRequired(BeanProperty writer, JsonSchema schema) {
                NotNull notNull = annotationFor(writer, NotNull.class);
                if (notNull != null) {
                    schema.setRequired(true);
                }
            }

            private void addStringPattern(BeanProperty writer, JsonSchema schema) {
                Pattern pattern = annotationFor(writer, Pattern.class);
                if (pattern != null && !isEmpty(pattern.regexp())) {
                    ((StringSchema) schema).setPattern(pattern.regexp());
                }
            }

            private void addStringMinLength(BeanProperty writer, JsonSchema schema) {
                Integer size = getMinSize(writer);
                if (size != null && schema instanceof StringSchema) {
                    ((StringSchema) schema).setMinLength(size);
                }
            }

            private void addStringMaxLength(BeanProperty writer, JsonSchema schema) {
                Integer size = getMaxSize(writer);
                if (size != null && schema instanceof StringSchema) {
                    ((StringSchema) schema).setMaxLength(size);
                }
            }

            private void addArrayMinItems(BeanProperty writer, JsonSchema schema) {
                Integer size = getMinSize(writer);
                if (size != null && schema instanceof ArraySchema) {
                    ((ArraySchema) schema).setMinItems(size);
                }
            }

            private void addArrayMaxItems(BeanProperty writer, JsonSchema schema) {
                Integer size = getMaxSize(writer);
                if (size != null && schema instanceof ArraySchema) {
                    ((ArraySchema) schema).setMaxItems(size);
                }
            }

            private void addNumberMinimum(BeanProperty writer, JsonSchema schema) {
                Min min = annotationFor(writer, Min.class);
                if (min != null) {
                    ((MinimumMaximumSchema) schema).setPropertyMinimum(new BigDecimal(min.value()));
                }

                DecimalMin decimalMin = annotationFor(writer, DecimalMin.class);
                if (decimalMin != null) {
                    ((MinimumMaximumSchema) schema).setPropertyMinimum(new BigDecimal(decimalMin.value()));
                }
            }

            private void addNumberMaximum(BeanProperty writer, JsonSchema schema) {
                Max max = annotationFor(writer, Max.class);
                if (max != null) {
                    ((MinimumMaximumSchema) schema).setPropertyMaximum(new BigDecimal(max.value()));
                }

                DecimalMax decimalMax = annotationFor(writer, DecimalMax.class);
                if (decimalMax != null) {
                    ((MinimumMaximumSchema) schema).setPropertyMaximum(new BigDecimal(decimalMax.value()));
                }
            }

            private void addNumberExclusiveMinimum(BeanProperty writer, JsonSchema schema) {
                DecimalMin decimalMin = annotationFor(writer, DecimalMin.class);
                if (decimalMin != null && !decimalMin.inclusive()) {
                    ((NumberSchema) schema).setExclusiveMinimum(true);
                }
            }

            private void addNumberExclusiveMaximum(BeanProperty writer, JsonSchema schema) {
                DecimalMax decimalMax = annotationFor(writer, DecimalMax.class);
                if (decimalMax != null && !decimalMax.inclusive()) {
                    ((NumberSchema) schema).setExclusiveMaximum(true);
                }
            }

            private void addReadOnly(BeanProperty writer, JsonSchema schema) {
                ReadOnly readOnly = annotationFor(writer, ReadOnly.class);
                if (readOnly != null) {
                    schema.setReadonly(readOnly.value());
                }
            }

            private void addTitle(BeanProperty writer, JsonSchema schema) {
                Title title = annotationFor(writer, Title.class);
                if (title != null && !isEmpty(title.value())) {
                    ((SimpleTypeSchema) schema).setTitle(title.value());
                }
            }

            private void addDefault(BeanProperty writer, JsonSchema schema) {
                Default defaultAnnotation = annotationFor(writer, Default.class);
                if (defaultAnnotation != null && !isEmpty(defaultAnnotation.value())) {
                    ((SimpleTypeSchema) schema).setDefault(defaultAnnotation.value());
                }
            }

            private void addUniqueItems(BeanProperty writer, JsonSchema schema) {
                UniqueItems uniqueItems = annotationFor(writer, UniqueItems.class);
                if (uniqueItems != null) {
                    ((ArraySchema) schema).setUniqueItems(uniqueItems.value());
                }
            }

            private void addMultipleOf(BeanProperty writer, JsonSchema schema) {
                MultipleOf multipleOf = annotationFor(writer, MultipleOf.class);
                if (multipleOf != null) {
                    ((MultipleOfSchema) schema).setMultipleOf(multipleOf.value());
                }
            }

            private void addFormat(BeanProperty writer, JsonSchema schema) {
                if (schema instanceof PropertyFormatSchema) {
                    Format format = annotationFor(writer, Format.class);
                    if (format != null && !isEmpty(format.value())) {
                        ((PropertyFormatSchema) schema).setPropertyFormat(format.value());
                    } else if (writer.getType() instanceof SimpleType) {
                        // automatically assign 'format' to numeric types
                        final Class rawClass = writer.getType().getRawClass();
                        final String formatValue;
                        if (Integer.class.equals(rawClass) || int.class.equals(rawClass)) {
                            formatValue = "int32";
                        } else if (Long.class.equals(rawClass) || long.class.equals(rawClass)) {
                            formatValue = "int64";
                        } else if (Double.class.equals(rawClass) || double.class.equals(rawClass)) {
                            formatValue = "double";
                        } else if (Float.class.equals(rawClass) || float.class.equals(rawClass)) {
                            formatValue = "float";
                        } else {
                            return;
                        }
                        ((PropertyFormatSchema) schema).setPropertyFormat(formatValue);
                    }
                }
            }

            private Integer getMaxSize(BeanProperty writer) {
                Size size = writer.getAnnotation(Size.class);
                if (size != null) {
                    int value = size.max();
                    if (value != Integer.MAX_VALUE) {
                        return value;
                    }
                }
                return null;
            }

            private Integer getMinSize(BeanProperty writer) {
                Size size = writer.getAnnotation(Size.class);
                if (size != null) {
                    int value = size.min();
                    if (value != 0) {
                        return value;
                    }
                }
                return null;
            }

            private <T extends Annotation> T annotationFor(BeanProperty writer, Class<T> type) {
                return writer.getMember().getAnnotation(type);
            }

            private JsonSchema schemaFor(BeanProperty writer) {
                return getSchema().asObjectSchema().getProperties().get(writer.getName());
            }
        };
    }
}
