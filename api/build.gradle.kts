/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

plugins {
    kotlin("jvm")
}

dependencies {
    // 「$driverName 不支持 XXX」这批界面可见报错由文案表统一提供语言。
    // :i18n 是纯 Kotlin + JDK 模块（无 project 依赖），不会与 :api 形成环。
    implementation(project(":i18n"))
}

group = "com.langbiantianya"
version = "0.1.0"

tasks.jar {
    archiveClassifier.set("")
    archiveBaseName.set("idb-api")
}
