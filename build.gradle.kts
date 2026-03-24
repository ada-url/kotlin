import java.util.Base64
import javax.inject.Inject
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    kotlin("multiplatform") version "2.3.20"
    `maven-publish`
    signing
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

// Abstract task class so Gradle can inject ExecOperations (Gradle 9-compatible
// replacement for the removed project.exec {} API).
abstract class BuildAdaLibTask
    @Inject
    constructor(
        private val execOps: ExecOperations,
    ) : DefaultTask() {
        @get:InputDirectory
        abstract val depsDir: DirectoryProperty

        @get:OutputFile
        abstract val outputLib: RegularFileProperty

        @TaskAction
        fun build() {
            val libFile = outputLib.get().asFile
            val outDir = libFile.parentFile
            val deps = depsDir.get().asFile
            outDir.mkdirs()

            val osName = System.getProperty("os.name")
            val isLinux = osName == "Linux"
            val isWindows = osName.startsWith("Windows")

            when {
                isWindows -> buildWindows(deps, outDir, libFile)
                isLinux -> buildLinux(deps, outDir, libFile)
                else -> buildUnix(deps, outDir, libFile)
            }
        }

        private fun buildLinux(
            deps: File,
            outDir: File,
            libFile: File,
        ) {
            val mainCompiler = System.getenv("CXX") ?: "c++"
            val adaObjFile = outDir.resolve("ada.o")
            val konanDepsDir = "${System.getProperty("user.home")}/.konan/dependencies"
            val gccDir =
                File(konanDepsDir)
                    .listFiles { f -> f.isDirectory && f.name.startsWith("x86_64-unknown-linux-gnu-gcc") }
                    ?.firstOrNull()
                    ?: error(
                        "KN GCC toolchain not found in $konanDepsDir. " +
                            "It is downloaded automatically on first use — ensure the KN " +
                            "plugin has run at least once (e.g. via the cinterop task).",
                    )
            val konanGpp = "${gccDir.absolutePath}/bin/x86_64-unknown-linux-gnu-g++"
            val sysroot = "${gccDir.absolutePath}/x86_64-unknown-linux-gnu/sysroot"
            val compatObjFile = outDir.resolve("string_compat.o")
            // Compile ada.cpp with the system C++ compiler (C++20 support), then
            // compile the ABI-compat shim with KN's GCC 8.3 (matching KN's libstdc++).
            execOps.exec {
                commandLine(
                    "sh",
                    "-c",
                    """
                    "$mainCompiler" -std=c++20 -O2 -fPIC -DADA_INCLUDE_URL_PATTERN=0 \
                        -I"${deps.absolutePath}" \
                        -c "${deps.absolutePath}/ada.cpp" \
                        -o "$adaObjFile" && \
                    "$konanGpp" -std=c++11 -O2 -fPIC \
                        --sysroot="$sysroot" \
                        -c "${deps.absolutePath}/string_compat.cpp" \
                        -o "$compatObjFile" && \
                    ar rcs "$libFile" "$adaObjFile" "$compatObjFile"
                    """.trimIndent(),
                )
            }
        }

        private fun buildUnix(
            deps: File,
            outDir: File,
            libFile: File,
        ) {
            val mainCompiler = System.getenv("CXX") ?: "c++"
            val adaObjFile = outDir.resolve("ada.o")
            execOps.exec {
                commandLine(
                    "sh",
                    "-c",
                    """
                    "$mainCompiler" -std=c++20 -O2 -fPIC -DADA_INCLUDE_URL_PATTERN=0 \
                        -I"${deps.absolutePath}" \
                        -c "${deps.absolutePath}/ada.cpp" \
                        -o "$adaObjFile" && \
                    ar rcs "$libFile" "$adaObjFile"
                    """.trimIndent(),
                )
            }
        }

        private fun buildWindows(
            deps: File,
            outDir: File,
            libFile: File,
        ) {
            // On Windows, KN links mingwX64 binaries with its own bundled clang/lld.
            // We compile ada.cpp with clang++ (available on GitHub Actions windows-latest
            // via LLVM) targeting the mingw ABI, then archive with llvm-ar.
            // No ABI shim needed: MSVC/mingw's libstdc++ doesn't have the cold-path split.
            val compiler = System.getenv("CXX") ?: "clang++"
            val ar = "llvm-ar"
            val adaObjFile = outDir.resolve("ada.o")
            execOps.exec {
                commandLine(
                    "cmd",
                    "/c",
                    "$compiler -std=c++20 -O2 -DADA_INCLUDE_URL_PATTERN=0" +
                        " -I\"${deps.absolutePath}\"" +
                        " -c \"${deps.absolutePath}\\ada.cpp\"" +
                        " -o \"$adaObjFile\"" +
                        " && $ar rcs \"$libFile\" \"$adaObjFile\"",
                )
            }
        }
    }

group = "com.ada-url"
version = "0.1.1"

repositories {
    mavenCentral()
}

// Register the Ada static library build task.
// Logic lives in BuildAdaLibTask above; all path resolution is deferred to
// execution time so the KN toolchain has been downloaded before we need it.
val isWindowsHost = System.getProperty("os.name").startsWith("Windows")
val buildAdaLib by tasks.registering(BuildAdaLibTask::class) {
    depsDir = layout.projectDirectory.dir("deps")
    // mingw uses libada.a too — llvm-ar produces GNU-format archives compatible with lld
    outputLib = layout.buildDirectory.file("ada/libada.a")
}

// Only declare targets that can be built on the current host.
// Apple targets require a macOS host when cinterop is involved; Linux can only
// build linuxX64. See: https://kotl.in/native-targets-tiers
val hostOs = System.getProperty("os.name")
val hostArch = System.getProperty("os.arch")

kotlin {
    val nativeTargets =
        when {
            hostOs == "Linux" -> listOf(linuxX64("linuxX64"))
            hostOs == "Mac OS X" -> listOf(macosArm64("macosArm64"))
            hostOs.startsWith("Windows") -> listOf(mingwX64("mingwX64"))
            else -> error("Unsupported host: $hostOs / $hostArch")
        }

    nativeTargets.forEach { target ->
        target.compilations["main"].apply {
            cinterops {
                val adaC by creating {
                    defFile(project.file("src/nativeInterop/cinterop/adaC.def"))
                    includeDirs(project.file("deps"))
                }
            }
            // Embed the Ada static library directly into the klib.
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add(
                        buildAdaLib.flatMap { it.outputLib }.map { "-include-binary=${it.asFile.absolutePath}" },
                    )
                }
            }
        }

        target.binaries {
            sharedLib { baseName = "ada" }
            staticLib { baseName = "ada" }
        }
    }

    sourceSets {
        val nativeMain by creating {
            dependsOn(commonMain.get())
        }
        val nativeTest by creating {
            dependsOn(commonTest.get())
            dependencies {
                implementation(kotlin("test"))
            }
        }

        // Wire the host-specific source sets into the shared native source sets.
        targets.withType<KotlinNativeTarget>().forEach { t ->
            getByName("${t.name}Main").dependsOn(nativeMain)
            getByName("${t.name}Test").dependsOn(nativeTest)
        }
    }
}

publishing {
    repositories {
        maven {
            name = "MavenCentral"
            // Releases go to the Central Portal staging API; snapshots go to OSSRH.
            val isRelease = !version.toString().endsWith("-SNAPSHOT")
            url =
                uri(
                    if (isRelease) {
                        "https://central.sonatype.com/api/v1/publisher/upload"
                    } else {
                        "https://s01.oss.sonatype.org/content/repositories/snapshots/"
                    },
                )
            credentials {
                username =
                    providers
                        .gradleProperty("mavenCentralUsername")
                        .orElse(providers.environmentVariable("MAVEN_CENTRAL_USERNAME"))
                        .orNull
                password =
                    providers
                        .gradleProperty("mavenCentralPassword")
                        .orElse(providers.environmentVariable("MAVEN_CENTRAL_PASSWORD"))
                        .orNull
            }
        }
    }

    publications.withType<MavenPublication>().configureEach {
        pom {
            name = "ada"
            description = "Fast WHATWG-compliant URL parser for Kotlin/Native, built on Ada"
            url = "https://github.com/ada-url/kotlin"
            licenses {
                license {
                    name = "MIT OR Apache-2.0"
                    url = "https://opensource.org/licenses/MIT"
                }
            }
            developers {
                developer {
                    id = "yagiz"
                    name = "Yagiz Nizipli"
                    email = "yagiz@nizipli.com"
                }
            }
            scm {
                connection = "scm:git:git://github.com/ada-url/kotlin.git"
                developerConnection = "scm:git:ssh://github.com/ada-url/kotlin.git"
                url = "https://github.com/ada-url/kotlin"
            }
        }
    }
}

signing {
    // SIGNING_KEY is stored as a base64-encoded ASCII-armored PGP private key to
    // avoid multiline secret mangling in GitHub Actions. Decode it before use.
    val signingKeyBase64 = providers.environmentVariable("SIGNING_KEY").orNull
    val signingPassword = providers.environmentVariable("SIGNING_PASSWORD").orNull
    if (signingKeyBase64 != null && signingPassword != null) {
        val signingKey = String(Base64.getDecoder().decode(signingKeyBase64))
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
    sign(publishing.publications)
}

// buildAdaLib needs the KN GCC 8.3 toolchain on Linux, which is downloaded lazily
// the first time a cinterop task executes. So the order must be:
//   cinterop → buildAdaLib → compile/link
// We wire this in two steps:
//   1. buildAdaLib mustRunAfter all cinterop tasks (ensures toolchain is present)
//   2. compile/link tasks depend on buildAdaLib (ensures libada.a is embedded)
tasks
    .matching { task ->
        task.name.startsWith("compile") ||
            task.name.startsWith("link")
    }.configureEach {
        dependsOn(buildAdaLib)
    }

tasks
    .matching { task ->
        task.name.contains("cinterop", ignoreCase = true)
    }.configureEach {
        buildAdaLib.get().mustRunAfter(this)
    }
