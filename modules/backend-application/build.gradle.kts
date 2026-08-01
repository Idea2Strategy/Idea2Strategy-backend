plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(project(":modules:backend-domain"))

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
