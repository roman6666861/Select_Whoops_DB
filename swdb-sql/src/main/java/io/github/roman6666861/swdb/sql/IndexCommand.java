package io.github.roman6666861.swdb.sql;

import io.github.roman6666861.swdb.common.SqlStates;
import io.github.roman6666861.swdb.common.SwdbException;
import io.github.roman6666861.swdb.sql.schema.SwdbSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code CREATE INDEX} и {@code DROP INDEX} в синтаксисе PostgreSQL. В DDL-грамматике Calcite
 * их нет, поэтому, как и {@link TransactionCommand}, они распознаются до разбора Calcite.
 * Разбор идёт по словам (лексемам), а не регулярным выражением: так проще учесть имена
 * в кавычках и комментарии.
 */
sealed interface IndexCommand {

    /**
     * {@code CREATE [UNIQUE] INDEX [[IF NOT EXISTS] name] ON table [USING btree] (column [ASC], ...)}.
     *
     * @param name {@code null}, если имя не задано: его придумает {@link DdlExecutor}
     */
    record Create(String name, String table, List<String> columns, boolean unique, boolean ifNotExists)
            implements IndexCommand {
    }

    /** {@code DROP INDEX [IF EXISTS] name [RESTRICT]}. */
    record Drop(String name, boolean ifExists) implements IndexCommand {
    }

    /** Команда, если текст начинается с {@code CREATE [UNIQUE] INDEX} или {@code DROP INDEX}. */
    static Optional<IndexCommand> parse(String text) {
        Parser parser = new Parser(text);
        if (parser.accept("create")) {
            boolean unique = parser.accept("unique");
            return parser.accept("index") ? Optional.of(parser.create(unique)) : Optional.empty();
        }
        if (parser.accept("drop")) {
            return parser.accept("index") ? Optional.of(parser.drop()) : Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * Лексема: имя (без кавычек — в нижнем регистре, как в PostgreSQL) или знак {@code ( ) , .}.
     * Слово в кавычках никогда не считается ключевым: {@code "on"} — это имя.
     */
    record Token(String text, boolean quoted) {
        boolean is(String keyword) {
            return !quoted && text.equals(keyword);
        }
    }

    /** Разбор сверху вниз. Лексемы читаются по одной, по мере надобности. */
    final class Parser {

        private final String sql;
        private int position;
        private Token next;

        private Parser(String sql) {
            this.sql = sql;
        }

        private Create create(boolean unique) {
            unsupportedIf("concurrently", "CREATE INDEX CONCURRENTLY");
            String name = null;
            boolean ifNotExists = false;
            if (accept("if")) {
                expect("not");
                expect("exists");
                ifNotExists = true;
                name = identifier();
            } else if (!peekIs("on")) {
                name = identifier();
            }
            expect("on");
            String table = qualifiedName();
            if (accept("using")) {
                // Наш индекс — упорядоченный диапазон ключей RocksDB, по смыслу это B-дерево.
                String method = identifier();
                if (!method.equals("btree")) {
                    throw unsupported("index method \"" + method + "\"");
                }
            }
            expect("(");
            List<String> columns = new ArrayList<>();
            do {
                columns.add(identifier());
                accept("asc");
                unsupportedIf("desc", "DESC in indexes");
            } while (accept(","));
            expect(")");
            expectEnd();
            return new Create(name, table, columns, unique, ifNotExists);
        }

        private Drop drop() {
            unsupportedIf("concurrently", "DROP INDEX CONCURRENTLY");
            boolean ifExists = false;
            if (accept("if")) {
                expect("exists");
                ifExists = true;
            }
            String name = qualifiedName();
            unsupportedIf(",", "DROP INDEX with several indexes");
            accept("restrict");
            expectEnd();
            return new Drop(name, ifExists);
        }

        /** {@code name} или {@code public.name}; другие схемы пока не поддерживаются. */
        private String qualifiedName() {
            String name = identifier();
            if (accept(".")) {
                if (!name.equals(SwdbSchema.NAME)) {
                    throw unsupported("schemas other than \"" + SwdbSchema.NAME + "\"");
                }
                name = identifier();
            }
            return name;
        }

        private String identifier() {
            Token token = peek();
            if (token == null || !token.quoted() && !isIdentifierStart(token.text().charAt(0))) {
                throw syntaxError(token);
            }
            next = null;
            return token.text();
        }

        private boolean accept(String keyword) {
            if (peekIs(keyword)) {
                next = null;
                return true;
            }
            return false;
        }

        private void expect(String keyword) {
            if (!accept(keyword)) {
                throw syntaxError(peek());
            }
        }

        private void expectEnd() {
            if (peek() != null) {
                throw syntaxError(peek());
            }
        }

        private void unsupportedIf(String keyword, String feature) {
            if (peekIs(keyword)) {
                throw unsupported(feature);
            }
        }

        private boolean peekIs(String keyword) {
            Token token = peek();
            return token != null && token.is(keyword);
        }

        /** Следующая лексема, не продвигаясь дальше; {@code null} в конце текста. */
        private Token peek() {
            if (next == null) {
                next = read();
            }
            return next;
        }

        private Token read() {
            skipSpaceAndComments();
            if (position >= sql.length()) {
                return null;
            }
            char c = sql.charAt(position);
            if (c == '"') {
                return quotedIdentifier();
            }
            if (isIdentifierStart(c)) {
                int start = position;
                while (position < sql.length() && isIdentifierPart(sql.charAt(position))) {
                    position++;
                }
                return new Token(sql.substring(start, position).toLowerCase(Locale.ROOT), false);
            }
            if ("(),.".indexOf(c) >= 0) {
                position++;
                return new Token(String.valueOf(c), false);
            }
            throw syntaxError(new Token(String.valueOf(c), false));
        }

        /** {@code "Имя"}: регистр сохраняется, удвоенная кавычка — сама кавычка. */
        private Token quotedIdentifier() {
            StringBuilder name = new StringBuilder();
            position++;
            while (position < sql.length()) {
                char c = sql.charAt(position++);
                if (c != '"') {
                    name.append(c);
                } else if (position < sql.length() && sql.charAt(position) == '"') {
                    name.append('"');
                    position++;
                } else {
                    return new Token(name.toString(), true);
                }
            }
            throw new SwdbException(SqlStates.SYNTAX_ERROR, "unterminated quoted identifier");
        }

        private void skipSpaceAndComments() {
            while (position < sql.length()) {
                if (Character.isWhitespace(sql.charAt(position))) {
                    position++;
                } else if (sql.startsWith("--", position)) {
                    int end = sql.indexOf('\n', position);
                    position = end < 0 ? sql.length() : end + 1;
                } else if (sql.startsWith("/*", position)) {
                    int end = sql.indexOf("*/", position + 2);
                    position = end < 0 ? sql.length() : end + 2;
                } else {
                    return;
                }
            }
        }

        private static boolean isIdentifierStart(char c) {
            return Character.isLetter(c) || c == '_';
        }

        private static boolean isIdentifierPart(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '$';
        }

        /** Сообщения как в PostgreSQL. */
        private static SwdbException syntaxError(Token token) {
            return new SwdbException(SqlStates.SYNTAX_ERROR, token == null ? "syntax error at end of input"
                    : "syntax error at or near \"" + token.text() + "\"");
        }

        private static SwdbException unsupported(String feature) {
            return new SwdbException(SqlStates.FEATURE_NOT_SUPPORTED, feature + " is not supported yet");
        }
    }
}
