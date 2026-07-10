package dev.isidro.queryverb.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Mini Doodle — Scheduling API")
                        .version("0.1.0")
                        .description("""
                                Meeting scheduling platform showcasing the experimental HTTP QUERY verb \
                                (draft-ietf-httpbis-safe-method-w-body) via Spring WebMvc.fn functional routes.
                                
                                The QUERY verb is safe + idempotent but allows a structured request body, \
                                making it ideal for slot availability queries with complex filters.
                                """)
                        .contact(new Contact().name("Isidro Rebollo").url("https://github.com/isidrorn")));
    }
}
