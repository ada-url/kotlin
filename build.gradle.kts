import java.util.Base64
import javax.inject.Inject
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    kotlin("multiplatform") version "2.3.20"
    `maven-publish`
    signing
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("com.gradleup.nmcp") version "1.4.4"
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
            // On Windows we compile ada.cpp with the MSYS2 mingw-w64 GCC 13 toolchain
            // (installed via pacman in CI at C:\msys64\mingw64). GCC 13 has full C++20
            // support including <ranges>, <bit>, etc. It produces mingw-ABI output so
            // KN's lld can resolve libstdc++/libgcc from its bundled sysroot.
            //
            // KN's bundled g++ (GCC 8) lacks C++20 headers, and KN's bundled LLVM 19
            // "essentials" has no C++ standard library headers at all.
            //
            // Arguments are passed as a list — no "cmd /c" string — so Gradle handles
            // quoting correctly regardless of spaces or backslashes in paths.
            val konanDepsDir =
                File(System.getenv("USERPROFILE") ?: System.getProperty("user.home"))
                    .resolve(".konan/dependencies")
            val mingwDir =
                konanDepsDir
                    .listFiles { f -> f.isDirectory && f.name.startsWith("msys2-mingw-w64-x86_64") }
                    ?.maxByOrNull { it.name }
                    ?: error(
                        "KN mingw toolchain not found in $konanDepsDir. " +
                            "It is downloaded on first use — ensure cinterop has run at least once.",
                    )

            // Modern GCC 13 from MSYS2 (installed via pacman in CI) for ada.cpp (C++20)
            // and the string_compat shim — both compiled with the same toolchain so
            // libstdc++ symbol references are consistent.
            val gpp13 = File("C:/msys64/mingw64/bin/g++.exe")
            // KN's own ar for archiving (format compatible with KN's lld)
            val ar = mingwDir.resolve("bin/ar.exe")
            val adaObjFile = outDir.resolve("ada.o")
            val compatObjFile = outDir.resolve("string_compat.o")

            // Step 1: compile ada.cpp → ada.o with GCC 13 (full C++20 support)
            execOps.exec {
                commandLine(
                    gpp13.absolutePath,
                    "-std=c++20",
                    "-O2",
                    "-DADA_INCLUDE_URL_PATTERN=0",
                    "-I",
                    deps.absolutePath,
                    "-c",
                    deps.resolve("ada.cpp").absolutePath,
                    "-o",
                    adaObjFile.absolutePath,
                )
            }

            // Step 2: compile string_compat.cpp → string_compat.o
            // Use the same GCC 13 that compiled ada.cpp — the shim must match the
            // same libstdc++ ABI that ada.o references, so they resolve together.
            // KN's bundled msys2 sysroot is headers+libs only, not a full compiler.
            execOps.exec {
                commandLine(
                    gpp13.absolutePath,
                    "-std=c++11",
                    "-O2",
                    "-c",
                    deps.resolve("string_compat.cpp").absolutePath,
                    "-o",
                    compatObjFile.absolutePath,
                )
            }

            // Step 3: archive both objects → libada.a
            execOps.exec {
                commandLine(
                    ar.absolutePath,
                    "rcs",
                    libFile.absolutePath,
                    adaObjFile.absolutePath,
                    compatObjFile.absolutePath,
                )
            }
        }
    }

group = "com.ada-url"
version = "0.1.3"

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

// nmcp (New Maven Central Publishing) bundles all artifacts into a ZIP and
// POSTs it to the Central Portal upload API — the correct protocol for
// https://central.sonatype.com (the standard maven-publish plugin sends
// per-file PUTs which the Central Portal does not support).
nmcp {
    publishAllPublicationsToCentralPortal {
        username =
            providers
                .gradleProperty("mavenCentralUsername")
                .orElse(providers.environmentVariable("MAVEN_CENTRAL_USERNAME"))
                .orNull ?: ""
        password =
            providers
                .gradleProperty("mavenCentralPassword")
                .orElse(providers.environmentVariable("MAVEN_CENTRAL_PASSWORD"))
                .orNull ?: ""
        // "USER_MANAGED" leaves the deployment in "Validated" state for manual review;
        // change to "AUTOMATIC" to publish straight to Maven Central without review.
        publishingType = "USER_MANAGED"
    }
}

publishing {
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
