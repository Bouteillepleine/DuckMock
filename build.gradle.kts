plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.agp.lib) apply false
    alias(libs.plugins.kotlin) apply false
}

val appPackageName by extra("com.strawing.duckmock")
val moduleId by extra("duckmock")
val moduleVersionName by extra("1.4.3")
val moduleVersionCode by extra(15)

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
