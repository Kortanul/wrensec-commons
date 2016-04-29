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

package org.forgerock.api.transform;

import static java.lang.Boolean.TRUE;
import static java.util.Collections.unmodifiableList;
import static org.forgerock.api.markup.asciidoc.AsciiDoc.normalizeName;
import static org.forgerock.api.util.PathUtil.*;
import static org.forgerock.api.util.ValidationUtil.isEmpty;
import static org.forgerock.json.JsonValue.*;
import static org.forgerock.util.Reject.checkNotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.swagger.models.ArrayModel;
import io.swagger.models.Info;
import io.swagger.models.Model;
import io.swagger.models.ModelImpl;
import io.swagger.models.Operation;
import io.swagger.models.Path;
import io.swagger.models.RefModel;
import io.swagger.models.Response;
import io.swagger.models.Scheme;
import io.swagger.models.Swagger;
import io.swagger.models.Tag;
import io.swagger.models.parameters.BodyParameter;
import io.swagger.models.parameters.HeaderParameter;
import io.swagger.models.parameters.PathParameter;
import io.swagger.models.parameters.QueryParameter;
import io.swagger.models.parameters.RefParameter;
import io.swagger.models.parameters.SerializableParameter;
import io.swagger.models.properties.AbstractNumericProperty;
import io.swagger.models.properties.AbstractProperty;
import io.swagger.models.properties.ArrayProperty;
import io.swagger.models.properties.BinaryProperty;
import io.swagger.models.properties.BooleanProperty;
import io.swagger.models.properties.ByteArrayProperty;
import io.swagger.models.properties.DateProperty;
import io.swagger.models.properties.DateTimeProperty;
import io.swagger.models.properties.DoubleProperty;
import io.swagger.models.properties.FloatProperty;
import io.swagger.models.properties.IntegerProperty;
import io.swagger.models.properties.LongProperty;
import io.swagger.models.properties.ObjectProperty;
import io.swagger.models.properties.PasswordProperty;
import io.swagger.models.properties.Property;
import io.swagger.models.properties.RefProperty;
import io.swagger.models.properties.StringProperty;
import io.swagger.models.properties.UUIDProperty;
import org.forgerock.api.enums.CountPolicy;
import org.forgerock.api.enums.PagingMode;
import org.forgerock.api.enums.ParameterSource;
import org.forgerock.api.enums.PatchOperation;
import org.forgerock.api.enums.QueryType;
import org.forgerock.api.enums.Stability;
import org.forgerock.api.markup.asciidoc.AsciiDoc;
import org.forgerock.api.models.Action;
import org.forgerock.api.models.ApiDescription;
import org.forgerock.api.models.Create;
import org.forgerock.api.models.Definitions;
import org.forgerock.api.models.Delete;
import org.forgerock.api.models.Error;
import org.forgerock.api.models.Errors;
import org.forgerock.api.models.Parameter;
import org.forgerock.api.models.Patch;
import org.forgerock.api.models.Paths;
import org.forgerock.api.models.Query;
import org.forgerock.api.models.Read;
import org.forgerock.api.models.Reference;
import org.forgerock.api.models.Resource;
import org.forgerock.api.models.Schema;
import org.forgerock.api.models.Services;
import org.forgerock.api.models.SubResources;
import org.forgerock.api.models.Update;
import org.forgerock.api.models.VersionedPath;
import org.forgerock.api.util.ReferenceResolver;
import org.forgerock.http.routing.Version;
import org.forgerock.json.JsonValue;
import org.forgerock.util.annotations.VisibleForTesting;

/**
 * Transforms an {@link ApiDescription} into an OpenAPI/Swagger model.
 *
 * @see <a href="https://github.com/OAI/OpenAPI-Specification/blob/master/versions/2.0.md">OpenAPI 2.0</a> spec
 */
public class OpenApiTransformer {

    private static final String EMPTY_STRING = "";

    private static final String PARAMETER_FIELDS = "_fields";

    private static final String PARAMETER_PRETTY_PRINT = "_prettyPrint";

    private static final String PARAMETER_MIME_TYPE = "_mimeType";

    private static final String PARAMETER_IF_MATCH = "If-Match";

    private static final String PARAMETER_IF_NONE_MATCH = "If-None-Match";

    private static final String PARAMETER_IF_NONE_MATCH_ANY_ONLY = "If-None-Match: *";

    private static final String PARAMETER_IF_NONE_MATCH_REV_ONLY = "If-None-Match: <rev>";

    static final String DEFINITIONS_REF = "#/definitions/";

    /**
     * Transforms an {@link ApiDescription} into a {@code Swagger} model.
     *
     * @param title API title
     * @param host Hostname or IP address, with optional port
     * @param basePath Base-path on host
     * @param secure {@code true} when host is using HTTPS and {@code false} when using HTTP
     * @param apiDescription CREST API Descriptor
     * @param externalApiDescriptions External CREST API Descriptions, for resolving {@link Reference}s, or {@code null}
     * @return {@code Swagger} model
     */
    public Swagger transform(final String title, final String host, final String basePath, final boolean secure,
            final ApiDescription apiDescription, final List<ApiDescription> externalApiDescriptions) {
        final Swagger swagger = new Swagger()
                .scheme(secure ? Scheme.HTTPS : Scheme.HTTP)
                .host(host)
                .basePath(basePath)
                .consumes("application/json")
                .consumes("text/plain")
                .consumes("multipart/form-data")
                .produces("application/json")
                .info(buildInfo(title, apiDescription));

        final ReferenceResolver referenceResolver = new ReferenceResolver(apiDescription);
        if (externalApiDescriptions != null) {
            referenceResolver.registerAll(externalApiDescriptions);
        }

        buildParameters(swagger);
        buildPaths(apiDescription.getPaths(), apiDescription.getServices(), apiDescription.getErrors(), swagger,
                referenceResolver);
        buildDefinitions(apiDescription.getDefinitions(), swagger);
        return swagger;
    }

    /**
     * Build globally-defined parameters, which are referred to by-reference.
     *
     * @param swagger Swagger model
     */
    private void buildParameters(final Swagger swagger) {
        // _fields
        final QueryParameter fieldsParameter = new QueryParameter();
        fieldsParameter.setName(PARAMETER_FIELDS);
        fieldsParameter.setType("string");
        fieldsParameter.setCollectionFormat("csv");
        fieldsParameter.setDescription(
                "Optional parameter containing a comma separated list of field references specifying which fields of "
                        + "the targeted JSON resource should be returned.");
        swagger.addParameter(fieldsParameter.getName(), fieldsParameter);

        // _prettyPrint
        final QueryParameter prettyPrintParameter = new QueryParameter();
        prettyPrintParameter.setName(PARAMETER_PRETTY_PRINT);
        prettyPrintParameter.setType("boolean");
        prettyPrintParameter.setDescription(
                "Optional parameter requesting that the returned JSON resource content should be formatted to be more "
                        + "human readable.");
        swagger.addParameter(prettyPrintParameter.getName(), prettyPrintParameter);

        // _mimeType
        final QueryParameter mimeTypeParameter = new QueryParameter();
        mimeTypeParameter.setName(PARAMETER_MIME_TYPE);
        mimeTypeParameter.setType("string");
        mimeTypeParameter.setDescription(
                "Optional parameter requesting that the response have the given MIME-Type. Use of this parameter "
                        + "requires a _fields parameter with a single field specified.");
        swagger.addParameter(mimeTypeParameter.getName(), mimeTypeParameter);

        // PUT-operation IF-NONE-MATCH always has * value
        final HeaderParameter putIfNoneMatchParameter = new HeaderParameter();
        putIfNoneMatchParameter.setName(PARAMETER_IF_NONE_MATCH);
        putIfNoneMatchParameter.setType("string");
        putIfNoneMatchParameter.required(true);
        putIfNoneMatchParameter.setEnum(Arrays.asList("*"));
        swagger.addParameter(PARAMETER_IF_NONE_MATCH_ANY_ONLY, putIfNoneMatchParameter);

        // READ-operation IF-NONE-MATCH cannot have * value
        final HeaderParameter readIfNoneMatchParameter = new HeaderParameter();
        readIfNoneMatchParameter.setName(PARAMETER_IF_NONE_MATCH);
        readIfNoneMatchParameter.setType("string");
        swagger.addParameter(PARAMETER_IF_NONE_MATCH_REV_ONLY, readIfNoneMatchParameter);

        // IF-MATCH
        final HeaderParameter ifMatchParameter = new HeaderParameter();
        ifMatchParameter.setName(PARAMETER_IF_MATCH);
        ifMatchParameter.setType("string");
        ifMatchParameter.setDefault("*");
        swagger.addParameter(ifMatchParameter.getName(), ifMatchParameter);
    }

    /**
     * Traverse CREST API Descriptor paths, to build the Swagger model.
     *
     * @param paths CREST paths
     * @param services CREST services
     * @param errors Global error definitions
     * @param swagger Swagger model
     */
    private void buildPaths(final Paths paths, final Services services, final Errors errors, final Swagger swagger,
            final ReferenceResolver referenceResolver) {
        if (paths != null) {
            final Map<String, Path> pathMap = new LinkedHashMap<>();
            final List<String> pathNames = new ArrayList<>(paths.getNames());
            Collections.sort(pathNames);
            for (final String pathName : pathNames) {
                final VersionedPath versionedPath = paths.get(pathName);
                final List<Version> versions = new ArrayList<>(versionedPath.getVersions());
                Collections.sort(versions);
                for (final Version version : versions) {
                    final String versionName;
                    if (VersionedPath.UNVERSIONED.equals(version)) {
                        // resource is unversioned
                        versionName = EMPTY_STRING;
                    } else {
                        // versionName is start of URL-fragment for path (e.g., /myPath#1.0)
                        versionName = version.toString();
                    }

                    Resource resource = versionedPath.get(version);
                    if (resource.getReference() != null) {
                        resource = referenceResolver.getService(resource.getReference());
                    }
                    buildResourcePaths(resource, pathName, null, versionName, services, errors,
                            Collections.<Parameter>emptyList(), pathMap, swagger, referenceResolver);
                }
            }
            swagger.setPaths(pathMap);
        }
    }

    /**
     * Constructs paths, for a given resource, and works with OpenAPI's current inability to overload paths for a
     * given REST operation (e.g., multiple {@code get} operations) by adding a URL-fragment {@code #} suffix
     * to the end of the path.
     *
     * @param resource CREST resource
     * @param pathName Resource path-name
     * @param parentTag Tag for grouping operations together by resource/version or {@code null} if there is no parent
     * @param resourceVersion Resource version-name or empty-string
     * @param services CREST services
     * @param errors Global error definitions
     * @param parameters CREST operation parameters
     * @param pathMap Output for OpenAPI paths that are constructed
     * @param swagger Swagger model
     */
    private void buildResourcePaths(final Resource resource, final String pathName, final String parentTag,
            final String resourceVersion, final Services services, final Errors errors,
            final List<Parameter> parameters, final Map<String, Path> pathMap, final Swagger swagger,
            final ReferenceResolver referenceResolver) {
        // always show version at end of paths, inside the URL fragment
        final boolean hasResourceVersion = !isEmpty(resourceVersion);
        final String pathNamespace = hasResourceVersion
                ? normalizeName(pathName, resourceVersion) : normalizeName(pathName);

        // group resource endpoints by tag
        String tag = parentTag;
        if (isEmpty(tag)) {
            tag = !isEmpty(resource.getTitle()) ? resource.getTitle() : pathName;
            if (hasResourceVersion) {
                tag += " v" + resourceVersion;
            }
            swagger.addTag(new Tag().name(tag));
        }

        Schema resourceSchema = null;
        if (resource.getResourceSchema() != null) {
            resourceSchema = resource.getResourceSchema();
        }

        // resource-parameters are inherited by operations, items, and subresources
        final List<Parameter> operationParameters = unmodifiableList(
                mergeParameters(new ArrayList<>(parameters), resource.getParameters()));

        // create Swagger operations from CREST operations
        buildCreate(resource, pathName, pathNamespace, tag, resourceVersion, resourceSchema, errors,
                operationParameters, pathMap, referenceResolver);
        buildRead(resource, pathName, pathNamespace, tag, resourceVersion, resourceSchema, errors,
                operationParameters, pathMap, referenceResolver);
        buildUpdate(resource, pathName, pathNamespace, tag, resourceVersion, resourceSchema, errors,
                operationParameters, pathMap, referenceResolver);
        buildDelete(resource, pathName, pathNamespace, tag, resourceVersion, resourceSchema, errors,
                operationParameters, pathMap, referenceResolver);
        buildPatch(resource, pathName, pathNamespace, tag, resourceVersion, resourceSchema, errors,
                operationParameters, pathMap, referenceResolver);
        buildActions(resource, pathName, pathNamespace, tag, resourceVersion, errors,
                operationParameters, pathMap, referenceResolver);
        buildQueries(resource, pathName, pathNamespace, tag, resourceVersion, resourceSchema, errors,
                operationParameters, pathMap, referenceResolver);

        // create collection-items and sub-resources
        buildItems(resource, pathName, tag, resourceVersion, services, errors, parameters, pathMap, swagger,
                referenceResolver);
        buildSubResources(resource, pathName, resourceVersion, services, errors, parameters, pathMap, swagger,
                referenceResolver);
    }

    /**
     * Builds {@link Resource} collection-items;
     *
     * @param resource CREST resource
     * @param pathName Resource path-name
     * @param parentTag Tag for grouping operations together by resource/version or {@code null} if there is no parent
     * @param resourceVersion Resource version-name or empty-string
     * @param services CREST services
     * @param errors Global error definitions
     * @param parameters CREST operation parameters
     * @param pathMap Output for OpenAPI paths that are constructed
     * @param swagger Swagger model
     */
    private void buildItems(final Resource resource, final String pathName, final String parentTag,
            final String resourceVersion, final Services services, final Errors errors,
            final List<Parameter> parameters, final Map<String, Path> pathMap, final Swagger swagger,
            final ReferenceResolver referenceResolver) {
        if (resource.getItems() != null) {
            Resource itemsResource = resource.getItems();
            if (itemsResource.getReference() != null) {
                itemsResource = referenceResolver.getService(itemsResource.getReference());
            }

            // assume there is an "id" path-parameter
            final List<Parameter> itemsParameters = mergeParameters(new ArrayList<>(parameters),
                    resource.getParameters());
            itemsParameters.add(Parameter.parameter()
                    .name("id")
                    .type("string")
                    .source(ParameterSource.PATH)
                    .required(true)
                    .build());

            final String itemsPath = buildPath(pathName, "/{id}");
            buildResourcePaths(itemsResource, itemsPath, parentTag, resourceVersion, services, errors,
                    unmodifiableList(itemsParameters), pathMap, swagger, referenceResolver);
        }
    }

    /**
     * Builds {@link Resource} sub-resources;
     *
     * @param resource CREST resource
     * @param pathName Resource path-name
     * @param resourceVersion Resource version-name or empty-string
     * @param services CREST services
     * @param errors Global error definitions
     * @param parameters CREST operation parameters
     * @param pathMap Output for OpenAPI paths that are constructed
     * @param swagger Swagger model
     */
    private void buildSubResources(final Resource resource, final String pathName,
            final String resourceVersion, final Services services, final Errors errors,
            final List<Parameter> parameters, final Map<String, Path> pathMap, final Swagger swagger,
            final ReferenceResolver referenceResolver) {
        if (resource.getSubresources() != null) {
            // recursively build sub-resources
            final SubResources subResources = resource.getSubresources();
            final List<String> subPathNames = new ArrayList<>(subResources.getNames());
            Collections.sort(subPathNames);
            for (final String name : subPathNames) {
                // create path-parameters, for any path-variables found in subPathName
                final List<Parameter> subresourcesParameters = mergeParameters(new ArrayList<>(parameters),
                        buildPathParameters(name));

                final String subPathName = buildPath(pathName, name);
                Resource subResource = subResources.get(name);
                if (subResource.getReference() != null) {
                    subResource = referenceResolver.getService(subResource.getReference());
                }
                buildResourcePaths(subResource, subPathName, null, resourceVersion, services, errors,
                        unmodifiableList(subresourcesParameters), pathMap, swagger, referenceResolver);
            }
        }
    }

    /**
     * Build create-operation.
     *
     * @param resource CREST resource
     * @param pathName Path-name, which is the actual HTTP path
     * @param pathNamespace Unique path-namespace
     * @param tag Tag for grouping operations together by resource/version
     * @param resourceVersion Resource version-name or empty-string
     * @param resourceSchema Resource schema or {@code null}
     * @param errors Global error definitions
     * @param parameters CREST operation parameters
     * @param pathMap Output for OpenAPI paths that are constructed
     */
    private void buildCreate(final Resource resource, final String pathName, final String pathNamespace,
            final String tag, final String resourceVersion, final Schema resourceSchema, final Errors errors,
            final List<Parameter> parameters, final Map<String, Path> pathMap,
            final ReferenceResolver referenceResolver) {
        if (resource.getCreate() != null) {
            final Create create = resource.getCreate();
            switch (create.getMode()) {
            case ID_FROM_CLIENT:
                final String createPutNamespace = normalizeName(pathNamespace, "create", "put");
                final String createPutPathFragment = normalizeName(resourceVersion, "create", "put");
                final Operation putOperation = buildOperation(create, createPutNamespace, resourceSchema,
                        resourceSchema, errors, parameters, referenceResolver);
                putOperation.setSummary("Create with Client-Assigned ID");

                if (resource.isMvccSupported()) {
                    putOperation.addParameter(new RefParameter(PARAMETER_IF_NONE_MATCH_ANY_ONLY));
                }

                addOperation(putOperation, "put", pathName, createPutPathFragment, resourceVersion, tag, pathMap);
                break;
            case ID_FROM_SERVER:
                final String createPostNamespace = normalizeName(pathNamespace, "create", "post");
                final String createPostPathFragment = normalizeName(resourceVersion, "create", "post");
                final Operation postOperation = buildOperation(create, createPostNamespace, resourceSchema,
                        resourceSchema, errors, parameters, referenceResolver);
                postOperation.setSummary("Create with Server-Assigned ID");

                addOperation(postOperation, "post", pathName, createPostPathFragment, resourceVersion, tag,
                        pathMap);
                break;
            default:
                throw new TransformerException("Unsupported CreateMode: " + create.getMode());
            }
        }
    }

    /**
     * Build read-operation.
     *
     * @param resource CREST resource
     * @param pathName Path-name, which is the actual HTTP path
     * @param pathNamespace Unique path-namespace
     * @param tag Tag for grouping operations together by resource/version
     * @param resourceVersion Resource version-name or empty-string
     * @param resourceSchema Resource schema or {@code null}
     * @param errors Global error definitions
     * @param parameters CREST operation parameters
     * @param pathMap Output for OpenAPI paths that are constructed
     */
    private void buildRead(final Resource resource, final String pathName, final String pathNamespace,
            final String tag, final String resourceVersion, final Schema resourceSchema, final Errors errors,
            final List<Parameter> parameters, final Map<String, Path> pathMap,
            final ReferenceResolver referenceResolver) {
        if (resource.getRead() != null) {
            final String operationNamespace = normalizeName(pathNamespace, "read");
            final String operationPathFragment = normalizeName(resourceVersion, "read");
            final Read read = resource.getRead();

            final Operation operation = buildOperation(read, operationNamespace, null, resourceSchema, errors,
                    parameters, referenceResolver);
            operation.setSummary("Read");
            operation.addParameter(new RefParameter(PARAMETER_MIME_TYPE));

            if (resource.isMvccSupported()) {
                operation.addParameter(new RefParameter(PARAMETER_IF_NONE_MATCH_REV_ONLY));
            }

            addOperation(operation, "get", pathName, operationPathFragment, resourceVersion, tag, pathMap);
        }
    }

    /**
     * Build update-operation.
     *
     * @param resource CREST resource
     * @param pathName Path-name, which is the actual HTTP path
     * @param pathNamespace Unique path-namespace
     * @param tag Tag for grouping operations together by resource/version
     * @param resourceVersion Resource version-name or empty-string
     * @param resourceSchema Resource schema or {@code null}
     * @param errors Global error definitions
     * @param parameters CREST operation parameters
     * @param pathMap Output for OpenAPI paths that are constructed
     */
    private void buildUpdate(final Resource resource, final String pathName, final String pathNamespace,
            final String tag, final String resourceVersion, final Schema resourceSchema, final Errors errors,
            final List<Parameter> parameters, final Map<String, Path> pathMap,
            final ReferenceResolver referenceResolver) {
        if (resource.getUpdate() != null) {
            final String operationNamespace = normalizeName(pathNamespace, "update");
            final String operationPathFragment = normalizeName(resourceVersion, "update");
            final Update update = resource.getUpdate();

            final Operation operation = buildOperation(update, operationNamespace, resourceSchema, resourceSchema,
                    errors, parameters, referenceResolver);
            operation.setSummary("Update");

            if (resource.isMvccSupported()) {
                operation.addParameter(new RefParameter(PARAMETER_IF_MATCH));
            }

            addOperation(operation, "put", pathName, operationPathFragment, resourceVersion, tag, pathMap);
        }
    }

    /**
     * Build delete-operation.
     *
     * @param resource CREST resource
     * @param pathName Path-name, which is the actual HTTP path
     * @param pathNamespace Unique path-namespace
     * @param tag Tag for grouping operations together by resource/version
     * @param resourceVersion Resource version-name or empty-string
     * @param resourceSchema Resource schema or {@code null}
     * @param errors Global error definitions
     * @param parameters CREST operation parameters
     * @param pathMap Output for OpenAPI paths that are constructed
     */
    private void buildDelete(final Resource resource, final String pathName, final String pathNamespace,
            final String tag, final String resourceVersion, final Schema resourceSchema, final Errors errors,
            final List<Parameter> parameters, final Map<String, Path> pathMap,
            final ReferenceResolver referenceResolver) {
        if (resource.getDelete() != null) {
            final String operationNamespace = normalizeName(pathNamespace, "delete");
            final String operationPathFragment = normalizeName(resourceVersion, "delete");
            final Delete delete = resource.getDelete();

            final Operation operation = buildOperation(delete, operationNamespace, null, resourceSchema, errors,
                    parameters, referenceResolver);
            operation.setSummary("Delete");

            if (resource.isMvccSupported()) {
                operation.addParameter(new RefParameter(PARAMETER_IF_MATCH));
            }

            addOperation(operation, "delete", pathName, operationPathFragment, resourceVersion, tag, pathMap);
        }
    }

    /**
     * Build patch-operation.
     *
     * @param resource CREST resource
     * @param pathName Path-name, which is the actual HTTP path
     * @param pathNamespace Unique path-namespace
     * @param tag Tag for grouping operations together by resource/version
     * @param resourceVersion Resource version-name or empty-string
     * @param resourceSchema Resource schema or {@code null}
     * @param errors Global error definitions
     * @param parameters CREST operation parameters
     * @param pathMap Output for OpenAPI paths that are constructed
     */
    private void buildPatch(final Resource resource, final String pathName, final String pathNamespace,
            final String tag, final String resourceVersion, final Schema resourceSchema, final Errors errors,
            final List<Parameter> parameters, final Map<String, Path> pathMap,
            final ReferenceResolver referenceResolver) {
        if (resource.getPatch() != null) {
            final String operationNamespace = normalizeName(pathNamespace, "patch");
            final String operationPathFragment = normalizeName(resourceVersion, "patch");
            final Patch patch = resource.getPatch();

            final Schema requestSchema = buildPatchRequestPayload(patch.getOperations());
            final Operation operation = buildOperation(patch, operationNamespace, requestSchema, resourceSchema,
                    errors, parameters, referenceResolver);
            operation.setSummary("Update via Patch Operations");

            if (resource.isMvccSupported()) {
                operation.addParameter(new RefParameter(PARAMETER_IF_MATCH));
            }

            addOperation(operation, "patch", pathName, operationPathFragment, resourceVersion, tag, pathMap);
        }
    }

    /**
     * Build action-operations.
     *
     * @param resource CREST resource
     * @param pathName Path-name, which is the actual HTTP path
     * @param pathNamespace Unique path-namespace
     * @param tag Tag for grouping operations together by resource/version
     * @param resourceVersion Resource version-name or empty-string
     * @param errors Global error definitions
     * @param parameters CREST operation parameters
     * @param pathMap Output for OpenAPI paths that are constructed
     */
    private void buildActions(final Resource resource, final String pathName, final String pathNamespace,
            final String tag, final String resourceVersion, final Errors errors,
            final List<Parameter> parameters, final Map<String, Path> pathMap,
            final ReferenceResolver referenceResolver) {
        if (!isEmpty(resource.getActions())) {
            final String operationNamespace = normalizeName(pathNamespace, "action");
            final String operationPathFragment = normalizeName(resourceVersion, "action");
            for (final Action action : resource.getActions()) {
                final String actionNamespace = normalizeName(operationNamespace, action.getName());
                final String actionPathFragment = normalizeName(operationPathFragment, action.getName());

                final Operation operation = buildOperation(action, actionNamespace, action.getRequest(),
                        action.getResponse(), errors, parameters, referenceResolver);
                operation.setSummary("Action: " + action.getName());

                final QueryParameter actionParameter = new QueryParameter();
                actionParameter.setName("_action");
                actionParameter.setType("string");
                actionParameter.setEnum(Arrays.asList(action.getName()));
                actionParameter.setRequired(true);
                operation.addParameter(actionParameter);

                addOperation(operation, "post", pathName, actionPathFragment, resourceVersion, tag, pathMap);
            }
        }
    }

    /**
     * Build query-operations.
     *
     * @param resource CREST resource
     * @param pathName Path-name, which is the actual HTTP path
     * @param pathNamespace Unique path-namespace
     * @param tag Tag for grouping operations together by resource/version
     * @param resourceVersion Resource version-name or empty-string
     * @param resourceSchema Resource schema or {@code null}
     * @param errors Global error definitions
     * @param parameters CREST operation parameters
     * @param pathMap Output for OpenAPI paths that are constructed
     */
    private void buildQueries(final Resource resource, final String pathName, final String pathNamespace,
            final String tag, final String resourceVersion, final Schema resourceSchema, final Errors errors,
            final List<Parameter> parameters, final Map<String, Path> pathMap,
            final ReferenceResolver referenceResolver) {
        if (!isEmpty(resource.getQueries())) {
            final String operationNamespace = normalizeName(pathNamespace, "query");
            final String operationPathFragment = normalizeName(resourceVersion, "query");
            for (final Query query : resource.getQueries()) {
                final String queryNamespace;
                final String queryPathFragment;
                final String summary;
                final QueryParameter queryParameter;
                switch (query.getType()) {
                case ID:
                    queryNamespace = normalizeName(operationNamespace, "id", query.getQueryId());
                    queryPathFragment = normalizeName(operationPathFragment, "id", query.getQueryId());
                    summary = "Query by ID: " + query.getQueryId();

                    queryParameter = new QueryParameter();
                    queryParameter.setName("_queryId");
                    queryParameter.setType("string");
                    queryParameter.setEnum(Arrays.asList(query.getQueryId()));
                    queryParameter.setRequired(true);
                    break;
                case FILTER:
                    queryNamespace = normalizeName(operationNamespace, "filter");
                    queryPathFragment = normalizeName(operationPathFragment, "filter");
                    summary = "Query by Filter";

                    queryParameter = new QueryParameter();
                    queryParameter.setName("_queryFilter");
                    queryParameter.setType("string");
                    queryParameter.setRequired(true);
                    break;
                case EXPRESSION:
                    queryNamespace = normalizeName(operationNamespace, "expression");
                    queryPathFragment = normalizeName(operationPathFragment, "expression");
                    summary = "Query by Expression";

                    queryParameter = new QueryParameter();
                    queryParameter.setName("_queryExpression");
                    queryParameter.setType("string");
                    queryParameter.setRequired(true);
                    break;
                default:
                    throw new TransformerException("Unsupported QueryType: " + query.getType());
                }

                final Schema responsePayload;
                if (resourceSchema.getSchema() != null
                        && !"array".equals(resourceSchema.getSchema().get("type").asString())) {
                    // make query-response schema an array of values
                    responsePayload = Schema.schema().schema(
                            json(object(
                                    field("type", "array"),
                                    field("items", resourceSchema.getSchema())
                            ))).build();
                } else {
                    // already an array or a reference (TODO might not be an array)
                    responsePayload = resourceSchema;
                }

                final Operation operation = buildOperation(query, queryNamespace, null, responsePayload, errors,
                        parameters, referenceResolver);
                operation.setSummary(summary);
                operation.addParameter(queryParameter);

                final QueryParameter pageSizeParamter = new QueryParameter();
                pageSizeParamter.setName("_pageSize");
                pageSizeParamter.setType("integer");
                operation.addParameter(pageSizeParamter);

                if (query.getPagingMode() != null) {
                    for (final PagingMode pagingMode : query.getPagingMode()) {
                        final QueryParameter parameter = new QueryParameter();
                        switch (pagingMode) {
                        case COOKIE:
                            parameter.setName("_pagedResultsCookie");
                            parameter.setType("string");
                            break;
                        case OFFSET:
                            parameter.setName("_pagedResultsOffset");
                            parameter.setType("integer");
                            break;
                        default:
                            throw new TransformerException("Unsupported PagingMode: " + pagingMode);
                        }
                        operation.addParameter(parameter);
                    }
                }

                final QueryParameter totalPagedResultsPolicyParameter = new QueryParameter();
                totalPagedResultsPolicyParameter.setName("_totalPagedResultsPolicy");
                totalPagedResultsPolicyParameter.setType("string");
                final List<String> totalPagedResultsPolicyValues = new ArrayList<>();
                if (query.getCountPolicies() != null) {
                    for (final CountPolicy countPolicy : query.getCountPolicies()) {
                        totalPagedResultsPolicyValues.add(countPolicy.name());
                    }
                } else {
                    totalPagedResultsPolicyValues.add("NONE");
                }
                totalPagedResultsPolicyParameter._enum(totalPagedResultsPolicyValues);
                operation.addParameter(totalPagedResultsPolicyParameter);

                if (query.getType() != QueryType.ID) {
                    // _sortKeys parameter is not supported for ID queries
                    final QueryParameter sortKeysParameter = new QueryParameter();
                    sortKeysParameter.setName("_sortKeys");
                    sortKeysParameter.setType("string");
                    if (!isEmpty(query.getSupportedSortKeys())) {
                        sortKeysParameter.setEnum(Arrays.asList(query.getSupportedSortKeys()));
                    }
                    operation.addParameter(sortKeysParameter);
                }

                addOperation(operation, "get", pathName, queryPathFragment, resourceVersion, tag, pathMap);
            }
        }
    }

    /**
     * Builds a Swagger operation.
     *
     * @param operationModel CREST operation
     * @param operationNamespace Unique operation-namespace
     * @param requestPayload Request payload or {@code null}
     * @param responsePayload Response payload
     * @param errors Global error definitions
     * @param parameters CREST operation parameters
     * @return Swagger operation
     */
    private Operation buildOperation(final org.forgerock.api.models.Operation operationModel,
            final String operationNamespace, final Schema requestPayload, final Schema responsePayload,
            final Errors errors, final List<Parameter> parameters, final ReferenceResolver referenceResolver) {
        final Operation operation = new Operation();
        operation.setOperationId(operationNamespace);
        applyOperationDescription(operationModel.getDescription(), operation);
        applyOperationStability(operationModel.getStability(), operation);
        applyOperationParameters(mergeParameters(new ArrayList<>(parameters), operationModel.getParameters()),
                operation);
        applyOperationRequestPayload(requestPayload, operation);
        applyOperationResponsePayloads(responsePayload, operationModel.getErrors(), errors, operation,
                referenceResolver);
        return operation;
    }

    /**
     * Adds an OpenAPI {@code Operation} to a given path, and handles OpenAPI's inability to overload paths/operations
     * by adding a URL-fragment to the path when necessary.
     *
     * @param operation OpenAPI operation
     * @param method HTTP method (e.g., get, post, etc.)
     * @param pathName Path name
     * @param pathFragment Unique path-fragment, for overloading paths
     * @param resourceVersion Resource version-name or empty-string
     * @param tag Tag used to group OpenAPI operations or {@code null}
     * @param pathMap Path map
     */
    private void addOperation(final Operation operation, final String method, final String pathName,
            final String pathFragment, final String resourceVersion, final String tag,
            final Map<String, Path> pathMap) {
        boolean showPathFragment = false;
        if (!isEmpty(resourceVersion)) {
            showPathFragment = true;
            operation.setVendorExtension("x-resourceVersion", resourceVersion);
        }
        if (!isEmpty(tag)) {
            operation.addTag(tag);
        }

        Path operationPath = pathMap.get(pathName);
        if (operationPath == null) {
            operationPath = new Path();
        } else if (!showPathFragment) {
            // path already exists, so make sure it is unique
            switch (method) {
            case "get":
                showPathFragment = operationPath.getGet() != null;
                break;
            case "post":
                showPathFragment = operationPath.getPost() != null;
                break;
            case "put":
                showPathFragment = operationPath.getPut() != null;
                break;
            case "delete":
                showPathFragment = operationPath.getDelete() != null;
                break;
            case "patch":
                showPathFragment = operationPath.getPatch() != null;
                break;
            default:
                throw new TransformerException("Unsupported method: " + method);
            }
        }

        if (showPathFragment) {
            // create a unique path by adding a URL-fragment at end
            if (pathName.indexOf('#') != -1) {
                throw new TransformerException("pathName cannot contain # character");
            }
            final String uniquePath = pathName + '#' + pathFragment;
            if (pathMap.containsKey(uniquePath)) {
                throw new TransformerException("pathFragment is not unique for given pathName");
            }
            operationPath = new Path();
            pathMap.put(uniquePath, operationPath);
        } else {
            pathMap.put(pathName, operationPath);
        }

        if (operationPath.set(method, operation) == null) {
            throw new TransformerException("Unsupported method: " + method);
        }
    }

    /**
     * Adds a description to a Swagger operation.
     *
     * @param description Operation description or {@code null}
     * @param operation Swagger operation
     */
    private void applyOperationDescription(final String description, final Operation operation) {
        if (!isEmpty(description)) {
            operation.setDescription(description);
        }
    }

    /**
     * Marks a Swagger operation as <em>deprecated</em> when CREST operation is deprecated or removed.
     *
     * @param stability CREST operation stability or {@code null}
     * @param operation Swagger operation
     */
    private void applyOperationStability(final Stability stability, final Operation operation) {
        if (stability == Stability.DEPRECATED || stability == Stability.REMOVED) {
            operation.setDeprecated(TRUE);
        }
    }

    /**
     * Converts CREST operation parameters (e.g., path variables, query fields) into Swagger operation parameters.
     * <p>
     * This method assumes at {@link org.forgerock.api.enums.ParameterSource#ADDITIONAL} parameters are
     * query-parameters, which would need to be changed with post-processing if, for example, they should be HTTP
     * headers.
     * </p>
     *
     * @param parameters CREST operation parameters
     * @param operation Swagger operation
     */
    private void applyOperationParameters(final List<Parameter> parameters, final Operation operation) {
        if (!parameters.isEmpty()) {
            for (final Parameter parameter : parameters) {
                final SerializableParameter operationParameter;
                // NOTE: request-payload BodyParameter is applied elsewhere
                switch (parameter.getSource()) {
                case PATH:
                    operationParameter = new PathParameter();
                    break;
                case ADDITIONAL:
                    // we assume that additional parameters are query-parameters, which would need to be changed
                    // with post-processing if, for example, they should be HTTP headers
                    operationParameter = new QueryParameter();
                    break;
                default:
                    throw new TransformerException("Unsupported ParameterSource: " + parameter.getSource());
                }
                operationParameter.setName(parameter.getName());
                operationParameter.setType(parameter.getType());
                operationParameter.setDescription(parameter.getDescription());
                operationParameter.setRequired(parameter.isRequired());
                if (!isEmpty(parameter.getEnumValues())) {
                    operationParameter.setEnum(Arrays.asList(parameter.getEnumValues()));

                    if (!isEmpty(parameter.getEnumTitles())) {
                        // enum_titles only provided with enum values
                        operationParameter.getVendorExtensions().put("x-enum_titles",
                                Arrays.asList(parameter.getEnumTitles()));
                    }
                }

                // TODO schema related fields in SerializableParameter

                operation.addParameter(operationParameter);
            }
        }

        // apply common parameters
        operation.addParameter(new RefParameter(PARAMETER_FIELDS));
        operation.addParameter(new RefParameter(PARAMETER_PRETTY_PRINT));
    }

    /**
     * Defines a request-payload for a Swagger operation.
     *
     * @param schema JSON Schema or {@code null}
     * @param operation Swagger operation
     */
    private void applyOperationRequestPayload(final Schema schema, final Operation operation) {
        if (schema != null) {
            final Model model;
            if (schema.getSchema() != null) {
                model = buildModel(schema.getSchema());
            } else {
                final String ref = getDefinitionsReference(schema.getReference());
                if (ref == null) {
                    throw new TransformerException("Invalid JSON ref");
                }
                model = new RefModel(ref);
            }
            final BodyParameter parameter = new BodyParameter();
            parameter.setName("requestPayload");
            parameter.setSchema(model);
            parameter.setRequired(true);
            operation.addParameter(parameter);
        }
    }

    /**
     * Defines response-payloads, which may be a combination of success and error responses, for a Swagger operation.
     *
     * @param schema Success-response JSON schema
     * @param errorResponses Error responses
     * @param errors Global error definitions
     * @param operation Swagger operation
     */
    private void applyOperationResponsePayloads(final Schema schema, final Error[] errorResponses, final Errors errors,
            final Operation operation, final ReferenceResolver referenceResolver) {
        final Map<String, Response> responses = new HashMap<>();
        if (schema != null) {
            final Response response = new Response();
            response.description("Success");
            if (schema.getSchema() != null) {
                if ("array".equals(schema.getSchema().get("type").asString())) {
                    response.setSchema(buildProperty(schema.getSchema()));
                } else {
                    final ObjectProperty property = new ObjectProperty();
                    property.setProperties(buildProperties(schema.getSchema()));
                    response.schema(property);
                }
            } else {
                final String ref = getDefinitionsReference(schema.getReference());
                if (ref == null) {
                    throw new TransformerException("Invalid JSON ref");
                }
                response.schema(new RefProperty(ref));
            }
            responses.put("200", response);
        }

        if (!isEmpty(errorResponses)) {
            // sort by error codes, so that same-codes can be merged together, because Swagger cannot overload codes
            Arrays.sort(errorResponses, Error.ERROR_COMPARATOR);
            final int n = errorResponses.length;
            for (int i = 0; i < n; ++i) {
                final Error error;
                if (errorResponses[i].getReference() != null) {
                    if (errors == null) {
                        throw new TransformerException("Global error definitions not provided for error reference");
                    }
                    error = referenceResolver.getError(errorResponses[i].getReference());
                    if (error == null) {
                        throw new TransformerException("Error reference not found in global error definitions");
                    }
                } else {
                    error = errorResponses[i];
                }

                // for a given error-code, create a bulleted-list of descriptions, if there is more than one to merge
                final int code = error.getCode();
                final List<String> descriptions = new ArrayList<>();
                if (error.getDescription() != null) {
                    descriptions.add(error.getDescription());
                }
                for (int k = i + 1; k < n; ++k) {
                    if (errorResponses[k].getCode() == code) {
                        // TODO build composite schema with detailsSchema??? errors[k].getSchema();
                        if (errorResponses[k].getDescription() != null) {
                            descriptions.add(errorResponses[k].getDescription());
                        }
                        ++i;
                    }
                }

                final String description;
                if (descriptions.isEmpty()) {
                    description = null;
                } else if (descriptions.size() == 1) {
                    description = descriptions.get(0);
                } else {
                    // Create a bulleted-list using single-asterisk, as supported by GitHub Flavored Markdown
                    final AsciiDoc bulletedList = AsciiDoc.asciiDoc();
                    for (final String listItem : descriptions) {
                        bulletedList.unorderedList1(listItem);
                    }
                    description = bulletedList.toString();
                }

                Object errorCause = null;
                if (error.getSchema() != null && error.getSchema().getSchema() != null) {
                    // TODO support detailsSchema reference
                    errorCause = error.getSchema().getSchema();
                }

                final JsonValue errorJsonSchema = json(object(
                        field("type", "object"),
                        field("required", array("code", "message")),
                        field("properties", object(
                                field("code", object(
                                        field("type", "integer"),
                                        field("title", "Code"),
                                        field("description", "3-digit error code, corresponding to HTTP status codes.")
                                )),
                                field("message", object(
                                        field("type", "string"),
                                        field("title", "Message"),
                                        field("description", "Error message.")
                                )),
                                field("reason", object(
                                        field("type", "string"),
                                        field("title", "Reason"),
                                        field("description", "Short description corresponding to error code.")
                                )),
                                field("detail", object(
                                        field("type", "string"),
                                        field("title", "Detail"),
                                        field("description", "Detailed error message.")
                                )),
                                fieldIfNotNull("cause", errorCause)
                        ))
                ));

                final Response response = new Response();
                response.description(description);
                final ObjectProperty property = new ObjectProperty();
                property.setProperties(buildProperties(errorJsonSchema));
                response.schema(property);
                responses.put(String.valueOf(code), response);
            }
        }
        operation.setResponses(responses);
    }

    /**
     * Builds a request-payload for a patch-operation.
     *
     * @param patchOperations Supported CREST path-operations
     * @return JSON schema for request-payload
     */
    @VisibleForTesting
    protected Schema buildPatchRequestPayload(final PatchOperation[] patchOperations) {
        // see org.forgerock.json.resource.PatchOperation#PatchOperation
        final List<Object> enumArray = new ArrayList<>(patchOperations.length);
        for (final PatchOperation op : patchOperations) {
            enumArray.add(op.name().toLowerCase(Locale.ROOT));
        }
        final JsonValue schemaValue = json(object(
                field("type", "array"),
                field("items", object(
                        field("type", "object"),
                        field("properties", object(
                                field("operation", object(
                                        field("type", "string"),
                                        field("enum", enumArray),
                                        field("required", true)
                                )),
                                field("field", object(field("type", "string"))),
                                field("from", object(field("type", "string"))),
                                field("value", object(field("type", "string")))
                        ))
                ))
        ));
        return Schema.schema().schema(schemaValue).build();
    }

    /**
     * Builds Swagger info-model, which describes the API (e.g., title, version, description).
     *
     * @param title API title
     * @param apiDescription API description model
     * @return Info model
     */
    @VisibleForTesting
    protected Info buildInfo(final String title, final ApiDescription apiDescription) {
        // TODO set other Info fields
        final Info info = new Info();
        info.setTitle(checkNotNull(title));
        info.setVersion(apiDescription.getVersion());
        info.description(apiDescription.getDescription());
        return info;
    }

    /**
     * Converts global CREST schema definitions into glabal Swagger schema definitions.
     *
     * @param definitions Global CREST schema-definitions
     * @param swagger Swagger model
     */
    @VisibleForTesting
    protected void buildDefinitions(final Definitions definitions, final Swagger swagger) {
        if (definitions != null) {
            final Map<String, Model> definitionMap = new HashMap<>();

            // named schema definitions
            final Set<String> definitionNames = definitions.getNames();
            for (final String name : definitionNames) {
                final Schema schema = definitions.get(name);
                if (schema.getSchema() != null) {
                    definitionMap.put(name, buildModel(schema.getSchema()));
                }
            }
            swagger.setDefinitions(definitionMap);
        }
    }

    /**
     * Converts a JSON schema into the appropriate Swagger model (e.g., object, array, string, integer, etc.).
     *
     * @param schema JSON schema
     * @return Swagger schema model
     */
    @VisibleForTesting
    protected Model buildModel(final JsonValue schema) {
        final ModelImpl model;
        final String type = schema.get("type").asString();
        switch (type) {
        case "object":
            model = new ModelImpl().type(type);
            model.setProperties(buildProperties(schema));
            final List<String> required = getArrayOfJsonString("required", schema);
            if (!required.isEmpty()) {
                model.setRequired(required);
            }
            break;
        case "array":
            return buildArrayModel(schema);
        case "null":
            return new ModelImpl().type(type);
        case "boolean":
        case "integer":
        case "number":
        case "string":
            model = new ModelImpl().type(type);
            if (schema.get("default").isNotNull()) {
                model.setDefaultValue(schema.get("default").asString());
            }

            final List<String> enumValues = getArrayOfJsonString("enum", schema);
            if (!enumValues.isEmpty()) {
                model.setEnum(enumValues);

                // enum_titles only provided with enum values
                final JsonValue options = schema.get("options");
                if (options.isNotNull()) {
                    final List<String> enumTitles = getArrayOfJsonString("enum_titles", options);
                    if (!enumTitles.isEmpty()) {
                        model.setVendorExtension("x-enum_titles", enumTitles);
                    }
                }
            }

            if (schema.get("format").isNotNull()) {
                // https://github.com/OAI/OpenAPI-Specification/blob/master/versions/2.0.md#dataTypeFormat
                model.setFormat(schema.get("format").asString());
                if ("full-date".equals(model.getFormat()) && "string".equals(type)) {
                    // Swagger normalizes full-date to date format
                    model.setFormat("date");
                }
            }
            break;
        default:
            throw new TransformerException("Unsupported JSON schema type: " + type);
        }

        model.setTitle(schema.get("title").asString());
        model.setDescription(schema.get("description").asString());

        // TODO reference
        // TODO external-docs URLs
        // TODO discriminator (see openapi spec and https://gist.github.com/leedm777/5730877)

        return model;
    }

    /**
     * Converts a JSON schema, representing an array-type, into a Swagger array-model.
     *
     * @param schema JSON schema
     * @return Swagger array-schema model
     */
    private Model buildArrayModel(final JsonValue schema) {
        final ArrayModel model = new ArrayModel();
        model.setTitle(schema.get("title").asString());
        model.setDescription(schema.get("description").asString());
        model.setProperties(buildProperties(schema));
        model.setItems(buildProperty(schema.get("items")));

        // TODO reference
        // TODO external-docs URLs

        return model;
    }

    /**
     * Convert JSON schema-properties into a Swagger named-properties map, where the key is the JSON field and
     * the value is the JSON schema for that field.
     *
     * @param schema JSON schema containing a <em>properties</em> field
     * @return Swagger named-properties map
     */
    @VisibleForTesting
    protected Map<String, Property> buildProperties(final JsonValue schema) {
        if (schema != null && schema.isNotNull()) {
            final JsonValue properties = schema.get("properties");
            if (properties.isNotNull()) {
                final Map<String, Object> propertiesMap = properties.asMap();
                final Map<String, Property> resultMap = new LinkedHashMap<>(propertiesMap.size() * 2);

                boolean sortByPropertyOrder = false;
                for (final Map.Entry<String, Object> entry : propertiesMap.entrySet()) {
                    final Property property = buildProperty(json(entry.getValue()));
                    if (!sortByPropertyOrder && property.getVendorExtensions().containsKey("x-propertyOrder")) {
                        sortByPropertyOrder = true;
                    }
                    resultMap.put(entry.getKey(), property);
                }

                if (sortByPropertyOrder && resultMap.size() > 1) {
                    // sort by x-propertyOrder vendor extension
                    final List<Map.Entry<String, Property>> entries = new ArrayList<>(resultMap.entrySet());
                    Collections.sort(entries, new Comparator<Map.Entry<String, Property>>() {
                        @Override
                        public int compare(final Map.Entry<String, Property> o1, final Map.Entry<String, Property> o2) {
                            // null values appear at end after sorting
                            final Integer v1 = (Integer) o1.getValue().getVendorExtensions().get("x-propertyOrder");
                            final Integer v2 = (Integer) o2.getValue().getVendorExtensions().get("x-propertyOrder");
                            if (v1 != null) {
                                if (v2 != null) {
                                    return v1.compareTo(v2);
                                }
                                return -1;
                            }
                            if (v2 != null) {
                                return 1;
                            }
                            return 0;
                        }
                    });

                    final Map<String, Property> sortedMap = new LinkedHashMap<>(propertiesMap.size() * 2);
                    for (final Map.Entry<String, Property> entry : entries) {
                        sortedMap.put(entry.getKey(), entry.getValue());
                    }
                    return sortedMap;
                } else {
                    return resultMap;
                }
            }
        }
        return null;
    }

    /**
     * Builds a Swagger property representing a JSON Schema definition, where custom JSON Schema extensions are
     * added as Swagger vendor-extensions.
     *
     * @param schema JSON Schema
     * @return Swagger property representing the JSON Schema
     */
    @VisibleForTesting
    protected Property buildProperty(final JsonValue schema) {
        if (schema == null || schema.isNull()) {
            return null;
        }

        if (schema.get("$ref").isNotNull()) {
            final String ref = getDefinitionsReference(schema.get("$ref").asString());
            if (ref == null) {
                throw new TransformerException("Invalid JSON ref: " + schema.get("$ref").asString());
            }
            return new RefProperty(ref);
        }

        // https://github.com/OAI/OpenAPI-Specification/blob/master/versions/2.0.md#dataTypeFormat
        final String format = schema.get("format").asString();

        final AbstractProperty abstractProperty;
        final String type = schema.get("type").asString();
        switch (type) {
        case "object": {
            // TODO there is a MapProperty type, but I am not sure how it is useful
            final ObjectProperty property = new ObjectProperty();
            property.setProperties(buildProperties(schema));
            property.setRequiredProperties(getArrayOfJsonString("required", schema));
            abstractProperty = property;
            break;
        }
        case "array": {
            final ArrayProperty property = new ArrayProperty();
            property.setItems(buildProperty(schema.get("items")));
            property.setMinItems(schema.get("minItems").asInteger());
            property.setMaxItems(schema.get("maxItems").asInteger());
            property.setUniqueItems(schema.get("uniqueItems").asBoolean());
            abstractProperty = property;
            break;
        }
        case "boolean":
            abstractProperty = new BooleanProperty();
            break;
        case "integer": {
            final AbstractNumericProperty property;
            if ("int64".equals(format)) {
                property = new LongProperty();
            } else {
                property = new IntegerProperty();
            }
            property.setMinimum(schema.get("minimum").asDouble());
            property.setMaximum(schema.get("maximum").asDouble());
            property.setExclusiveMinimum(schema.get("exclusiveMinimum").asBoolean());
            property.setExclusiveMaximum(schema.get("exclusiveMaximum").asBoolean());
            abstractProperty = property;
            break;
        }
        case "number": {
            final AbstractNumericProperty property;
            if (isEmpty(format)) {
                // ambiguous
                property = new DoubleProperty();
            } else {
                switch (format) {
                case "int32":
                    property = new IntegerProperty();
                    break;
                case "int64":
                    property = new LongProperty();
                    break;
                case "float":
                    property = new FloatProperty();
                    break;
                case "double":
                default:
                    property = new DoubleProperty();
                    break;
                }
            }
            property.setMinimum(schema.get("minimum").asDouble());
            property.setMaximum(schema.get("maximum").asDouble());
            property.setExclusiveMinimum(schema.get("exclusiveMinimum").asBoolean());
            property.setExclusiveMaximum(schema.get("exclusiveMaximum").asBoolean());
            abstractProperty = property;
            break;
        }
        case "null":
            return null;
        case "string": {
            if (isEmpty(format)) {
                final StringProperty property = new StringProperty();
                property.setMinLength(schema.get("minLength").asInteger());
                property.setMaxLength(schema.get("maxLength").asInteger());
                property.setPattern(schema.get("pattern").asString());
                abstractProperty = property;
            } else {
                switch (format) {
                case "byte":
                    abstractProperty = new ByteArrayProperty();
                    break;
                case "binary": {
                    final BinaryProperty property = new BinaryProperty();
                    property.setMinLength(schema.get("minLength").asInteger());
                    property.setMaxLength(schema.get("maxLength").asInteger());
                    property.setPattern(schema.get("pattern").asString());
                    abstractProperty = property;
                    break;
                }
                case "date":
                case "full-date":
                    abstractProperty = new DateProperty();
                    break;
                case "date-time":
                    abstractProperty = new DateTimeProperty();
                    break;
                case "password": {
                    final PasswordProperty property = new PasswordProperty();
                    property.setMinLength(schema.get("minLength").asInteger());
                    property.setMaxLength(schema.get("maxLength").asInteger());
                    property.setPattern(schema.get("pattern").asString());
                    abstractProperty = property;
                    break;
                }
                case "uuid": {
                    final UUIDProperty property = new UUIDProperty();
                    property.setMinLength(schema.get("minLength").asInteger());
                    property.setMaxLength(schema.get("maxLength").asInteger());
                    property.setPattern(schema.get("pattern").asString());
                    abstractProperty = property;
                    break;
                }
                default: {
                    final StringProperty property = new StringProperty();
                    property.setMinLength(schema.get("minLength").asInteger());
                    property.setMaxLength(schema.get("maxLength").asInteger());
                    property.setPattern(schema.get("pattern").asString());
                    abstractProperty = property;
                    break;
                }
                }
            }
            break;
        }
        default:
            throw new TransformerException("Unsupported JSON schema type: " + type);
        }

        if (!isEmpty(format)) {
            abstractProperty.setFormat(format);
        }
        if (schema.get("default").isNotNull()) {
            abstractProperty.setDefault(schema.get("default").asString());
        }
        abstractProperty.setTitle(schema.get("title").asString());
        abstractProperty.setDescription(schema.get("description").asString());

        final String readPolicy = schema.get("readPolicy").asString();
        if (!isEmpty(readPolicy)) {
            abstractProperty.setVendorExtension("x-readPolicy", readPolicy);
        }
        if (schema.get("returnOnDemand").isNotNull()) {
            abstractProperty.setVendorExtension("x-returnOnDemand", schema.get("returnOnDemand").asBoolean());
        }

        final Boolean readOnly = schema.get("readOnly").asBoolean();
        if (TRUE.equals(readOnly)) {
            abstractProperty.setReadOnly(TRUE);
        } else {
            // write-policy only relevant when NOT read-only
            final String writePolicy = schema.get("writePolicy").asString();
            if (!isEmpty(writePolicy)) {
                abstractProperty.setVendorExtension("x-writePolicy", writePolicy);
                if (schema.get("errorOnWritePolicyFailure").isNotNull()) {
                    abstractProperty.setVendorExtension("x-errorOnWritePolicyFailure",
                            schema.get("errorOnWritePolicyFailure").asBoolean());
                }
            }
        }

        // https://github.com/jdorn/json-editor#property-ordering
        final Integer propertyOrder = schema.get("propertyOrder").asInteger();
        if (propertyOrder != null) {
            abstractProperty.setVendorExtension("x-propertyOrder", propertyOrder);
        }

        return abstractProperty;
    }

    /**
     * Reads an array of JSON strings, given a field name.
     *
     * @param field Field name
     * @param schema Schema
     * @return result or empty-list, if field is undefined or value is {@code null}
     */
    private List<String> getArrayOfJsonString(final String field, final JsonValue schema) {
        final JsonValue value = schema.get(field);
        if (value.isNotNull() && value.isCollection()) {
            return value.asList(String.class);
        }
        return Collections.emptyList();
    }

    /**
     * Locates a JSON reference segment from an API Descriptor JSON reference, and strips everything before the
     * name of the reference under <em>definitions</em>.
     *
     * @param reference API Descriptor JSON reference
     * @return JSON reference segment or {@code null}
     */
    @VisibleForTesting
    protected String getDefinitionsReference(final Reference reference) {
        if (reference != null) {
            return getDefinitionsReference(reference.getValue());
        }
        return null;
    }

    /**
     * Locates a JSON reference segment from an API Descriptor JSON reference, and strips everything before the
     * name of the reference under <em>definitions</em>.
     *
     * @param reference API Descriptor JSON reference-value
     * @return JSON reference segment or {@code null}
     */
    @VisibleForTesting
    protected String getDefinitionsReference(final String reference) {
        if (!isEmpty(reference)) {
            final int start = reference.indexOf(DEFINITIONS_REF);
            if (start != -1) {
                final String s = reference.substring(start + DEFINITIONS_REF.length());
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return null;
    }

}