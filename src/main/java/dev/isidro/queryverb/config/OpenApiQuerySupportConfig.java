package dev.isidro.queryverb.config;

import dev.isidro.queryverb.web.dto.SlotQueryFilter;
import dev.isidro.queryverb.web.dto.SlotResponse;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Adds a machine-readable trace of the QUERY route to the live {@code /api-docs} JSON — evaluated
 * from an externally-sourced proposal (a {@code OpenApiCustomizer}-based approach) that turned out
 * to be wrong on its central claim, corrected here. See design-decisions-v2.md for the full writeup,
 * including the original proposal's exact wording.
 *
 * <p><b>What the proposal got wrong, confirmed by testing against this project's actual dependency
 * versions, not assumed:</b> it claimed {@code pathItem.addExtension("query", operation)} — an
 * unprefixed key — would be picked up by "modern UI readers processing OpenAPI 3.2." Tested that
 * exact call: the key is silently dropped during serialization and never appears in {@code
 * /api-docs} at all. swagger-core (2.2.47, this project's pinned version) only serializes extension
 * keys that start with {@code x-}, per the OpenAPI spec's own extension convention — confirmed by
 * adding an {@code x-}-prefixed key alongside the unprefixed one and observing only the prefixed
 * one survive serialization. Its second suggestion, {@code springdoc.config-path} to merge a static
 * YAML file, isn't a real property in this version either — not present anywhere in {@code
 * SpringDocConfigProperties} (checked the actual source, not just the properties reference page).
 *
 * <p><b>What this corrected version actually achieves, and what it doesn't:</b> the QUERY operation
 * now appears under {@code paths./api/users/{userId}/slots.x-query} in the live JSON, with real
 * {@code $ref}s into {@code #/components/schemas/SlotQueryFilter} and {@code SlotResponse} (reflected
 * from the actual DTOs via {@code ModelConverters}, not hand-typed — so it can't silently drift out
 * of sync the way a hand-maintained duplicate could). It is <b>not</b> expected to render as an
 * interactive operation card in Swagger UI: Swagger UI's renderer only builds those from the fixed
 * method keys (get/put/post/delete/head/options/patch/trace) — an arbitrary {@code x-} key holding
 * an Operation-shaped object is not one of them, regardless of OpenAPI version. This is a documented
 * limitation of this approach, not a claim it fully solves the problem — the human-readable
 * reference remains {@code query-endpoint.openapi.yaml}; this just makes the same information
 * discoverable to anything already consuming {@code /api-docs} programmatically.
 */
@Configuration
public class OpenApiQuerySupportConfig {

    private static final String SLOTS_PATH = "/api/users/{userId}/slots";

    @Bean
    public OpenApiCustomizer addQueryMethodDocumentation() {
        return openApi -> {
            ModelConverters converters = ModelConverters.getInstance();

            ResolvedSchema filterSchema = converters.resolveAsResolvedSchema(new AnnotatedType(SlotQueryFilter.class));
            ResolvedSchema responseSchema = converters.resolveAsResolvedSchema(new AnnotatedType(SlotResponse.class));

            for (Map.Entry<String, Schema> entry : filterSchema.referencedSchemas.entrySet()) {
                openApi.getComponents().addSchemas(entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, Schema> entry : responseSchema.referencedSchemas.entrySet()) {
                openApi.getComponents().addSchemas(entry.getKey(), entry.getValue());
            }
            openApi.getComponents().addSchemas("SlotQueryFilter", filterSchema.schema);
            openApi.getComponents().addSchemas("SlotResponse", responseSchema.schema);

            Operation queryOperation = new Operation()
                    .operationId("querySlots")
                    .summary("Filter a user's slots by status and/or time range (HTTP QUERY)")
                    .description("""
                            Not renderable as a normal Swagger UI operation card — Swagger UI only builds those \
                            from the standard get/put/post/delete/... keys, and QUERY isn't one of them yet in \
                            this project's OpenAPI tooling. See query-endpoint.openapi.yaml for the full \
                            human-readable reference (examples, parameter docs) and design-decisions-v2.md for \
                            why this route can't be documented like the other 12.""")
                    .addTagsItem("slot-handler")
                    .requestBody(new RequestBody()
                            .required(false)
                            .content(new Content().addMediaType("application/json",
                                    new MediaType().schema(new Schema<>().$ref("#/components/schemas/SlotQueryFilter")))))
                    .responses(new ApiResponses().addApiResponse("200",
                            new ApiResponse()
                                    .description("Matching slots for this user, ordered by startTime ascending.")
                                    .content(new Content().addMediaType("application/json",
                                            new MediaType().schema(new ArraySchema()
                                                    .items(new Schema<>().$ref("#/components/schemas/SlotResponse")))))));

            PathItem pathItem = openApi.getPaths().computeIfAbsent(SLOTS_PATH, k -> new PathItem());
            pathItem.addExtension("x-query", queryOperation);
        };
    }
}
