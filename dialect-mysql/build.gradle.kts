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
}

tasks.jar {
    archiveClassifier.set("")
    archiveBaseName.set("idb-dialect-mysql")
}
