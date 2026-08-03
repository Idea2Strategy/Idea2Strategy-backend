plugins {
    application
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    implementation(project(":modules:backend-application"))
    implementation(project(":modules:backend-persistence")) { isTransitive = false }
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.springframework:spring-jdbc")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    applicationName = "idea2strategy"
    mainClass = "com.idea2strategy.cli.Idea2StrategyCli"
}

tasks.named<Test>("test") {
    dependsOn(tasks.named("installDist"))
    systemProperty(
        "idea2strategy.cli.installDir",
        layout.buildDirectory.dir("install/idea2strategy").get().asFile.absolutePath,
    )
}
