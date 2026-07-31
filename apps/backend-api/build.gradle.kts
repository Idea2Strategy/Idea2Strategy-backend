plugins {
    java
    id("org.springframework.boot")
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    implementation(project(":modules:backend-application"))
    implementation(project(":modules:backend-persistence"))
    implementation(project(":modules:backend-messaging"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
