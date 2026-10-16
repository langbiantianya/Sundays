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
version = "0.1.1"

dependencies {
    implementation(project(":api"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    // `jdbcUrlForCatalog` 是纯字符串变换，不需要真库 —— 这套测试不打网络。
    // 聚合 artifact 里已含 junit-jupiter-params（`@ParameterizedTest` / `@ValueSource`）。
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveClassifier.set("")
    archiveBaseName.set("idb-dialect-postgresql")
}
