plugins {
    java
    id("org.springframework.boot")
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    implementation(platform("software.amazon.awssdk:bom:2.31.7"))
    implementation(project(":modules:backend-application"))
    implementation(project(":modules:backend-messaging"))
    implementation(project(":modules:backend-persistence"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("software.amazon.awssdk:sqs")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-localstack")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation("org.springframework.boot:spring-boot-flyway")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets {
    test {
        // The relay writes the canonical outbox, so its tests need the canonical schema. The bundle
        // lives in db-migration; this borrows it exactly as backend-persistence's tests do.
        resources.srcDir(project(":db-migration").file("src/main/resources"))
    }
}
