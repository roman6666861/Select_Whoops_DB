# Select_Whoops_DB

Серверная реляционная СУБД на Java 25 (сборка — Gradle). Совместима с протоколом PostgreSQL:
подключаться можно через `psql`, DBeaver или JDBC-драйвер PostgreSQL.

Основа: Apache Calcite (SQL), RocksDB (хранилище), Netty (сеть).

## Сборка и запуск

```shell
./gradlew build                     # сборка и тесты
./gradlew :swdb-server:installDist  # дистрибутив в swdb-server/build/install/swdb
swdb-server/build/install/swdb/bin/swdb --port 5432 --data-dir data
```

```shell
psql -h 127.0.0.1 -p 5432 -c "SELECT 1"
```

JDBC: `jdbc:postgresql://127.0.0.1:5432/swdb?preferQueryMode=simple`
(extended query protocol пока не поддерживается).

## Модули

| Модуль | Назначение |
|---|---|
| `swdb-common` | общие типы и исключения |
| `swdb-storage` | key-value хранилище на RocksDB |
| `swdb-txn` | транзакции |
| `swdb-catalog` | каталог: схемы, таблицы, индексы |
| `swdb-sql` | разбор, оптимизация и выполнение SQL на Apache Calcite |
| `swdb-server` | сервер: протокол PostgreSQL на Netty, точка входа |
| `swdb-it` | интеграционные тесты через JDBC-драйвер PostgreSQL |
