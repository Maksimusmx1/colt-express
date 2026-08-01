# Colt Express — онлайн

Онлайновая версия настольной игры Colt Express: сервер (Kotlin + Ktor) и клиент (Android, Compose).
Реализована базовая игра на 3–6 игроков по правилам `Colt_Express_Rules.pdf`.

```
├── server/    — сервер (Kotlin + Ktor): комнаты, движок правил, WebSocket-протокол
└── android/   — клиент (Kotlin + Jetpack Compose): подключение, комнаты, игра
```

## Технологии

| Слой      | Стек                                                        |
|-----------|-------------------------------------------------------------|
| Сервер    | Kotlin 2.0, Ktor 3 (Netty), kotlinx.serialization, Logback  |
| Клиент    | Jetpack Compose (BOM), Ktor Client (OkHttp), MVVM + ViewModel |
| Сборка    | Gradle 8.9 (wrapper), JDK 17+                               |

## Запуск сервера

```powershell
cd server
.\gradlew.bat run          # слушает 0.0.0.0:8080 (с --no-daemon на JDK 19)
```

Проверка: `GET http://localhost:8080/health` → `{"status":"ok","players":0,"rooms":0}`.

Собрать дистрибутив: `.\gradlew.bat installDist` → `build\install\colt-express-server\bin\colt-express-server.bat`.

Автосимуляция партии (движок правил):

```powershell
java -cp "build\install\colt-express-server\lib\*" com.coltexpress.server.Sim 3 4
# 3 партии, 4 игрока (аргументы: количество игр, число игроков)
```

## Запуск клиента

Откройте `android/` в Android Studio и запустите на эмуляторе. По умолчанию клиент
подключается к `ws://10.0.2.2:8080/ws` (`10.0.2.2` = localhost хоста из эмулятора).
Для физического устройства укажите LAN-IP хоста в `GameClient.DEFAULT_URL`.

## Игровые правила (реализовано)

- Поезд = локомотив + столько вагонов, сколько игроков; локомотив = вагон (внутри и крыша).
- 5 раундов из колоды; фазы «Планирование» → «Ограбление»; карты применяются в порядке выкладывания.
- Режимы раундов: стандартный, туннель (втёмную), разгон (PLAY2 / DRAW6 / DRAW3_PLAY1), перевод стрелки (реверс порядка).
- 10 карт действий на игрока: Движение×2, Лестница×2, Выстрел×2, Кража×2, Удар×1, Шериф×1.
- 6 пуль своего цвета на игрока; в свой ход можно выложить карту или добрать 3 карты.
- Шериф: при встрече бандиты спасаются на крышу и получают нейтральную пулю (13 нейтральных пуль).
- Подсчёт: сумма добычи + 1000$ самому меткому (меньше всего своих пуль осталось); ничья — меньше полученных пуль.
- Стартовый кошелёк 250$ каждому; добыча в вагонах и колода раундов заданы в `Setup.kt`
  (точные таблички вагонов в PDF не приводятся — приближение).

## Протокол (JSON, WebSocket)

Все сообщения содержат поле-дискриминатор `"type"` (см. `@SerialName`). Полное описание —
`server/src/main/kotlin/com/coltexpress/server/protocol/Messages.kt` (зеркало — в клиенте).

### Клиент → сервер
```json
{"type":"CreateRoom","nickname":"Alice","maxPlayers":4}
{"type":"JoinRoom","roomId":"abc12345","nickname":"Bob"}
{"type":"StartGame"}
{"type":"PlayAction","cardType":"ROB"}
{"type":"DrawCards"}
{"type":"MakeChoice","choiceId":"c0","value":"F"}
{"type":"Say","text":"привет"}
```

### Сервер → клиент (основные)
```json
{"type":"Welcome","playerId":"...","nickname":"Alice"}
{"type":"RoomUpdate","roomId":"abc12345","players":[...],"maxPlayers":4,"phase":"LOBBY","ownerId":"..."}
{"type":"GameStarted","players":[...],"rounds":5,"firstPlayerId":"..."}
{"type":"RoundStart","round":1,"mode":"STANDARD","turns":3,"firstPlayerId":"..."}
{"type":"PlanningTurn","playerId":"...","mode":"STANDARD"}
{"type":"HandUpdate","hand":[{"uid":"...","type":"MOVE"}],"deckSize":4,"ownBullets":6}
{"type":"CardPlayed","playerId":"...","cardType":"ROB","faceDown":false}
{"type":"ChoiceRequired","playerId":"...","choiceId":"c0","kind":"MOVE_DIRECTION","options":["B","F"],"cardType":"MOVE","context":"ROBBERY"}
{"type":"GameEventMsg","event":{"type":"BanditMoved","playerId":"...","fromCar":3,"toCar":2,"onRoof":false}}
{"type":"GameEnded","results":[...],"winnerId":"..."}
{"type":"Chat","nickname":"Alice","text":"привет"}
{"type":"Error","message":"..."}
```

## Примечания

- `android/` не собирается без Android SDK; `server/` собирается и проверен на JDK 19.
  Gradle-команды сервера на этой машине требуют `--no-daemon` (test-worker падает с JDK 19),
  кириллические пути — через 8.3-имена (`C:\Users\291A~1\...`).
- `usesCleartextTraffic=true` включён только для локальной разработки (`ws://` без TLS);
  для продакшена используйте WSS.
- Оба модуля — отдельные Gradle-проекты (свои wrapper'ы), т.к. сервер не требует Android SDK.
