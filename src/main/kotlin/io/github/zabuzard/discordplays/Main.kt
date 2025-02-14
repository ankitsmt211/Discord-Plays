package io.github.zabuzard.discordplays

import dev.kord.common.annotation.KordPreview
import me.jakejmattson.discordkt.dsl.bot
import mu.KotlinLogging

@OptIn(KordPreview::class)
fun main(args: Array<String>) {
    require(args.size == 1) {
        "Incorrect number of arguments. The first argument must be the bot token. " +
            "Supplied arguments: ${args.contentToString()}"
    }

    val token = args.first()

    bot(token) {
        data("config.json") { Config() }

        configure {
            mentionAsPrefix = false
            logStartup = false
            searchCommands = false
            dualRegistry = false
            commandReaction = null
        }

        onException {
            logger.error(exception) { "Unknown error" }
        }

        onStart {
            logger.info { "Bot started, ready" }
        }
    }
}

private val logger = KotlinLogging.logger {}
