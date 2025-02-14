package io.github.zabuzard.discordplays.discord.commands

import dev.kord.common.entity.Permission.Administrator
import dev.kord.common.entity.Permissions
import dev.kord.core.behavior.interaction.response.respond
import io.github.zabuzard.discordplays.Config
import io.github.zabuzard.discordplays.discord.DiscordBot
import io.github.zabuzard.discordplays.discord.commands.CommandExtensions.mentionCommandOrNull
import io.github.zabuzard.discordplays.discord.commands.CommandExtensions.requireOwnerPermission
import io.github.zabuzard.discordplays.emulation.Emulator
import io.github.zabuzard.discordplays.local.FrameRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import me.jakejmattson.discordkt.arguments.AnyArg
import me.jakejmattson.discordkt.arguments.BooleanArg
import me.jakejmattson.discordkt.arguments.ChoiceArg
import me.jakejmattson.discordkt.arguments.UserArg
import me.jakejmattson.discordkt.commands.subcommand
import me.jakejmattson.discordkt.dsl.edit
import me.jakejmattson.discordkt.extensions.fullName
import mu.KotlinLogging
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.config.Configurator
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.notExists

fun ownerCommands(
    config: Config,
    bot: DiscordBot,
    emulator: Emulator,
    autoSaver: AutoSaver
) = subcommand(OWNER_COMMAND_NAME, Permissions(Administrator)) {
    sub("start", "Starts the game emulation") {
        execute {
            if (requireOwnerPermission(config)) return@execute

            val hook = interaction!!.deferEphemeralResponse()

            bot.startGame(discord)
            discord.kord.editPresence {
                playing(config.gameTitle)
                since = Clock.System.now()
            }

            val streamCommand = guild.mentionCommandOrNull(
                HOST_COMMAND_NAME,
                "$HOST_COMMAND_NAME $STREAM_SUBCOMMAND_NAME"
            )!!
            hook.respond {
                content = "Game emulation started. Stream is ready, use $streamCommand to host it."
            }
        }
    }

    sub("stop", "Stops the game emulation") {
        execute {
            if (requireOwnerPermission(config)) return@execute

            bot.stopGame()
            discord.kord.editPresence {}

            respond("Game emulation stopped.")
        }
    }

    sub("lock-input", "Only allows user input from owners, blocks any other input") {
        execute(BooleanArg("lock", description = "true to lock, false to unlock")) {
            if (requireOwnerPermission(config)) return@execute

            val lock = args.first
            bot.userInputLockedToOwners = lock

            val actionVerb = if (lock) "Locked" else "Unlocked"
            respond("$actionVerb user input.")
        }
    }

    sub("local-display", "Activates a local display on the bots machine for manual control.") {
        execute(
            BooleanArg("activate", description = "true to activate, false to deactivate"),
            BooleanArg(
                "sound",
                description = "true to activate sound, false to deactivate"
            ).optional(false)
        ) {
            if (requireOwnerPermission(config)) return@execute

            val (activate, sound) = args
            with(bot) {
                if (activate) activateLocalDisplay(sound) else deactivateLocalDisplay()
            }

            val activateVerb = if (activate) "Activated" else "Deactivated"
            val soundVerb = if (sound) "with" else "without"
            respond("$activateVerb local display $soundVerb sound.")
        }
    }

    sub("global-message", "Attaches a global message to the stream") {
        execute(
            AnyArg(
                "message",
                "leave out to clear any existing message"
            ).optionalNullable(null)
        ) {
            if (requireOwnerPermission(config)) return@execute

            val message = args.first
            bot.setGlobalMessage(message)

            val actionVerb = if (message == null) "Cleared" else "Set"
            respond("$actionVerb the global message.")
        }
    }

    sub("chat-message", "Sends a message to the chats of all hosts") {
        execute(
            AnyArg("message")
        ) {
            if (requireOwnerPermission(config)) return@execute
            val hook = interaction!!.deferEphemeralResponse()

            val message = args.first
            bot.sendChatMessage(message)

            hook.respond { content = "Send the chat message." }
        }
    }

    sub("add-owner", "Give another user owner-permission") {
        execute(UserArg("user", "who to grant owner-permission")) {
            if (requireOwnerPermission(config)) return@execute

            val user = args.first
            config.edit { owners += user.id }

            with("Added ${user.fullName} to the owners.") {
                logger.info { this }
                respond(this)
            }
        }
    }

    sub("game-metadata", "Change the metadata of the game played") {
        execute(
            ChoiceArg(
                "entity",
                "what to modify",
                *GameMetadataEntity.values().map(GameMetadataEntity::name).toTypedArray()
            ),
            AnyArg("value", "the new value for the entity")
        ) {
            if (requireOwnerPermission(config)) return@execute

            val (entity, value) = args
            config.edit {
                when (GameMetadataEntity.valueOf(entity)) {
                    GameMetadataEntity.ROM_PATH -> romPath = value
                    GameMetadataEntity.TITLE -> gameTitle = value
                }
            }

            with("Changed metadata $entity to $value") {
                logger.info { this }
                respond(this)
            }
        }
    }

    sub("ban", "Bans an user from the event, their input will be blocked") {
        execute(UserArg("user", "who you want to ban")) {
            if (requireOwnerPermission(config)) return@execute

            val user = args.first
            val userId = user.id
            if (userId in config.owners) {
                respond("Cannot ban an owner of the event.")
            }

            config.edit { bannedUsers += userId }

            with("Banned ${user.fullName} from the event.") {
                logger.info { this }
                respond(this)
            }
        }
    }

    sub("save", "Starts the auto-save dialog out of its automatic schedule") {
        execute {
            if (requireOwnerPermission(config)) return@execute

            val dmChannel = author.getDmChannelOrNull()
            if (dmChannel == null) {
                respond("Please open your DMs first.")
                return@execute
            }

            logger.info { "Triggered auto-save manually" }
            respond("Triggered the auto-save routine. Check your DMs.")
            autoSaveConversation(bot, emulator, autoSaver, author).startPrivately(discord, author)
        }
    }

    sub("clear-stats", "Clears all statistics, use when starting a new run") {
        execute {
            if (requireOwnerPermission(config)) return@execute

            config.edit {
                playtimeMs = 0
                userToInputCount = emptyList()
            }

            with("Cleared all statistics.") {
                logger.info { this }
                respond(this)
            }
        }
    }

    sub("log-level", "Changes the log level") {
        val levels = Level.values().map { it.name()!! }.toTypedArray()
        execute(ChoiceArg("level", "the level to set", *levels)) {
            if (requireOwnerPermission(config)) return@execute

            val level = Level.getLevel(args.first)!!
            Configurator.setAllLevels(LogManager.getRootLogger().name, level)

            with("Set the log level to $level.") {
                logger.info { this }
                respond(this)
            }
        }
    }

    sub("create-video", "Creates a video out of the recorded frames") {
        execute(AnyArg("date", "to use frames of, e.g. 2023-02-23, also folder name")) {
            if (requireOwnerPermission(config)) return@execute

            val date = args.first

            val frameFolder = Path.of(config.recordingPath, date)
            if (frameFolder.notExists()) {
                respond("Could not find any recordings for $date.")
                return@execute
            }
            respond("Command invoked, video is being created.")

            withContext(Dispatchers.IO) {
                val process = ProcessBuilder(
                    "ffmpeg",
                    "-framerate",
                    "5",
                    "-r",
                    "5",
                    "-i",
                    "%d${FrameRecorder.FRAME_SUFFIX}",
                    "-pix_fmt",
                    "yuv420p",
                    "-profile:v",
                    "high",
                    "-level:v",
                    "4.1",
                    "-crf:v",
                    "20",
                    "-movflags",
                    "+faststart",
                    "$date.mp4"
                ).directory(frameFolder.toFile()).start()

                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroy()
                }
            }
        }
    }
}

private val logger = KotlinLogging.logger {}

const val OWNER_COMMAND_NAME = "owner"

private enum class GameMetadataEntity {
    ROM_PATH,
    TITLE
}
