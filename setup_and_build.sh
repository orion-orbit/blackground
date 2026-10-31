#!/usr/bin/env bash
set -e

echo "==> 1. Setting up Directory Tree..."
mkdir -p .github/workflows
mkdir -p app/src/main/res/xml
mkdir -p app/src/main/res/values
mkdir -p app/src/main/cpp/include
mkdir -p app/src/main/cpp/external
mkdir -p app/src/main/java/com/minimal/hifi

echo "==> 2. Generating Root Build Configuration..."
cat << 'FILE_EOF' > settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "blackground"
include(":app")
FILE_EOF

cat << 'FILE_EOF' > build.gradle.kts
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
FILE_EOF

cat << 'FILE_EOF' > .gitignore
*.iml
.gradle
/local.properties
/.idea/
.DS_Store
/build
/captures
.externalNativeBuild
.cxx
app/build/
FILE_EOF

echo "==> 3. Generating app/build.gradle.kts..."
cat << 'FILE_EOF' > app/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.minimal.hifi"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.minimal.hifi"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20 -O3 -fno-exceptions"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    val media3Version = "1.3.0"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
}
FILE_EOF

echo "==> 4. Generating AndroidManifest.xml and Resources..."
cat << 'FILE_EOF' > app/src/main/res/values/strings.xml
<resources>
    <string name="app_name">Blackground</string>
</resources>
FILE_EOF

cat << 'FILE_EOF' > app/src/main/res/xml/usb_audio_device_filter.xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <usb-device class="1" subclass="1" />
</resources>
FILE_EOF

cat << 'FILE_EOF' > app/src/main/AndroidManifest.xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.minimal.hifi">

    <uses-feature android:name="android.hardware.usb.host" android:required="true" />

    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:label="@string/app_name"
        android:allowBackup="true"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.DeviceDefault.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
            </intent-filter>
            <meta-data
                android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
                android:resource="@xml/usb_audio_device_filter" />
        </activity>

        <service
            android:name=".HiFiUsbPlaybackService"
            android:exported="false"
            android:foregroundServiceType="mediaPlayback" />
    </application>
</manifest>
FILE_EOF

echo "==> 5. Generating CMakeLists.txt and C++ Engine..."
cat << 'FILE_EOF' > app/src/main/cpp/CMakeLists.txt
cmake_minimum_required(VERSION 3.22.1)
project("hifi_usb_engine" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
add_compile_options(-O3 -Wall -Wextra -fno-exceptions)

set(LIBUSB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/external/libusb/libusb)

add_library(usb1.0 STATIC
    ${LIBUSB_DIR}/core.c
    ${LIBUSB_DIR}/descriptor.c
    ${LIBUSB_DIR}/hotplug.c
    ${LIBUSB_DIR}/io.c
    ${LIBUSB_DIR}/sync.c
    ${LIBUSB_DIR}/strerror.c
    ${LIBUSB_DIR}/os/events_posix.c
    ${LIBUSB_DIR}/os/threads_posix.c
    ${LIBUSB_DIR}/os/linux_usbfs.c
    ${LIBUSB_DIR}/os/linux_netlink.c
)

target_include_directories(usb1.0 PUBLIC
    ${LIBUSB_DIR}
    ${LIBUSB_DIR}/os
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_compile_definitions(usb1.0 PRIVATE
    OS_LINUX=1
    _GNU_SOURCE=1
    HAVE_CLOCK_GETTIME=1
    DEFAULT_VISIBILITY=__attribute__\(\(visibility\("default"\)\)\)
)

add_library(hifi_usb_engine SHARED
    native-uac-driver.cpp
)

target_include_directories(hifi_usb_engine PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${LIBUSB_DIR}
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(hifi_usb_engine
    usb1.0
    ${log-lib}
    ${android-lib}
)
FILE_EOF

echo "==> 6. Generating GitHub Actions Cloud Build Workflow..."
cat << 'FILE_EOF' > .github/workflows/build-apk.yml
name: Build Blackground APK

on:
  push:
    branches: [ "main", "master" ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout Source
        uses: actions/checkout@v4

      - name: Fetch libusb Source & Header
        run: |
          mkdir -p app/src/main/cpp/external
          if [ ! -d "app/src/main/cpp/external/libusb" ]; then
            git clone --depth 1 https://github.com/libusb/libusb.git app/src/main/cpp/external/libusb
          fi
          mkdir -p app/src/main/cpp/include
          cp app/src/main/cpp/external/libusb/android/config.h app/src/main/cpp/include/config.h

      - name: Setup JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Setup Android SDK & NDK
        uses: android-actions/setup-android@v3

      - name: Generate Gradle Wrapper
        run: |
          gradle wrapper --gradle-version 8.5
          chmod +x gradlew

      - name: Build Debug APK
        run: ./gradlew assembleDebug --no-daemon --stacktrace

      - name: Upload APK Artifact
        uses: actions/upload-artifact@v4
        with:
          name: Blackground-decat << 'EOF' > setup_and_build.sh
#!/usr/bin/env bash
set -e

echo "==> 1. Setting up Directory Tree..."
mkdir -p .github/workflows
mkdir -p app/src/main/res/xml
mkdir -p app/src/main/res/values
mkdir -p app/src/main/cpp/include
mkdir -p app/src/main/cpp/external
mkdir -p app/src/main/java/com/minimal/hifi

echo "==> 2. Generating Root Build Configuration..."
cat << 'FILE_EOF' > settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "blackground"
include(":app")
FILE_EOF

cat << 'FILE_EOF' > build.gradle.kts
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
FILE_EOF

cat << 'FILE_EOF' > .gitignore
*.iml
.gradle
/local.properties
/.idea/
.DS_Store
/build
/captures
.externalNativeBuild
.cxx
app/build/
FILE_EOF

echo "==> 3. Generating app/build.gradle.kts..."
cat << 'FILE_EOF' > app/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.minimal.hifi"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.minimal.hifi"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20 -O3 -fno-exceptions"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    val media3Version = "1.3.0"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
}
FILE_EOF

echo "==> 4. Generating AndroidManifest.xml and Resources..."
cat << 'FILE_EOF' > app/src/main/res/values/strings.xml
<resources>
    <string name="app_name">Blackground</string>
</resources>
FILE_EOF

cat << 'FILE_EOF' > app/src/main/res/xml/usb_audio_device_filter.xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <usb-device class="1" subclass="1" />
</resources>
FILE_EOF

cat << 'FILE_EOF' > app/src/main/AndroidManifest.xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.minimal.hifi">

    <uses-feature android:name="android.hardware.usb.host" android:required="true" />

    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:label="@string/app_name"
        android:allowBackup="true"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.DeviceDefault.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
            </intent-filter>
            <meta-data
                android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
                android:resource="@xml/usb_audio_device_filter" />
        </activity>

        <service
            android:name=".HiFiUsbPlaybackService"
            android:exported="false"
            android:foregroundServiceType="mediaPlayback" />
    </application>
</manifest>
FILE_EOF

echo "==> 5. Generating CMakeLists.txt and C++ Engine..."
cat << 'FILE_EOF' > app/src/main/cpp/CMakeLists.txt
cmake_minimum_required(VERSION 3.22.1)
project("hifi_usb_engine" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
add_compile_options(-O3 -Wall -Wextra -fno-exceptions)

set(LIBUSB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/external/libusb/libusb)

add_library(usb1.0 STATIC
    ${LIBUSB_DIR}/core.c
    ${LIBUSB_DIR}/descriptor.c
    ${LIBUSB_DIR}/hotplug.c
    ${LIBUSB_DIR}/io.c
    ${LIBUSB_DIR}/sync.c
    ${LIBUSB_DIR}/strerror.c
    ${LIBUSB_DIR}/os/events_posix.c
    ${LIBUSB_DIR}/os/threads_posix.c
    ${LIBUSB_DIR}/os/linux_usbfs.c
    ${LIBUSB_DIR}/os/linux_netlink.c
)

target_include_directories(usb1.0 PUBLIC
    ${LIBUSB_DIR}
    ${LIBUSB_DIR}/os
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_compile_definitions(usb1.0 PRIVATE
    OS_LINUX=1
    _GNU_SOURCE=1
    HAVE_CLOCK_GETTIME=1
    DEFAULT_VISIBILITY=__attribute__\(\(visibility\("default"\)\)\)
)

add_library(hifi_usb_engine SHARED
    native-uac-driver.cpp
)

target_include_directories(hifi_usb_engine PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${LIBUSB_DIR}
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(hifi_usb_engine
    usb1.0
    ${log-lib}
    ${android-lib}
)
FILE_EOF

echo "==> 6. Generating GitHub Actions Cloud Build Workflow..."
cat << 'FILE_EOF' > .github/workflows/build-apk.yml
name: Build Blackground APK

on:
  push:
    branches: [ "main", "master" ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout Source
        uses: actions/checkout@v4

      - name: Fetch libusb Source & Header
        run: |
          mkdir -p app/src/main/cpp/external
          if [ ! -d "app/src/main/cpp/external/libusb" ]; then
            git clone --depth 1 https://github.com/libusb/libusb.git app/src/main/cpp/external/libusb
          fi
          mkdir -p app/src/main/cpp/include
          cp app/src/main/cpp/external/libusb/android/config.h app/src/main/cpp/include/config.h

      - name: Setup JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Setup Android SDK & NDK
        uses: android-actions/setup-android@v3

      - name: Generate Gradle Wrapper
        run: |
          gradle wrapper --gradle-version 8.5
          chmod +x gradlew

      - name: Build Debug APK
        run: ./gradlew assembleDebug --no-daemon --stacktrace

      - name: Upload APK Artifact
        uses: actions/upload-artifact@v4
        with:
          name: Blackground-debug-apk
          path: app/builcat << 'EOF' > setup_and_build.sh
#!/usr/bin/env bash
set -e

echo "==> 1. Setting up Directory Tree..."
mkdir -p .github/workflows
mkdir -p app/src/main/res/xml
mkdir -p app/src/main/res/values
mkdir -p app/src/main/cpp/include
mkdir -p app/src/main/cpp/external
mkdir -p app/src/main/java/com/minimal/hifi

echo "==> 2. Generating Root Build Configuration..."
cat << 'FILE_EOF' > settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "blackground"
include(":app")
FILE_EOF

cat << 'FILE_EOF' > build.gradle.kts
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
FILE_EOF

cat << 'FILE_EOF' > .gitignore
*.iml
.gradle
/local.properties
/.idea/
.DS_Store
/build
/captures
.externalNativeBuild
.cxx
app/build/
FILE_EOF

echo "==> 3. Generating app/build.gradle.kts..."
cat << 'FILE_EOF' > app/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.minimal.hifi"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.minimal.hifi"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20 -O3 -fno-exceptions"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    val media3Version = "1.3.0"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
}
FILE_EOF

echo "==> 4. Generating AndroidManifest.xml and Resources..."
cat << 'FILE_EOF' > app/src/main/res/values/strings.xml
<resources>
    <string name="app_name">Blackground</string>
</resources>
FILE_EOF

cat << 'FILE_EOF' > app/src/main/res/xml/usb_audio_device_filter.xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <usb-device class="1" subclass="1" />
</resources>
FILE_EOF

cat << 'FILE_EOF' > app/src/main/AndroidManifest.xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.minimal.hifi">

    <uses-feature android:name="android.hardware.usb.host" android:required="true" />

    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:label="@string/app_name"
        android:allowBackup="true"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.DeviceDefault.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
            </intent-filter>
            <meta-data
                android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
                android:resource="@xml/usb_audio_device_filter" />
        </activity>

        <service
            android:name=".HiFiUsbPlaybackService"
            android:exported="false"
            android:foregroundServiceType="mediaPlayback" />
    </application>
</manifest>
FILE_EOF

echo "==> 5. Generating CMakeLists.txt and C++ Engine..."
cat << 'FILE_EOF' > app/src/main/cpp/CMakeLists.txt
cmake_minimum_required(VERSION 3.22.1)
project("hifi_usb_engine" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
add_compile_options(-O3 -Wall -Wextra -fno-exceptions)

set(LIBUSB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/external/libusb/libusb)

add_library(usb1.0 STATIC
    ${LIBUSB_DIR}/core.c
    ${LIBUSB_DIR}/descriptor.c
    ${LIBUSB_DIR}/hotplug.c
    ${LIBUSB_DIR}/io.c
    ${LIBUSB_DIR}/sync.c
    ${LIBUSB_DIR}/strerror.c
    ${LIBUSB_DIR}/os/events_posix.c
    ${LIBUSB_DIR}/os/threads_posix.c
    ${LIBUSB_DIR}/os/linux_usbfs.c
    ${LIBUSB_DIR}/os/linux_netlink.c
)

target_include_directories(usb1.0 PUBLIC
    ${LIBUSB_DIR}
    ${LIBUSB_DIR}/os
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_compile_definitions(usb1.0 PRIVATE
    OS_LINUX=1
    _GNU_SOURCE=1
    HAVE_CLOCK_GETTIME=1
    DEFAULT_VISIBILITY=__attribute__\(\(visibility\("default"\)\)\)
)

add_library(hifi_usb_engine SHARED
    native-uac-driver.cpp
)

target_include_directories(hifi_usb_engine PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${LIBUSB_DIR}
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(hifi_usb_engine
    usb1.0
    ${log-lib}
    ${android-lib}
)
FILE_EOF

echo "==> 6. Generating GitHub Actions Cloud Build Workflow..."
cat << 'FILE_EOF' > .github/workflows/build-apk.yml
name: Build Blackground APK

on:
  push:
    branches: [ "main", "master" ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout Source
        uses: actions/checkout@v4

      - name: Fetch libusb Source & Header
        run: |
          mkdir -p app/src/main/cpp/external
          if [ ! -d "app/src/main/cpp/external/libusb" ]; then
            git clone --depth 1 https://github.com/libusb/libusb.git app/src/main/cpp/external/libusb
          fi
          mkdir -p app/src/main/cpp/include
          cp app/src/main/cpp/external/libusb/android/config.h app/src/main/cpp/include/config.h

      - name: Setup JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Setup Android SDK & NDK
        uses: android-actions/setup-android@v3

      - name: Generate Gradle Wrapper
        run: |
          gradle wrapper --gradle-version 8.5
          chmod +x gradlew

      - name: Build Debug APK
        run: ./gradlew assembleDebug --no-daemon --stacktrace

      - name: Upload APK Artifact
        uses: actions/upload-artifact@v4
        with:
          name: Blackground-debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk
FILE_EOF

echo "==> Complete! Setup script successfully initialized base project structure."
