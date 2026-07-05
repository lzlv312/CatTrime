// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

pluginManagement {
    includeBuild("build-logic")
    repositories {
        // 优先使用国内镜像
        maven {
            url = uri("https://maven.aliyun.com/repository/gradle-plugin")
            name = "AliyunGradlePlugin"
        }
        maven {
            url = uri("https://maven.aliyun.com/repository/google")
            name = "AliyunGoogle"
        }
        maven {
            url = uri("https://maven.aliyun.com/repository/public")
            name = "AliyunPublic"
        }
        // 备用官方仓库
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 优先使用国内镜像
        maven {
            url = uri("https://maven.aliyun.com/repository/google")
            name = "AliyunGoogle"
        }
        maven {
            url = uri("https://maven.aliyun.com/repository/public")
            name = "AliyunPublic"
        }
        // 备用官方仓库
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "trime"
include(":app")
include(":codegen")
