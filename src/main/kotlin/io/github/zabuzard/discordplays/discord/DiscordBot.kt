package io.github.zabuzard.discordplays.discord

import com.sksamuel.aedile.core.caffeineBuilder
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.edit
import dev.kord.core.entity.Guild
import dev.kord.rest.builder.message.modify.embed
import dev.kord.rest.request.KtorRequestException
import io.github.zabuzard.discordplays.Config
import io.github.zabuzard.discordplays.discord.commands.CommandExtensions.clearEmbeds
import io.github.zabuzard.discordplays.discord.stats.Statistics
import io.github.zabuzard.discordplays.discord.stats.StatisticsConsumer
import io.github.zabuzard.discordplays.emulation.Emulator
import io.github.zabuzard.discordplays.local.LocalDisplay
import io.github.zabuzard.discordplays.stream.OverlayRenderer
import io.github.zabuzard.discordplays.stream.StreamConsumer
import io.github.zabuzard.discordplays.stream.StreamRenderer
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import me.jakejmattson.discordkt.Discord
import me.jakejmattson.discordkt.annotations.Service
import me.jakejmattson.discordkt.dsl.edit
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.time.Duration.Companion.seconds

@Service
class DiscordBot(
    private val config: Config,
    private val emulator: Emulator,
    private val streamRenderer: StreamRenderer,
    private val overlayRenderer: OverlayRenderer,
    private val localDisplay: LocalDisplay,
    private val statistics: Statistics
) : StreamConsumer, StatisticsConsumer {
    private var guildToHost = emptyMap<Guild, Host>()

    private val userInputCache = caffeineBuilder<Snowflake, Instant> {
        maximumSize = 1_000
        expireAfterWrite = (10).seconds
    }.build()

    var userInputLockedToOwners = false
    var gameCurrentlyRunning = false

    init {
        streamRenderer.addStreamConsumer(this)
        statistics.addStatisticsConsumer(this)
    }

    suspend fun startGame(discord: Discord) {
        loadHosts(discord)

        emulator.start()
        streamRenderer.start()
        statistics.onGameStarted()
        gameCurrentlyRunning = true
    }

    fun stopGame() {
        emulator.stop()
        streamRenderer.stop()
        statistics.onGameStopped()
        gameCurrentlyRunning = false

        sendOfflineImage()
    }

    fun addHost(host: Host) {
        require(guildToHost[host.guild] == null) { "Only one host per guild allowed, first delete the existing host" }

        guildToHost += host.guild to host
        saveHosts()
    }

    private fun removeHost(host: Host) {
        guildToHost -= host.guild
        saveHosts()
    }

    enum class UserInputResult {
        ACCEPTED,
        RATE_LIMITED,
        BLOCKED_NON_OWNER,
        USER_BANNED
    }

    suspend fun onUserInput(input: UserInput): UserInputResult {
        val userId = input.user.id

        if (userInputLockedToOwners && userId !in config.owners) {
            return UserInputResult.BLOCKED_NON_OWNER
        }

        if (userId in config.bannedUsers) {
            return UserInputResult.USER_BANNED
        }

        val lastInput = userInputCache.getIfPresent(userId)

        val now = Clock.System.now()
        val timeSinceLastInput = if (lastInput == null) userInputRateLimit else now - lastInput

        return when {
            timeSinceLastInput >= userInputRateLimit -> {
                overlayRenderer.recordUserInput(input)
                emulator.clickButton(input.button)

                userInputCache.put(userId, now)
                statistics.onUserInput(input)
                UserInputResult.ACCEPTED
            }

            else -> UserInputResult.RATE_LIMITED
        }
    }

    fun activateLocalDisplay(sound: Boolean) {
        localDisplay.activate(sound)
        streamRenderer.addStreamConsumer(localDisplay)
    }

    fun deactivateLocalDisplay() {
        streamRenderer.removeStreamConsumer(localDisplay)
        localDisplay.deactivate()
        emulator.muteSound()
    }

    fun setGlobalMessage(message: String?) {
        if (message == null) {
            streamRenderer.globalMessage = null
        } else {
            require(message.isNotEmpty()) {
                "Cannot send an empty global message."
            }
            streamRenderer.globalMessage = message
        }
    }

    suspend fun setCommunityMessage(guild: Guild, message: String?) {
        if (message != null) {
            require(message.isNotEmpty()) {
                "Cannot send an empty community message."
            }
        }

        val host = guildToHost[guild]
        requireNotNull(host) { "Could not find any stream hosted in this server." }

        host.streamMessage.edit {
            clearEmbeds()

            if (message != null) {
                embed { description = message }
            }
        }
    }

    override fun acceptFrame(frame: BufferedImage) {
        // Only interested in gifs
    }

    override fun acceptGif(gif: ByteArray) =
        sendStreamFile("image.gif", gif.toInputStream())

    private fun sendOfflineImage() =
        sendStreamFile("stream.png", javaClass.getResourceAsStream(OFFLINE_COVER_RESOURCE)!!)

    private fun sendStreamFile(name: String, data: InputStream) {
        runBlocking {
            guildToHost.values.forEach {
                launch {
                    try {
                        it.streamMessage.edit {
                            files?.clear()
                            addFile(name, data)
                        }
                    } catch (e: KtorRequestException) {
                        if (e.error?.code?.name == MESSAGE_NOT_FOUND_ERROR) {
                            removeHost(it)
                        } else {
                            throw e
                        }
                    }
                }
            }
        }
    }

    override fun acceptStatistics(stats: String) {
        runBlocking {
            guildToHost.values.forEach {
                launch {
                    try {
                        it.chatDescriptionMessage.edit {
                            embeds?.clear()
                            embed {
                                title = "Stats"
                                description = stats
                            }
                        }
                    } catch (e: KtorRequestException) {
                        if (e.error?.code?.name == MESSAGE_NOT_FOUND_ERROR) {
                            removeHost(it)
                        } else {
                            throw e
                        }
                    }
                }
            }
        }
    }

    private fun saveHosts() {
        config.edit { hosts = guildToHost.values.map(Host::toHostId).toSet() }
    }

    private suspend fun loadHosts(discord: Discord) {
        guildToHost = config.hosts.mapNotNull { it.toHost(discord) }.associateBy { it.guild }
        saveHosts()
    }

    companion object {
        const val OFFLINE_COVER_RESOURCE = "/currently_offline.png"
        const val STARTING_SOON_COVER_RESOURCE = "/starting_soon.png"
    }
}

private val userInputRateLimit = (1.5).seconds
private const val MESSAGE_NOT_FOUND_ERROR = "UnknownMessage"

private fun ByteArray.toInputStream() =
    ByteArrayInputStream(this)
