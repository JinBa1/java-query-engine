package com.github.jinba1.cuckoodb.server.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document metadata. springdoc auto-generates the schema and serves Swagger UI at
 * {@code /swagger-ui.html} and the spec at {@code /v3/api-docs}; this only supplies the
 * title/description/version so the contract documents the gateway's read-only, budgeted intent.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI cuckooDbOpenApi() {
        return new OpenAPI().info(new Info()
                .title("cuckooDB REST API")
                .version("1.0.0")
                .description("A guarded, read-only-by-construction, resource-budgeted HTTP front "
                        + "door onto the cuckooDB in-memory query engine. POST /queries executes "
                        + "SQL synchronously; the /tables endpoints expose catalog schema and "
                        + "(opt-in) CSV upload."));
    }
}
