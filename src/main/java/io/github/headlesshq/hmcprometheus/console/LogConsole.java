package io.github.headlesshq.hmcprometheus.console;

import io.github.headlesshq.headlessmc.console.Console;
import io.github.headlesshq.hmcprometheus.logs.LokiPusher;
import me.earth.headlessmc.mc.console.CapturingConsole;

/**
 * Captures the output of a command from the web console, writes it to the console of the game
 * and pushes every line to Loki as soon as it is written, so that it shows up in the log panel of Grafana,
 * also when it is written after the response has been sent, e.g. by the render command.
 */
final class LogConsole extends CapturingConsole {
    static final String PREFIX = "[Console] ";

    private final LokiPusher pusher;
    private final Console game;

    /**
     * @param game the console of the game.
     * @param pusher the pusher to send the lines to Loki with, or {@code null} if Loki is disabled.
     */
    LogConsole(Console game, LokiPusher pusher) {
        super(game);
        this.game = game;
        this.pusher = pusher;
    }

    /**
     * Logs the executed command, without capturing it, the web console already shows what has been sent.
     */
    void writeCommand(String line) {
        String message = PREFIX + "> " + line;
        game.write(message);
        push(message);
    }

    @Override
    public void write(String message) {
        super.write(message);
        push(PREFIX + message);
    }

    private void push(String message) {
        if (pusher != null) {
            pusher.add(System.currentTimeMillis(), "INFO", message);
        }
    }

}
