plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(project(":modules:backend-domain"))

    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
