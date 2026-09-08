pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        // GitHub-hosted libraries should resolve from JitPack before regional
        // mirrors. A transient mirror 5xx must not prevent Gradle from reaching
        // the canonical source for com.github.* dependencies.
        maven { url = uri("https://jitpack.io") }

        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/jcenter") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }

        maven { url = uri("https://chaquo.com/maven") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Resolve GitHub coordinates from JitPack before regional mirrors so
        // mirror outages don't fail dependency resolution before fallback.
        maven { url = uri("https://jitpack.io") }

        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/jcenter") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }

        maven { url = uri("https://chaquo.com/maven") }
    }
}
rootProject.name = "Zafiro"
include(":app")
include(":xposed-api")
include(":xposed-runtime")
include(":ui-kit")
include(":store")
include(":agent-runtime")

// Vendored libraries (see libs/README.md)
include(":libs:logging")
include(":libs:okia")
include(":libs:libterm-core")
project(":libs:libterm-core").projectDir = file("libs/libterm/libterm-core")
include(":libs:libterm-runtime")
project(":libs:libterm-runtime").projectDir = file("libs/libterm/libterm-runtime")
include(":libs:libterm-backend-libsu")
project(":libs:libterm-backend-libsu").projectDir = file("libs/libterm/libterm-backend-libsu")
include(":libs:libterm-backend-shizuku")
project(":libs:libterm-backend-shizuku").projectDir = file("libs/libterm/libterm-backend-shizuku")
include(":libs:libterm-backend-ssh")
project(":libs:libterm-backend-ssh").projectDir = file("libs/libterm/libterm-backend-ssh")
