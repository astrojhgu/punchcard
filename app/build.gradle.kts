plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gujunhua.attendance"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gujunhua.attendance"
        minSdk = 26
        // 目标机是 Android 13+ 的小米 HyperOS。targetSdk 34 而不是 35：
        // 避免强制 edge-to-edge 带来的额外适配成本，行为更可预测。
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
    }

    /**
     * 签名密钥固定放在项目里，不用 AGP 默认的 ~/.android/debug.keystore。
     *
     * 为什么要这样：默认那个位置不受项目控制。实测踩过一次——15:29 构建 release 时
     * AGP 用的 keystore 到 19:32 已经不存在了，AGP 就自动新建了一个，
     * 结果新旧 APK 证书不一致，`adb install -r` 直接
     * INSTALL_FAILED_UPDATE_INCOMPATIBLE，只能卸载重装（数据全丢）。
     *
     * debug 和 release 共用同一套密钥，这样调试包和发布包可以互相覆盖安装、
     * 数据保留，调试时不用来回卸载。
     *
     * 密码是明文的：个人自用、不发布到任何商店，密钥本身也不出这台机器。
     *
     * 密钥文件**不进版本库**（见 .gitignore）。所以 clone 下来的副本没有它，
     * 这时回退到 AGP 的默认 debug 签名，保证别人（或你在新机器上）能构建成功；
     * 代价是签出来的包跟已安装版本的证书不同，装不上去，只能卸载重装。
     */
    val personalKeystore = rootProject.file("tools/attendance.keystore")

    signingConfigs {
        if (personalKeystore.exists()) {
            create("personal") {
                storeFile = personalKeystore
                storePassword = "attendance"
                keyAlias = "attendance"
                keyPassword = "attendance"
            }
        }
    }

    val buildSigning = if (personalKeystore.exists()) {
        signingConfigs.getByName("personal")
    } else {
        logger.warn(
            "⚠ 找不到 tools/attendance.keystore，回退到 AGP 默认 debug 签名。" +
                "这样签出来的包无法覆盖安装已装版本，只能卸载重装。"
        )
        signingConfigs.getByName("debug")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = buildSigning
        }
        release {
            // 不混淆：个人自用，可读的崩溃栈比省几百 KB 重要。
            isMinifyEnabled = false
            signingConfig = buildSigning
        }
    }

    lint {
        // 不跑 release 的 lint 关卡。理由有两条：
        //  1. 这是自己侧载的 app，lint 的唯一用途是上架前的自查；
        //  2. lintVitalRelease 会额外拉一堆 lint-gradle 依赖，网络一不稳就直接把
        //     release 构建卡到超时（实测 24 分钟后以 Network is unreachable 失败）。
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

/**
 * 考勤表模板只有一个真源：项目根目录的 template.docx（原方案的 run.py 也用它）。
 *
 * 构建时同步到 assets。以前是两份副本，改模板得记着改两处，
 * 迟早出现「app 里用的是旧模板、基准对比用的却是新模板」这种最难查的错。
 * assets 下那份已加入 .gitignore，不要再手工编辑它。
 */
val syncTemplate = tasks.register<Copy>("syncTemplate") {
    from(rootProject.file("template.docx"))
    into(layout.projectDirectory.dir("src/main/assets"))
}

tasks.named("preBuild") {
    dependsOn(syncTemplate)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
    // Android 自带的 org.json 在 JVM 单元测试里只是抛异常的 stub，
    // 必须显式引入真实实现，否则 JSON 相关的单测全部假失败。
    testImplementation("org.json:json:20240303")
}
