## 1.9.3
- Сканирование нескольких IPv4/CIDR диапазонов за один запуск.
- До 4 диапазонов, максимум /24 на диапазон и 1024 уникальных адреса суммарно.
- Пересекающиеся диапазоны объединяются без повторных проверок.
- Обновлены подсказки и поле ввода диапазонов; ограничения и предупреждение об авторизованном сканировании видны в интерфейсе.

## 1.9.2
- Обновлена адаптивная иконка приложения: щит, глобус и сетевой радар.
- Обновлены фирменные цвета, статусные акценты и брендинг Scaner Proxy X.
- Сохранены applicationId, SQLite и SharedPreferences для установки поверх предыдущей версии при совпадении подписи.

# Changelog

## 1.9.1 (versionCode 12)

- First versioned update after 1.9.0.
- Android package identity remains `com.happ.proxyfinder` so this build can update an existing installation.
- The CI debug signing key remains stable between builds to preserve update compatibility.
- Existing SQLite and SharedPreferences data are retained; no storage migration is introduced in this release.

## 1.9.0 (versionCode 11)

- Previous baseline release.
