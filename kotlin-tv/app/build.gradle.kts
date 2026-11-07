import java.util.Properties
import com.android.build.api.variant.impl.VariantOutputImpl

plugins { id("com.android.application"); kotlin("android") }
val signingProperties = Properties().apply { val config = rootProject.file("keystore.properties"); if (config.exists()) config.inputStream().use { load(it) } }
fun signingValue(env: String, key: String): String? = System.getenv(env) ?: signingProperties.getProperty(key)
val petsName = System.getenv("PETS_NAME")
val petsKey = System.getenv("PETS_KEY")
// Release artifacts ship as hongguotv-<versionName>-release.apk so the build output, the GitHub release asset and the update manifest all agree.
val isolatedTest = providers.gradleProperty("tvIsolatedTest").orNull == "true"
val playbackTest = providers.gradleProperty("tvPlaybackTest").orNull == "true"
val deliveryVersionCode = System.getenv("HONGGUOTV_VERSION_CODE")?.toInt() ?: 9
val deliveryVersionName = System.getenv("HONGGUOTV_VERSION_NAME") ?: "0.9.0"

android {
    namespace = "com.hongguotv.nativeapp"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.hongguotv.nativeapp"
        manifestPlaceholders["appLabel"] = "红果 TV 原生版"
        minSdk = 26
        targetSdk = 36
        versionCode = deliveryVersionCode
        versionName = deliveryVersionName
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        create("delivery") {
            storeFile = signingValue("HONGGUOTV_KEYSTORE", "storeFile")?.let { file(it) }
                ?: rootProject.file("../Pets.jks").takeIf { petsName != null && petsKey != null }
            storePassword = signingValue("HONGGUOTV_STORE_PASSWORD", "storePassword") ?: petsKey
            keyAlias = signingValue("HONGGUOTV_KEY_ALIAS", "keyAlias") ?: petsName
            keyPassword = signingValue("HONGGUOTV_KEY_PASSWORD", "keyPassword") ?: petsKey
        }
    }
    buildTypes {
        getByName("debug") {
            // 播放修复验收包独立安装，不覆盖已安装的正式版或改动其账号数据。
            if(playbackTest) {
                applicationIdSuffix = ".playbacktest"
                manifestPlaceholders["appLabel"] = "红果 TV 播放测试"
            }
            if(isolatedTest) {
                applicationIdSuffix = ".petstest"
                manifestPlaceholders["appLabel"] = "红果 TV 测试版"
                signingConfig = signingConfigs.getByName("delivery")
            }
        }
        getByName("release") { signingConfig = signingConfigs.getByName("delivery"); isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module") }
}
androidComponents {
    onVariants(selector().all()) { variant ->
        if (variant.buildType == "release") variant.outputs.forEach { output -> (output as VariantOutputImpl).outputFileName.set("hongguotv-$deliveryVersionName-release.apk") }
    }
}
dependencies {
    implementation("androidx.core:core:1.8.0")
    implementation("com.google.zxing:core:3.5.3")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(project(":core")) { exclude(group = "org.json") }
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
}
