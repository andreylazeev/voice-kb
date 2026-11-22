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

### Проверка русского свайпа

Для повтора нужны `valid.jsonl` и `valid.ref` из полного набора NeuroSwipe 2023. Кодирование жестов выполняется на ARM64 с установленной клавиатурой и тестовым APK. [Данные Yandex Cup](https://disk.yandex.ru/d/IYiSpLob-zAxqg)

```sh
python3 scripts/prepare-russian-swipe-replay.py valid.jsonl valid.ref /tmp/gestures.jsonl
adb shell mkdir -p /sdcard/Android/data/dev.voicekb.android/files/swipe-benchmark
adb push /tmp/gestures.jsonl /sdcard/Android/data/dev.voicekb.android/files/swipe-benchmark/
adb shell am instrument -w -e swipe_benchmark true dev.voicekb.android.test/ly.anoma.hex.DeviceChecks
adb pull /sdcard/Android/data/dev.voicekb.android/files/swipe-benchmark/emissions.bin /tmp/emissions.bin
VOICEKB_SWIPE_EMISSIONS=/tmp/emissions.bin VOICEKB_SWIPE_PARTITION=validation \
  ./gradlew :app:testDebugUnitTest --rerun --tests '*RussianSwipeQualityTest'
```

Результаты выводятся в отчёте `app/build/reports/tests/testDebugUnitTest/index.html`. `calibration` выбирает 3 000 жестов для настройки, `validation` выбирает оставшиеся 7 000.

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

### Публикация через GitHub Actions

Поменяйте `versionName` в формате `X.Y.Z` и увеличьте `versionCode` в [`app/build.gradle.kts`](app/build.gradle.kts). После отправки изменений в `main` откройте **Actions → Android Release → Run workflow** и выберите `main`. Можно запустить и из терминала.

```sh
gh workflow run release.yml --ref main
```

Workflow берёт версию из коммита запуска. Если релиз с тегом `vX.Y.Z` уже есть, workflow пропускает сборку. Для новой версии он запускает тесты, подписывает APK ключом из GitHub Actions Secrets и публикует APK и `SHA256SUMS.txt`.

## Структура

- `app/src/main/java/ly/anoma/hex/keyboard` — IME, режимы Fold, ввод и управление жестами.
- `app/src/main/java/ly/anoma/hex/speech` — запись, потоковый tinyasr и оформление черновика.
- `app/src/main/java/ly/anoma/hex/typing` — FUTO, словарный декодер, подсказки и самообучение.
- `app/src/main/cpp` и `vendor/tinyasr` — JNI-обвязка и исходники движка диктовки.
- `app/src/main/assets` — модели, словари и условия распространения.
