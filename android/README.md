# Сборка VoiceKB для Android

Описание и варианты установки есть в [основном README](../README.md). Команды ниже запускаются из каталога `android/`.

## Локальная сборка и проверка

Можно использовать JDK 17 или новее. Установите Android SDK 37, NDK r29 (`29.0.14206865`), Android Build Tools и Rust с целевой платформой ARM64 Android. Настройте `ANDROID_HOME` или `ANDROID_SDK_ROOT`. Для NDK в отдельном каталоге укажите `VOICE_KB_NDK`.

```sh
rustup target add aarch64-linux-android
./gradlew :app:assembleDebug :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

На подключённом ARM64-устройстве можно проверить потоковую диктовку и FUTO. Тестовый APK использует короткие синтетические аудиофразы и заданные траектории свайпа.

```sh
./gradlew :app:assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w dev.voicekb.android.test/ly.anoma.hex.DeviceChecks
```

## Release APK

`assembleRelease` создаёт неподписанный APK. Пример подписи собственным ключом для новой установки. Команды предполагают, что `zipalign` и `apksigner` из Android Build Tools доступны в `PATH`.

```sh
./gradlew :app:assembleRelease
zipalign -P 16 -f 4 app/build/outputs/apk/release/app-release-unsigned.apk /tmp/voicekb-aligned.apk
apksigner sign --ks /path/to/release.p12 --out /tmp/VoiceKB-arm64.apk /tmp/voicekb-aligned.apk
apksigner verify --verbose --print-certs /tmp/VoiceKB-arm64.apk
adb install -r /tmp/VoiceKB-arm64.apk
```

Для обновления установленной версии используйте совместимую подпись. Android сохраняет данные при обновлении APK с тем же ключом или корректной цепочкой ротации. [Подпись APK и ротация ключей](https://developer.android.com/tools/apksigner)

## Структура

- `app/src/main/java/ly/anoma/hex/keyboard` — IME, режимы Fold, ввод и управление жестами.
- `app/src/main/java/ly/anoma/hex/speech` — запись, потоковый tinyasr и оформление черновика.
- `app/src/main/java/ly/anoma/hex/typing` — FUTO, словарный декодер, подсказки и самообучение.
- `app/src/main/cpp` и `vendor/tinyasr` — JNI-обвязка и исходники движка диктовки.
- `app/src/main/assets` — модели, словари и условия распространения.
