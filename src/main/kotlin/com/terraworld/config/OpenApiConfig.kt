package com.terraworld.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun openAPI(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("TerraWorld API")
                .description("TerraWorld Backend API - 나만의 작은 기록 정원")
                .version("1.0.0")
        )
        .addSecurityItem(SecurityRequirement().addList("Bearer"))
        .components(
            Components().addSecuritySchemes(
                "Bearer",
                SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("JWT Access Token")
            )
        )
}
