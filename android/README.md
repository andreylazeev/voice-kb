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

### Проверка автокоррекции

Набор содержит 128 812 случаев. Для русских пар использованы тестовый `test_pairs.csv` из Mendeley, RUSpellRU и MultidomainGold из версии `0f7e587709ee07db253041baf01b376970964176`. Для английского нужны `missp.dat` и `wikipedia.dat`. [Mendeley](https://data.mendeley.com/datasets/mpwdrr6y3v/1) · [RUSpellRU и MultidomainGold](https://huggingface.co/datasets/BW/spellcheck_benchmark_actualized/tree/0f7e587709ee07db253041baf01b376970964176/data) · [Birkbeck и Wikipedia](https://titan.dcs.bbk.ac.uk/~roger/corpora.html)

Скопируйте `en.hextm` и `ru.hextm` из коммита `581c144` в `/tmp/voicekb-baseline-models`. Скрипт использует их для воспроизводимых ошибок соседних клавиш, пропусков, повторов и перестановок букв. В опубликованных корпусах он сохраняет однозначные случаи с заменой одного слова и проверяет правильные слова отдельно. Скачанные корпуса и подготовленный TSV хранятся локально.

```sh
python3 scripts/prepare-autocorrect-replay.py --output /tmp/autocorrect.tsv \
  --birkbeck missp.dat --wikipedia wikipedia.dat --russian-pairs test_pairs.csv \
  --ruspell ruspell.json --russian-multidomain multidomain.json \
  --keyboard-models /tmp/voicekb-baseline-models
VOICEKB_AUTOCORRECT_REPLAY=/tmp/autocorrect.tsv \
  ./gradlew :app:testDebugUnitTest --rerun --tests '*AutocorrectReplayTest'
```

Прогон имитирует касания по центрам клавиш и вызывает обычный декодер VoiceKB. Проценты показывают точное совпадение после завершения слова без учёта регистра. Строки `AUTOCORRECT` в отчёте теста содержат размер группы, число совпадений и число слов, оставленных как введено.

| Случаи | 0.1.17 | 0.1.18 до расширения | 0.1.18 с расширением |
| --- | ---: | ---: | ---: |
| EN, 33 571 опубликованная опечатка | 19,02% | 24,16% | 24,16% |
| RU, 24 884 опубликованные опечатки | 5,10% | 6,63% | 7,35% |
| EN, 9 860 имитированных опечаток на клавиатуре | 89,56% | 89,56% | 89,56% |
| RU, 9 958 имитированных опечаток на клавиатуре | 87,75% | 87,40% | 91,53% |
| EN, 6 907 правильных слов из опубликованных корпусов | 99,83% | 99,83% | 99,83% |
| RU, 23 814 правильных слов из опубликованных корпусов | 92,59% | 92,55% | 94,50% |
| RU, 9 958 правильных слов из набора имитации | 99,92% | 99,92% | 100,00% |

В русской выборке Mendeley доля сохранённых правильных слов снизилась с 93,98% до 93,50%. В MultidomainGold она выросла с 80,21% до 98,74%.

Параметр `--double-neighbour` добавляет к этому прогону 2 500 русских опечаток с двумя нажатиями соседних клавиш и 2 500 соответствующих правильных слов. Генератор использует тот же зафиксированный словарь и выдаёт одинаковые примеры при каждом запуске.

После добавления поиска двух соседних промахов число точных исправлений в этой группе выросло с 29 до 791 из 2 500 (с 1,16% до 31,64%). Все 2 500 правильных слов сохранились. В исходном прогоне число точных исправлений русских опечаток осталось прежним, а сохранение правильных слов из опубликованных корпусов снизилось с 22 504 до 22 499 из 23 814.

В этой сборке запрет на изменение крайних букв заменён проверкой правдоподобия ввода. По пробелу два соседних промаха исправляются, если вариант явно лидирует, а во введённом слове есть сочетания, отсутствующие в русских словарях. При правдоподобном написании вариант может появиться в полоске подсказок.

Точные исправления выросли с 791 до 1 734 из 2 500 (69,36%). На дополнительной выборке с другими словами получилось 1 728 из 2 500 (69,12%). В обеих выборках сохранились все 2 500 правильных слов. В опубликованных корпусах сохранилось 22 504 из 23 814 правильных слов, на пять больше, чем в предыдущей сборке. Число точных исправлений в исходном прогоне осталось прежним.

Разбор оставшихся 766 пропусков показал, что в 433 случаях ввод прошёл проверку правдоподобия, в 225 случаях нужный вариант получил слишком низкую оценку, а в 37 случаях его отрыв от второго варианта был мал. Новый проход оценивает сочетания вокруг каждого из двух промахов отдельно и сравнивает, как часто тройки букв встречаются во введённом слове и исправлении.

Точность выросла до 1 990 из 2 500 (79,60%), а на дополнительной выборке до 1 964 из 2 500 (78,56%). Контрольная проверка охватывает исходные корпуса, правильные слова из имитаций и 4 364 дополнительных редких слова и имени из Hunspell вне словаря клавиатуры. Все 41 727 правильных вводов, которые предыдущая сборка оставляла как есть, сохранились. В Mendeley появилось восемь новых неверных замен опечаток при том же числе точных исправлений.

### Русский словарь автокоррекции

Словоформы берутся из частотного списка Козиева и проверяются по словарям Danakt и Hunspell. `ru.correction.hextm` добавляет 110 000 вариантов исправления, а `ru.valid` помогает сохранять ещё 313 945 редких словоформ. `ru.patterns` хранит частоты троек букв и отмечает четвёрки, встречающиеся в этих словоформах и в основном словаре. [Исходные файлы и лицензии](app/src/main/assets/licenses/russian-swipe-vocabulary.txt)

Для сборки установите Hunspell и сохраните `index.dic` и `index.aff` из указанной версии словаря как `ru.dic` и `ru.aff` рядом друг с другом.

```sh
python3 scripts/build-russian-swipe-vocabulary.py term2freq.dat russian.txt \
  --hunspell /path/to/ru --typing-extra /tmp/ru-correction.tsv \
  --valid-output app/src/main/assets/typing/ru.valid
python3 scripts/build-cyrillic-typing-models.py --russian-typing-extra /tmp/ru-correction.tsv
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
