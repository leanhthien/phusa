package vn.phusa

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    // Must be the pgvector image, not stock postgres: the migrations do
    // `CREATE EXTENSION vector` (V3) and would fail on a vanilla postgres image.
    // asCompatibleSubstituteFor tells Testcontainers this image behaves like
    // "postgres" for wait-strategy / JDBC-URL purposes.
    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer<*> =
        PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16")
                .asCompatibleSubstituteFor("postgres"),
        )
}
