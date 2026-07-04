package nl.jessedezwart.piglinpanel.desktop.logging

import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.UnsynchronizedAppenderBase
import nl.jessedezwart.piglinpanel.console.PanelConsole

/**
 * Feeds formatted log lines into [PanelConsole] so the in-app console screen can stream them.
 * Wired through logback.xml, which is why this cannot come from the DI graph.
 */
class PanelConsoleAppender : UnsynchronizedAppenderBase<ILoggingEvent>() {
    var pattern: String = "%d{HH:mm:ss.SSS} %-5level %logger{24} - %msg"

    private lateinit var layout: PatternLayout

    override fun start() {
        layout = PatternLayout().also {
            it.context = context
            it.pattern = pattern
            it.start()
        }
        super.start()
    }

    override fun append(event: ILoggingEvent) {
        PanelConsole.publish(layout.doLayout(event))
    }
}
