package io.github.zabuzard.discordplays.discord.commands

import dev.kord.core.entity.Guild
import dev.kord.rest.builder.message.modify.UserMessageModifyBuilder
import dev.kord.rest.builder.message.modify.embed
import io.github.zabuzard.discordplays.Config
import kotlinx.coroutines.flow.toList
import me.jakejmattson.discordkt.commands.GuildSlashCommandEvent

object CommandExtensions {
    suspend fun Guild.mentionCommandOrNull(commandName: String, fullCommandQuery: String) =
        kord.getGuildApplicationCommands(id).toList().find { it.name == commandName }
            ?.id
            ?.let { "</$fullCommandQuery:$it>" }

    suspend fun GuildSlashCommandEvent<*>.requireOwnerPermission(config: Config): Boolean {
        if (author.id in config.owners) return false

        respond("Sorry, only owners can use this command.")
        return true
    }

    suspend fun UserMessageModifyBuilder.clearEmbeds() {
        embed { description = "" }
        embeds?.clear()
    }
}
