plugins {
    id("java-library")
    idea
}

group = "lib.minecraft"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven(url = "https://jitpack.io")
}

dependencies {
    // Simplified Annotations
    annotationProcessor(libs.simplified.annotations)

    // Lombok
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    // Tests
    testImplementation(libs.hamcrest)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.junit.platform.launcher)

    // Simplified Libraries (github.com/simplified-dev). Pinned to the post-interface-migration
    // commit on collections (ece3042 onward) - older pins linked against the AtomicCollection
    // class form and surfaced LambdaConversionException at consumer link time.
    api("com.github.simplified-dev:collections") { version { strictly("6586657") } }
    api("com.github.simplified-dev:utils") { version { strictly("5c6c96a") } }
    api("com.github.simplified-dev:image") { version { strictly("4140130") } }

    // Gson - JsonObject / JsonElement used by TextSegment + friends
    api(libs.gson)
}

idea {
    module {
        excludeDirs.add(layout.projectDirectory.dir("cache").asFile)
    }
}

// Generated font files live outside src/main/resources so the font-generator tool never has
// to write into the source tree. processResources picks them up and copies them onto the
// runtime classpath at build time so MinecraftFont.initFont("fonts/X.otf", size) keeps
// resolving via getResourceAsStream without any runtime code changes.
val fontsCacheDir = layout.projectDirectory.dir("cache/fonts")

tasks {
    test {
        useJUnitPlatform()
    }

    processResources {
        // INCLUDE lets the generated fonts win over anything that might already sit in
        // src/main/resources/fonts/ (that directory is gitignored, but a stale dev-machine
        // copy could otherwise collide under the default FAIL strategy).
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        from(fontsCacheDir) {
            into("fonts")
        }
    }

    withType<JavaExec>().configureEach {
        workingDir = layout.projectDirectory.asFile
    }

    register<JavaExec>("fonts") {
        description = "Clones minecraft-library/font-generator into cache/font-generator, sets up a Python venv, and runs the generator against the given MC version (-PfontVersion=26.1 by default). Writes .otf files to cache/fonts/ - run processResources afterwards to copy them onto the classpath."
        group = "tooling"
        mainClass.set("dev.sbs.renderer.tooling.ToolingFonts")
        classpath = sourceSets["main"].runtimeClasspath
        val version = (project.findProperty("fontVersion") as String?) ?: "26.1"
        args = listOf(version)
    }
}
