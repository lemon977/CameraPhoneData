pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()

        // 阿里云镜像
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }

        // OSS SDK 额外仓库
        maven { url = uri("https://maven.aliyun.com/nexus/content/repositories/releases") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)

    repositories {
        google()
        mavenCentral()

        // 阿里云镜像
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }

        // OSS SDK 额外仓库
        maven { url = uri("https://maven.aliyun.com/nexus/content/repositories/releases") }
    }
}

rootProject.name = "CameraPhoneData"
include(":app", ":opencv")