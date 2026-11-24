buildscript {
    repositories {
        maven(url = uri(rootDir.resolve(".local-m2")))
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.3.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10")
    }
}
