// Scrubs personal and health data out of log events before any appender writes them.
plugins {
    id("solace.java-library")
}

dependencies {
    api("ch.qos.logback:logback-classic")
}
