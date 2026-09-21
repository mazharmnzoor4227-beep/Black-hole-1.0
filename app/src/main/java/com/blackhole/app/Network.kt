package com.blackhole.app

import java.io.IOException

class UserFailure(message: String) : IOException(message)

data class Video(
    val url: String,
    val title: String,
    val source: String,
    val quality: String,
)
