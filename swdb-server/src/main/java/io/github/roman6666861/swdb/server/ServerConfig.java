package io.github.roman6666861.swdb.server;

import java.nio.file.Path;

/**
 * Настройки сервера.
 *
 * @param host    адрес, на котором слушает сервер
 * @param port    TCP-порт; 0 — выбрать свободный
 * @param dataDir каталог с файлами базы
 */
public record ServerConfig(String host, int port, Path dataDir) {
}
