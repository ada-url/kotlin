import javax.inject.Inject
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    kotlin("multiplatform") version "2.3.20"
    `maven-publish`
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

            val isLinux = System.getProperty("os.name") == "Linux"
            val mainCompiler = System.getenv("CXX") ?: "c++"
            val adaObjFile = outDir.resolve("ada.o")

            val compileCmd =
                if (isLinux) {
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
                    """.trimIndent()
                } else {
                    """
                    "$mainCompiler" -std=c++20 -O2 -fPIC -DADA_INCLUDE_URL_PATTERN=0 \
                        -I"${deps.absolutePath}" \
                        -c "${deps.absolutePath}/ada.cpp" \
                        -o "$adaObjFile" && \
                    ar rcs "$libFile" "$adaObjFile"
                    """.trimIndent()
                }

            execOps.exec {
                commandLine("sh", "-c", compileCmd)
            }
        }
    }

group = "com.adaurl"
version = "1.0.0"

repositories {
    mavenCentral()
}

// Register the Ada static library build task.
// Logic lives in BuildAdaLibTask above; all path resolution is deferred to
// execution time so the KN toolchain has been downloaded before we need it.
val buildAdaLib by tasks.registering(BuildAdaLibTask::class) {
    depsDir = layout.projectDirectory.dir("deps")
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
            hostOs == "Mac OS X" && hostArch == "aarch64" ->
                listOf(macosArm64("macosArm64"))
            hostOs == "Mac OS X" ->
                listOf(macosX64("macosX64"))
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
