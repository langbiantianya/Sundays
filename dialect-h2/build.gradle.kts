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

group = "com.langbiantianya"
version = "0.1.0"

dependencies {
    implementation(project(":api"))
    // 文案表 —— `connectionForm` 里要给字段标 `StrKey`（标签 / 占位提示）。
    // :api 对 :i18n 是 implementation 依赖（不传递给消费方），所以这里要自己声明。
    implementation(project(":i18n"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation(libs.h2)
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveClassifier.set("")
    archiveBaseName.set("idb-dialect-h2")
}
