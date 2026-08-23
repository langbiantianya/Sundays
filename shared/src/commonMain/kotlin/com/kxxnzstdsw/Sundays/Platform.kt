package com.kxxnzstdsw.Sundays

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform