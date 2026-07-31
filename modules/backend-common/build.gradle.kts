plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    testFixturesApi(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    testFixturesApi("com.fasterxml.jackson.core:jackson-databind")
    testFixturesApi("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
