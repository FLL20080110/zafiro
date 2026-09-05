pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()

        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/jcenter") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://jitpack.io") }

        maven { url = uri("https://chaquo.com/maven") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // com.github.Kyant0 artifacts are published by JitPack. Keep this group
        // exclusive to JitPack so a transient 5xx from an unrelated mirror
        // cannot abort dependency resolution before Gradle reaches JitPack.
        exclusiveContent {
            forRepository {
                maven {
                    name = "JitPackKyant0"
                    url = uri("https://jitpack.io")
                }
            }
            filter {
                includeGroup("com.github.Kyant0")
            }
        }

        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/jcenter") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://jitpack.io") }

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
