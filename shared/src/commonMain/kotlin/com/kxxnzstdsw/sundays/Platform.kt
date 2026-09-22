package com.kxxnzstdsw.sundays

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform