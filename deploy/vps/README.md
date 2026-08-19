# Деплой Colt Express на VPS

Готовые файлы для поднятия игрового сервера на Linux-VPS (Debian/Ubuntu).
Схема: сервер Ktor слушает `0.0.0.0:8080` (WebSocket `/ws`, здоровье `/health`,
скачивание клиента `/apk`), клиенты подключаются напрямую по `ws://<VPS_IP>:8080`.

## Файлы

| Файл                                        | Где используется | Назначение |
|---------------------------------------------|------------------|------------|
| `deploy\vps\build.ps1`                      | Windows (ваша машина) | Собирает сервер (`installDist`) и APK (`assembleDebug`), пакует всё в `deploy\vps\dist\` |
| `deploy\vps\upload.ps1`                     | Windows (ваша машина) | Загружает `dist\` на VPS по scp и запускает `install.sh` |
| `deploy\vps\install.sh`                     | VPS               | Ставит JDK 17, пользователя `colt`, распаковывает сервер в `/opt/colt/server`, регистрирует и запускает systemd-сервис |
| `deploy\vps\colt-express.service`           | VPS               | systemd unit: автозапуск, перезапуск при падении, `COLT_APK_PATH` |
| `deploy\vps\nginx-colt.conf`                | VPS (опционально) | TLS reverse proxy для сценария A (`wss://` + `https://`) |
| `deploy\vps\README.md`                      | —                 | Этот файл |

Промежуточный результат: `deploy\vps\dist\` (создаётся `build.ps1`,
в git не коммитится):
- `colt-express-server.tar.gz` — дистрибутив сервера (bin + lib)
- `app-debug.apk` — клиент, раздаваемый через `/apk`
- копии `install.sh`, `colt-express.service`, `nginx-colt.conf`

## Быстрый старт (2 команды)

### 1. Сборка (Windows)

```powershell
cd "C:\Users\максим\OneDrive\Документы\Default Project"
powershell -ExecutionPolicy Bypass -File .\deploy\vps\build.ps1
```

Требования: JDK 17+ в PATH (уже используется для сервера), Android SDK
(нужен `local.properties` в `android\`), Windows 10 1803+ (встроенный `tar.exe`).
Выполняется: `installDist` сервера → `assembleDebug` клиента → упаковка.

### 2. Установка на VPS

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy\vps\upload.ps1 -Server <IP_или_домен_VPS> -User root
```

Что делает: создаёт `/tmp/colt-deploy` на VPS, копирует туда файлы по scp,
запускает `install.sh` (через sudo). Требования: OpenSSH Client на Windows,
доступ к VPS по ключу или паролю.

Проверка после установки:

```bash
curl http://<IP_VPS>:8080/health
# {"status":"ok","players":0,"rooms":0,"buildNumber":8}
journalctl -u colt-express -f    # логи сервиса
```

## Подключение клиента

В приложении на экране подключения в поле адреса укажите:
`<IP_VPS>:8080` (без схемы и `/ws` — клиент сам добавит `ws://` и `/ws`).
APK новой версии клиента скачивается прямо в приложении через `/apk`
(сервер сам раздаёт `app-debug.apk` из `/opt/colt/apk`).

## Сценарий A: wss/TLS через публичный домен (клиент сборки 9+)

Клиент с этой сборки сам выбирает схему по вводу:
без схемы или `http`/`ws` → `ws://`; `https`/`wss` → `wss://`.

1. Заведите домен и `A`-запись `colt.домен` → IP VPS (или бесплатный
   `colt.<IP>.nip.io` — резолвится в ваш IP без регистрации домена).
2. На VPS (подробно — в шапке `deploy\vps\nginx-colt.conf`):
   ```bash
   sudo apt-get install -y nginx certbot python3-certbot-nginx
   sudo cp /tmp/colt-deploy/nginx-colt.conf /etc/nginx/sites-available/colt
   sudo sed -i 's/colt.example.com/YOUR_DOMAIN/g' /etc/nginx/sites-available/colt
   sudo ln -s /etc/nginx/sites-available/colt /etc/nginx/sites-enabled/
   sudo rm -f /etc/nginx/sites-enabled/default
   sudo nginx -t && sudo systemctl reload nginx
   sudo certbot --nginx -d YOUR_DOMAIN
   ```
3. Проверка: `curl https://YOUR_DOMAIN/health`.
4. В приложении введите адрес `YOUR_DOMAIN` (без порта) — подключение и
   скачивание `/apk` пойдут по TLS.

Сертификат не нужно перевыпускать при обновлении клиента/сервера —
он привязан только к домену; certbot продлевает его сам (90 дней).


## Обновление версии

1. Поднимите `versionCode` в `android/app/build.gradle.kts` (он же задаёт
   `BUILD_NUMBER` клиента через `BuildConfig`) и `SERVER_BUILD_NUMBER` в
   `server/src/main/kotlin/com/coltexpress/server/Application.kt` (должны совпадать).
   Без увеличения `versionCode` Android не установит новую сборку поверх старой.
2. Повторите `build.ps1` → `upload.ps1`.
3. `install.sh` перезапишет `/opt/colt/server`, `/opt/colt/apk/app-debug.apk`
   и перезапустит сервис (комнаты не сохраняются — это in-memory сервер).

## Частые вопросы

- **Не пингуется/не коннектится** — проверьте, что VPS-провайдер открыл порт 8080
  в своей панели (не только ufw на сервере). ufw открывается автоматически:
  `sudo ufw allow 8080/tcp`.
- **Хочу HTTPS (wss://)** — включите сценарий A: публичный домен + nginx +
  certbot (раздел выше). Клиент сборки 9+ сам переключается на `wss://`,
  если в адресе указана схема `https`/`wss`.
- **Только IP без домена** — используйте `colt.<IP>.nip.io` как домен для
  сценария A, либо подключение без TLS напрямую на порт 8080.
- **Логи** — `journalctl -u colt-express -f`; stderr/stdout сервиса пишутся в journald.
- **Перезапуск вручную** — `sudo systemctl restart colt-express`.
- **Сброс состояния** — сервер хранит комнаты в памяти; перезапуск сервиса
  полностью очищает всё, включая bot-автоматизацию.
