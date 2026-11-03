# CI execution image for HaramVeil pipelines: JDK 21 + Android SDK 36 baked in.
#
# Built and pushed to the project's own GitLab container registry by the
# `prepare-ci-image` job (automatic whenever this file changes, manual otherwise).
# Using this image means pipeline jobs need NO SDK download, NO apt installs and
# NO .android-sdk cache — they start Gradle straight away.
#
# Rebuild manually: CI/CD -> Pipelines -> Run pipeline -> trigger prepare-ci-image.
# Suggest also scheduling a monthly rebuild to pick up SDK tool updates.
FROM eclipse-temurin:21-jdk

ENV ANDROID_HOME=/opt/android-sdk

RUN apt-get update -qq \
    && apt-get install -y -qq curl unzip \
    && rm -rf /var/lib/apt/lists/*

RUN curl -sL -o /tmp/cmdtools.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" \
    && mkdir -p "$ANDROID_HOME" \
    && unzip -q /tmp/cmdtools.zip -d "$ANDROID_HOME" \
    && rm /tmp/cmdtools.zip \
    && yes | "$ANDROID_HOME/cmdline-tools/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --licenses >/dev/null \
    && "$ANDROID_HOME/cmdline-tools/bin/sdkmanager" --sdk_root="$ANDROID_HOME" \
        "platform-tools" "platforms;android-36" "build-tools;36.0.0" >/dev/null
