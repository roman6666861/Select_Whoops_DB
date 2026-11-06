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

JDBC: `jdbc:postgresql://127.0.0.1:5432/swdb` — с настройками драйвера по умолчанию
(extended query protocol: подготовленные операторы с параметрами, текстовый и бинарный формат значений).

## Что поддерживается

```sql
CREATE TABLE employees (
  id INT,
  name VARCHAR(50) NOT NULL,
  salary DECIMAL(10, 2),
  hired DATE,
  PRIMARY KEY (id)
);
CREATE INDEX employees_hired ON employees (hired);
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
- `PRIMARY KEY` (обязателен; составной — `PRIMARY KEY (a, b)`, из одной колонки можно и `id INT PRIMARY KEY`), `NOT NULL`, `UNIQUE` (в колонке, `UNIQUE (a, b)` и `CONSTRAINT имя UNIQUE (...)` — это уникальный индекс `таблица_колонки_key`); данные хранятся в RocksDB и переживают перезапуск.
- вторичные индексы: `CREATE [UNIQUE] INDEX [[IF NOT EXISTS] имя] ON таблица (колонки)`, `DROP INDEX [IF EXISTS] имя`; индекс обновляется в той же транзакции, что и строки; `WHERE` с `=`, `IS NULL` и диапазонами (`<`, `>`, `BETWEEN`) по первым колонкам индекса читает только нужные записи;
- транзакции: `BEGIN` / `START TRANSACTION [ISOLATION LEVEL ...]`, `COMMIT`, `ROLLBACK`; без `BEGIN` каждый оператор выполняется в своей транзакции;
- уровни изоляции, как в PostgreSQL: READ COMMITTED (по умолчанию; `READ UNCOMMITTED` — то же самое) — у каждого оператора свой снимок, а оператор, упёршийся в чужую запись той же строки, откатывается к точке сохранения и выполняется заново поверх неё; REPEATABLE READ — один снимок на всю транзакцию, конфликт одновременной записи строки — ошибка `40001`; SERIALIZABLE — как REPEATABLE READ плюс двухфазные блокировки таблиц между SERIALIZABLE-транзакциями (чтение — разделяемая, запись — исключительная): результат как при поочерёдном выполнении, а при конфликте одна из транзакций получает `40001`. В отличие от SSI в PostgreSQL блокировки пессимистичные и на уровне таблиц: пишущие SERIALIZABLE-транзакции одной таблицы ждут друг друга и её читателей;
- DDL транзакционный, как в PostgreSQL: `CREATE`/`DROP TABLE` и `CREATE`/`DROP INDEX` можно выполнять внутри `BEGIN`, `ROLLBACK` их отменяет, а другие сессии видят изменения только после `COMMIT`. Операторы блокируют таблицу в разделяемом режиме, DDL — в исключительном: DDL ждёт открытые транзакции, работающие с таблицей, а они — его. Оператор вне `BEGIN` или в READ COMMITTED, получивший `40001` (ждал DDL или чужую запись той же строки), сам выполняется заново со свежим снимком; в REPEATABLE READ `40001` получает клиент — повторить нужно всю транзакцию.

Пока не поддерживаются: `MERGE`,
`DEFAULT`, `SAVEPOINT`, пользователи и пароли.

## Модули

| Модуль | Назначение |
|---|---|
| `swdb-common` | общие типы и исключения |
| `swdb-storage` | key-value хранилище на RocksDB, транзакции и блокировки |
| `swdb-catalog` | каталог: схемы, таблицы, индексы |
| `swdb-sql` | разбор, оптимизация и выполнение SQL на Apache Calcite |
| `swdb-server` | сервер: протокол PostgreSQL на Netty, точка входа |
| `swdb-it` | интеграционные тесты через JDBC-драйвер PostgreSQL |
