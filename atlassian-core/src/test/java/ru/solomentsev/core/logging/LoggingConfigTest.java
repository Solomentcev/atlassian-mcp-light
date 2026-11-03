package ru.solomentsev.core.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Проверки конфигурации логирования.
 *
 * <p>Ключевое требование: все логи идут в {@code System.err}, потому что
 * {@code System.out} занят протоколом JSON-RPC. При нарушении этого условия
 * MCP-клиент не сможет разобрать ответы сервера.</p>
 */
public class LoggingConfigTest {

    /** Сообщение, которое используется для проверки маршрутизации вывода. */
    private static final String TEST_MESSAGE = "проверка вывода логов в stderr";

    /**
     * Конфигурация Logback должна присутствовать в classpath.
     */
    @Test
    public void logbackConfigurationIsOnClasspath() {
        InputStream configuration = getClass().getResourceAsStream("/logback.xml");
        assertNotNull("logback.xml должен находиться в classpath", configuration);
    }

    /**
     * У корневого логгера должен быть настроен вывод в stderr.
     */
    @Test
    public void rootLoggerHasStderrAppender() {
        Appender<ILoggingEvent> appender = rootAppender();
        assertNotNull("обработчик вывода STDERR должен быть настроен", appender);
        assertTrue("логи должны выводиться через ConsoleAppender",
                appender instanceof ConsoleAppender);
    }

    /**
     * Сообщение лога должно попасть в stderr и не должно попасть в stdout.
     */
    @Test
    public void logsGoToStderrAndDoNotBreakStdout() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LoggingConfigTest.class);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));

            logger.info(TEST_MESSAGE);

            System.out.flush();
            System.err.flush();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            logger.setLevel(previousLevel);
        }

        assertEquals("в stdout не должно быть ничего: stdout занят JSON-RPC",
                "", stdout.toString(StandardCharsets.UTF_8));
        assertTrue("в stderr должно быть сообщение лога",
                stderr.toString(StandardCharsets.UTF_8).contains(TEST_MESSAGE));
    }

    private Appender<ILoggingEvent> rootAppender() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        return root.getAppender("STDERR");
    }
}
