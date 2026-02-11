buildscript {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    // 这里只声明，不 apply
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}