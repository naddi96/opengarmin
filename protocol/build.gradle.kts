plugins {
    kotlin("jvm")
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    testLogging {
        events("passed", "failed", "skipped")
    }
}
