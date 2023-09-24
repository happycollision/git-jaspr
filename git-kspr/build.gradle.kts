@file:Suppress("UnstableApiUsage")

import com.expediagroup.graphql.plugin.gradle.config.GraphQLSerializer

plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.10"
    kotlin("plugin.serialization") version "1.9.10"
    application
    id("com.expediagroup.graphql") version "6.4.1"
    id("com.diffplug.spotless") version "6.21.0"
}

repositories {
    mavenCentral()
    maven {
        name = "jgit-repository"
        url = uri("https://repo.eclipse.org/content/groups/releases/")
    }
}

// val graphqlDownloadSDL by tasks.getting(GraphQLDownloadSDLTask::class) {
//    endpoint.set("https://docs.github.com/public/schema.docs.graphql")
// }

graphql {
    client {
        sdlEndpoint = "https://docs.github.com/public/schema.docs.graphql"
        queryFileDirectory = "src/graphql"
        packageName = "sims.michael.gitkspr.generated"
        serializer = GraphQLSerializer.KOTLINX
    }
}

// val graphqlGenerateClient by tasks.getting(GraphQLGenerateClientTask::class) {
//    // we need to overwrite default behavior of using Jackson data model
//    serializer.set(GraphQLSerializer.KOTLINX)
//    packageName.set("com.expediagroup.graphql.generated")
// //    parserOptions.set(GraphQLParserOptions(maxTokens = Int.MAX_VALUE))
// }

dependencies {
    implementation("com.github.ajalt.clikt:clikt:4.2.0")
    implementation("com.expediagroup:graphql-kotlin-ktor-client:6.4.1")
    implementation("io.ktor:ktor-client-auth:2.3.0")
    implementation("org.slf4j:slf4j-api:2.0.5")
    implementation("ch.qos.logback:logback-classic:1.4.6")
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.3.0.202209071007-r")
    implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.jsch:6.3.0.202209071007-r")
    implementation("org.zeroturnaround:zt-exec:1.11")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.9.1")
    testImplementation("org.eclipse.jgit:org.eclipse.jgit.junit:6.3.0.202209071007-r")
    testImplementation("com.nhaarman.mockitokotlin2:mockito-kotlin:2.2.0")
    testImplementation("org.mockito:mockito-inline:2.23.0")
}

// Apply a specific Java toolchain to ease working on different environments.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

application {
    // Define the main class for the application.
    mainClass.set("sims.michael.gitkspr.Cli")
}

// tasks.named<Test>("test") {
//    // Use JUnit Platform for unit tests.
//    useJUnitPlatform()
// }

val nonDefaultTestTags = mapOf(
    "functional" to "Functional tests",
)

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter()
            targets {
                all {
                    testTask.configure {
                        useJUnitPlatform {
                            excludeTags(*nonDefaultTestTags.keys.toTypedArray())
                        }
                    }
                }
            }
        }
    }
}

val defaultTestSuite = testing.suites.named<JvmTestSuite>("test")

nonDefaultTestTags.forEach { (testTag, testDescription) ->
    task<Test>(testTag) {
        description = testDescription
        useJUnitPlatform {
            includeTags(testTag)
        }

        testClassesDirs = files(defaultTestSuite.map { it.sources.output.classesDirs })
        classpath = files(defaultTestSuite.map { it.sources.runtimeClasspath })
    }
}

spotless {
    kotlin {
        toggleOffOn()
        ktlint().setEditorConfigPath("$rootDir/.editorconfig")
        targetExclude("build/generated/")
    }
    kotlinGradle {
        toggleOffOn()
        ktlint()
    }
}
