# Лаунчер на ТВ: установка, обновление, откат

ТВ: TCL 65C8K (G08_4K_GB), Google TV 14, Magisk 31.1. ADB по сети: `adb connect 192.168.1.230:5555`
(IP смотрите в «Настройки → Сеть и Интернет», если сменился).

## Как стоит сейчас

- Пакет `com.muzdai.launcher`, системное привилегированное приложение: модуль Magisk `muzdai_launcher`
  (`/data/adb/modules/muzdai_launcher`) кладёт APK в `/system/priv-app/MuzdaiLauncher` и allowlist
  `/system/etc/permissions/privapp-permissions-com.muzdai.launcher.xml` (DELETE_PACKAGES, WRITE_SECURE_SETTINGS).
  Установленная копия в `/data` — обновление системного приложения.
- Home: `com.muzdai.launcher/.MainActivity`. Служба кнопок пульта: `com.muzdai.launcher/.InputKeyService`
  (`settings get secure enabled_accessibility_services`).
- `com.google.android.tungsten.setupwraith` отключён (`pm disable-user --user 0`): его RecoveryActivity с приоритетом
  HOME зацикливалась при каждом нажатии Home. Стоковый `com.google.android.apps.tv.launcherx` удалён для user 0.
- Обои: `/sdcard/Android/data/com.muzdai.launcher/files/wallpapers/` (`<group>-*.jpg`, `captions.txt`).
- Ключ подписи релизов: `/root/apk-sign/muzdai-release.keystore` (секреты в репозитории GitHub).

## Обновление

Новая версия ставится поверх: `adb install -r vpro-tv-launcher-<версия>.apk`. Модуль Magisk переустанавливать не
нужно, пока манифест не запросит новое привилегированное разрешение. **Важно:** на ТВ
`ro.control_privapp_permissions=enforce`. Если новая версия попросит ещё одно привилегированное разрешение, его нужно
сначала добавить в `magisk/system/etc/permissions/privapp-permissions-com.muzdai.launcher.xml`, пересобрать модуль
(`magisk/build-module.sh <apk>`), поставить его (`magisk --install-module`) и перезагрузить — иначе ТВ не загрузится.

## Откат

- Убрать системный статус (лаунчер останется обычным приложением):
  `adb shell su -c 'touch /data/adb/modules/muzdai_launcher/remove'`, затем перезагрузка.
- ТВ не грузится из-за модуля: Magisk safe mode (модули отключаются) или
  `adb wait-for-device shell su -c 'magisk --remove-modules'`.
- Вернуть стоковый Home: `adb shell pm enable --user 0 com.google.android.tungsten.setupwraith`,
  `adb shell cmd package install-existing com.google.android.apps.tv.launcherx`, затем
  `adb shell cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/.home.HomeActivity`.
- Назначить лаунчер Home заново: `adb shell cmd package set-home-activity --user 0 com.muzdai.launcher/.MainActivity`.
- Включить службу кнопок заново:
  `adb shell settings put secure enabled_accessibility_services com.muzdai.launcher/.InputKeyService` и
  `adb shell settings put secure accessibility_enabled 1`.
