package com.kxxnzstdsw.idb_app

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform