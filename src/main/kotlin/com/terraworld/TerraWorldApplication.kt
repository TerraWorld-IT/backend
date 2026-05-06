package com.terraworld

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication(
    // We own authentication via JwtAuthenticationFilter + better-auth JWKS.
    // Exclude the default in-memory user autoconfig that would otherwise
    // spam "Using generated security password: ..." on every boot.
    exclude = [UserDetailsServiceAutoConfiguration::class],
)
@ConfigurationPropertiesScan
class TerraWorldApplication

fun main(args: Array<String>) {
    runApplication<TerraWorldApplication>(*args)
}
