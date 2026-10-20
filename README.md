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

## Что поддерживается

```sql
CREATE TABLE employees (
  id INT,
  name VARCHAR(50) NOT NULL,
  salary DECIMAL(10, 2),
  hired DATE,
  PRIMARY KEY (id)
);
INSERT INTO employees VALUES (1, 'Alice', 4800.50, DATE '2022-07-15');
SELECT name, salary FROM employees WHERE salary > 4000 ORDER BY salary DESC;
EXPLAIN SELECT name FROM employees WHERE id = 1;
UPDATE employees SET salary = salary * 1.1 WHERE id = 1;
DELETE FROM employees WHERE hired IS NULL;
DROP TABLE IF EXISTS employees;
```

- `CREATE TABLE [IF NOT EXISTS]`, `DROP TABLE [IF EXISTS]`, `INSERT` (в том числе `INSERT ... SELECT`), `UPDATE`, `DELETE`;
- `SELECT` с `WHERE`, `JOIN`, `GROUP BY`, агрегатами, `ORDER BY`, подзапросами, CTE, оконными функциями;
- `EXPLAIN <запрос>` (как в PostgreSQL) и `EXPLAIN PLAN FOR <запрос>` (как в Calcite) — план без выполнения;
- типы: `BOOLEAN`, `INT`, `BIGINT`, `DOUBLE`, `DECIMAL(p, s)`, `VARCHAR(n)`/`TEXT`, `DATE`, `TIMESTAMP`;
- `PRIMARY KEY` (обязателен, можно составной), `NOT NULL`; данные хранятся в RocksDB и переживают перезапуск.

Пока не поддерживаются: `MERGE`, `PRIMARY KEY` в объявлении колонки (`id INT PRIMARY KEY`),
`DEFAULT`, `UNIQUE`, вторичные индексы, транзакции, пользователи и пароли.

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
