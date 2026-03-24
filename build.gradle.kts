plugins {
    kotlin("multiplatform") version "2.1.20"
    `maven-publish`
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

group = "com.adaurl"
version = "1.0.0"

repositories {
    mavenCentral()
}

// Build the Ada static library from the single-header C++ source.
//
// Ada requires C++20. On Linux, KN statically links GCC 8.3's libstdc++, which
// lacks std::string::_M_replace_cold — a cold-path helper emitted by GCC 13+.
// We compile a compat shim (string_compat.cpp) with KN's own GCC 8.3 toolchain
// (ABI-compatible with KN's libstdc++) and add it to libada.a so the symbol is
// defined. ada.cpp itself is compiled with the system C++ compiler (GCC 13) since
// GCC 8.3 lacks C++20 headers (<bit>, <ranges>, <version>, …).
val buildAdaLib by tasks.registering(Exec::class) {
    val depsDir = file("deps")
    val outDir = layout.buildDirectory.dir("ada").get().asFile
    val hostOs = System.getProperty("os.name")
    val konanDepsDir = "${System.getProperty("user.home")}/.konan/dependencies"

    doFirst { outDir.mkdirs() }

    val mainCompiler = System.getenv("CXX") ?: "c++"
    val adaObjFile = outDir.resolve("ada.o")
    val libFile = outDir.resolve("libada.a")

    val compileCmd: String
    if (hostOs == "Linux") {
        val gccDir =
            File(konanDepsDir)
                .listFiles { f -> f.isDirectory && f.name.startsWith("x86_64-unknown-linux-gnu-gcc") }
                ?.firstOrNull() ?: error("KN GCC dir not found in $konanDepsDir")
        val konanGpp = "${gccDir.absolutePath}/bin/x86_64-unknown-linux-gnu-g++"
        val sysroot = "${gccDir.absolutePath}/x86_64-unknown-linux-gnu/sysroot"
        val compatObjFile = outDir.resolve("string_compat.o")

        compileCmd =
            """
            # Compile ada.cpp with system C++ compiler (C++20 support)
            "$mainCompiler" -std=c++20 -O2 -fPIC -DADA_INCLUDE_URL_PATTERN=0 \
                -I"${depsDir.absolutePath}" \
                -c "${depsDir.absolutePath}/ada.cpp" \
                -o "$adaObjFile" && \
            # Compile the ABI-compat shim with KN's GCC 8.3 (same ABI as KN's libstdc++)
            "$konanGpp" -std=c++14 -O2 -fPIC \
                --sysroot="$sysroot" \
                -c "${depsDir.absolutePath}/string_compat.cpp" \
                -o "$compatObjFile" && \
            ar rcs "$libFile" "$adaObjFile" "$compatObjFile"
            """.trimIndent()
    } else {
        compileCmd =
            """
            "$mainCompiler" -std=c++20 -O2 -fPIC -DADA_INCLUDE_URL_PATTERN=0 \
                -I"${depsDir.absolutePath}" \
                -c "${depsDir.absolutePath}/ada.cpp" \
                -o "$adaObjFile" && \
            ar rcs "$libFile" "$adaObjFile"
            """.trimIndent()
    }

    commandLine("sh", "-c", compileCmd)

    inputs.files(fileTree(depsDir) { include("*.cpp", "*.h") })
    outputs.file(libFile)
}

val adaLibFile = layout.buildDirectory.file("ada/libada.a")

kotlin {
    // Declare all targets we support; only the one matching the host will build locally.
    val nativeTargets =
        listOf(
            linuxX64("linuxX64"),
            macosX64("macosX64"),
            macosArm64("macosArm64"),
        )

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
                        provider { "-include-binary=${adaLibFile.get().asFile.absolutePath}" },
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

        // Wire platform source sets into the shared native source sets.
        listOf("linuxX64Main", "macosX64Main", "macosArm64Main").forEach { name ->
            getByName(name).dependsOn(nativeMain)
        }
        listOf("linuxX64Test", "macosX64Test", "macosArm64Test").forEach { name ->
            getByName(name).dependsOn(nativeTest)
        }
    }
}

// Ensure the Ada static library is built before any cinterop or Kotlin compilation task.
tasks.matching { task ->
    task.name.contains("cinterop", ignoreCase = true) ||
        task.name.startsWith("compile") ||
        task.name.startsWith("link")
}.configureEach {
    dependsOn(buildAdaLib)
}
