plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.urlxl.mail"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.urlxl.mail"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val mailImapHost = providers.gradleProperty("mail.imap.host").orElse("").get()
        val mailImapPort = providers.gradleProperty("mail.imap.port").orElse("993").get().toInt()
        val mailSmtpHost = providers.gradleProperty("mail.smtp.host").orElse("").get()
        val mailSmtpPort = providers.gradleProperty("mail.smtp.port").orElse("587").get().toInt()
        val mailUsername = providers.gradleProperty("mail.username").orElse("").get()
        val mailPassword = providers.gradleProperty("mail.password").orElse("").get()
        val mailFolder = providers.gradleProperty("mail.imap.folder").orElse("INBOX").get()

        buildConfigField("String", "MAIL_IMAP_HOST", "\"$mailImapHost\"")
        buildConfigField("int", "MAIL_IMAP_PORT", mailImapPort.toString())
        buildConfigField("String", "MAIL_SMTP_HOST", "\"$mailSmtpHost\"")
        buildConfigField("int", "MAIL_SMTP_PORT", mailSmtpPort.toString())
        buildConfigField("String", "MAIL_USERNAME", "\"$mailUsername\"")
        buildConfigField("String", "MAIL_PASSWORD", "\"$mailPassword\"")
        buildConfigField("String", "MAIL_IMAP_FOLDER", "\"$mailFolder\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE.md"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.cardview)
    implementation(libs.angus.mail)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}