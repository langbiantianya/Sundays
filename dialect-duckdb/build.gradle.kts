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
    // 「Excel 文件不存在: …」这条界面可见报错由文案表提供语言。
    implementation(project(":i18n"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    // Excel 预转换：POI 读取 .xlsx/.xls → 临时 DuckDB
    implementation(libs.poi)
    implementation(libs.poi.ooxml)

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation(libs.duckdb)
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveClassifier.set("")
    archiveBaseName.set("idb-dialect-duckdb")
}
