plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.rust.android)
}

android {
    namespace = "io.github.lens0021.teogeul"
    compileSdk = 34
    // rust-android-gradle(cargo 크로스컴파일)이 요구. 명시하면 AGP 가 자동 설치한다.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "io.github.lens0021.teogeul"
        minSdk = 26
        targetSdk = 34

        val versionString = "1.2.0" // x-release-please-version
        versionName = versionString

        // Auto-calculate versionCode from versionName (e.g., 1.2.3 -> 10203)
        val parts = versionString.split(".")
        versionCode = parts[0].toInt() * 10000 + parts[1].toInt() * 100 + parts[2].toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.txt")
            // Use debug signing config for development/testing releases
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    applicationVariants.all {
        outputs.all {
            val outputImpl = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val buildType = buildType.name
            val version = versionName
            outputImpl.outputFileName = "teogeul-$buildType-$version.apk"
        }
    }

    lint {
        checkAllWarnings = true
        disable += setOf("MissingTranslation", "ObsoleteLintCustomCheck")
        error += setOf("MissingPrefix", "StringFormatInvalid")
    }

    sourceSets {
        getByName("main") {
            // UniFFI 생성 Kotlin 바인딩. Rust .so 는 rust-android-gradle 이
            // rustJniLibs 를 jniLibs 에 직접 등록하므로 여기서 다루지 않는다.
            kotlin.srcDir(layout.buildDirectory.dir("generated/uniffi/kotlin"))
        }
    }
}

cargo {
    module = "../rust"
    libname = "teogeul_engine"
    targets = listOf("arm64", "arm", "x86_64", "x86")
    profile = "release"
    pythonCommand = "python3"
}

// 호스트용 빌드: UniFFI 바인딩 생성과 JVM 단위 테스트(Robolectric)가 쓴다.
val cargoHostBuild =
    tasks.register<Exec>("cargoHostBuild") {
        workingDir = file("../rust")
        commandLine("cargo", "build", "--lib")
        inputs.files(fileTree("../rust/src"), file("../rust/Cargo.toml"), file("../rust/Cargo.lock"))
        outputs.file("../rust/target/debug/libteogeul_engine.so")
    }

val generateUniffiBindings =
    tasks.register<Exec>("generateUniffiBindings") {
        dependsOn(cargoHostBuild)
        workingDir = file("../rust")
        val outDir = layout.buildDirectory.dir("generated/uniffi/kotlin")
        commandLine(
            "cargo",
            "run",
            "--bin",
            "uniffi-bindgen",
            "--",
            "generate",
            "--library",
            "target/debug/libteogeul_engine.so",
            "--language",
            "kotlin",
            "--no-format",
            "--out-dir",
            outDir.get().asFile.absolutePath,
        )
        inputs.files(fileTree("../rust/src"), file("../rust/uniffi.toml"))
        outputs.dir(outDir)
    }

tasks.named("preBuild") {
    dependsOn(generateUniffiBindings)
}

// cargoBuild(Android 타겟) 산출물이 jniLibs 병합보다 먼저 만들어지도록.
tasks.matching { it.name.matches(Regex("merge.*JniLibFolders")) }.configureEach {
    dependsOn(tasks.named("cargoBuild"))
}

// rust-android-gradle 의 cargo 태스크는 실행 시점에 프로젝트 확장을 조회해
// 구성 캐시와 호환되지 않는다. 해당 태스크가 스케줄될 때만 캐시를 끈다.
tasks.matching { it.name.startsWith("cargoBuild") }.configureEach {
    notCompatibleWithConfigurationCache("rust-android-gradle 이 구성 캐시를 지원하지 않음")
}

// Robolectric(JVM) 테스트가 호스트 cdylib 를 로드할 수 있게 한다.
tasks.withType<Test>().configureEach {
    dependsOn(cargoHostBuild)
    systemProperty("jna.library.path", file("../rust/target/debug").absolutePath)
}

repositories {
    google()
    mavenCentral()
}

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.squareup" && requested.name == "javapoet") {
            useVersion(libs.versions.javapoet.get())
        }
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.android)
    // UniFFI 바인딩의 FFI 런타임 (Android 는 aar, JVM 테스트는 jar).
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
    testImplementation(libs.jna)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)

    // Compose BOM
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)

    // Compose
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.navigation.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    debugImplementation(libs.compose.ui.tooling)
}
