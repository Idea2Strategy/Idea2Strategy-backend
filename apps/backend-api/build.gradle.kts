plugins {
    java
    id("org.springframework.boot")
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    implementation(platform("software.amazon.awssdk:bom:2.31.7"))
    implementation(project(":modules:backend-application"))
    implementation(project(":modules:backend-persistence"))
    implementation(project(":modules:backend-messaging"))
    implementation(project(":modules:backend-operator-trust"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("software.amazon.awssdk:sesv2")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation("org.springframework.boot:spring-boot-flyway")
    testImplementation("org.springframework.security:spring-security-oauth2-jose")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

sourceSets {
    test {
        resources.srcDir(project(":db-migration").file("src/main/resources"))
    }
}
