plugins {
    application
}

dependencies {
    implementation(project(":bullseye-common"))
    implementation(project(":bullseye-native"))

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("io.bullseye.core.BullseyeApplication")
    applicationDefaultJvmArgs = listOf("-XX:+ExitOnOutOfMemoryError")
}

distributions {
    main {
        contents {
            from(rootProject.file("config/bullseye.properties")) {
                into("config")
            }
        }
    }
}
