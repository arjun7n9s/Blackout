// Top-level build file. Plugins are declared here (apply false) so the
// versions resolve once and every module reuses them.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
