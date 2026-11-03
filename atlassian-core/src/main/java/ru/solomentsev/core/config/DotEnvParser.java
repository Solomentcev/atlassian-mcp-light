package ru.solomentsev.core.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Парсер файлов в формате dotenv (`.env`).
 *
 * <p>Поддерживаемый синтаксис:
 * <ul>
 *   <li>пустые строки и комментарии, начинающиеся с {@code #};</li>
 *   <li>необязательный префикс {@code export} перед именем переменной;</li>
 *   <li>разделение имени и значения по первому символу {@code =};</li>
 *   <li>значение в парных кавычках {@code "} или {@code '} сохраняется как есть;</li>
 *   <li>в значении без кавычек комментарий начинается с {@code #} после пробела.</li>
 * </ul>
 *
 * <p>Значения переменных не логируются, чтобы не раскрывать секреты.</p>
 */
public final class DotEnvParser {

    /** Префикс объявления переменной в shell-совместимом формате. */
    private static final String EXPORT_PREFIX = "export";

    private DotEnvParser() {
        // utility class
    }

    /**
     * Разбирает содержимое dotenv-файла.
     *
     * @param content содержимое файла (может быть null)
     * @return карта «имя переменной → значение»; порядок сохраняется как в файле
     */
    public static Map<String, String> parse(String content) {
        Map<String, String> values = new LinkedHashMap<>();
        if (content == null) {
            return values;
        }
        for (String line : content.split("\r\n|\r|\n")) {
            parseLine(line, values);
        }
        return values;
    }

    /**
     * Читает и разбирает dotenv-файл.
     *
     * @param file путь к файлу
     * @return карта «имя переменной → значение»
     * @throws IOException если файл не удалось прочитать
     */
    public static Map<String, String> parse(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    private static void parseLine(String rawLine, Map<String, String> values) {
        String line = rawLine.trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }

        line = stripExportPrefix(line);

        int separator = line.indexOf('=');
        if (separator <= 0) {
            return;
        }

        String key = line.substring(0, separator).trim();
        if (key.isEmpty()) {
            return;
        }
        values.put(key, parseValue(line.substring(separator + 1).trim()));
    }

    private static String stripExportPrefix(String line) {
        if (!line.startsWith(EXPORT_PREFIX)) {
            return line;
        }
        int prefixEnd = EXPORT_PREFIX.length();
        if (line.length() == prefixEnd || Character.isWhitespace(line.charAt(prefixEnd))) {
            return line.substring(prefixEnd).trim();
        }
        return line;
    }

    private static String parseValue(String rawValue) {
        if (rawValue.length() >= 2) {
            char first = rawValue.charAt(0);
            char last = rawValue.charAt(rawValue.length() - 1);
            if ((first == '"' || first == '\'') && first == last) {
                return rawValue.substring(1, rawValue.length() - 1);
            }
        }
        return stripInlineComment(rawValue).trim();
    }

    private static String stripInlineComment(String value) {
        int hash = value.indexOf('#');
        if (hash < 0) {
            return value;
        }
        if (hash == 0 || Character.isWhitespace(value.charAt(hash - 1))) {
            return value.substring(0, hash);
        }
        return value;
    }
}
