package io.github.zabuzard.discordplays.discord

import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Message
import dev.kord.core.entity.channel.MessageChannel
import kotlinx.serialization.Serializable
import me.jakejmattson.discordkt.Discord

@Serializable
data class MessageId(val channelId: Snowflake, val messageId: Snowflake) {
    suspend fun toMessage(discord: Discord) =
        discord.kord.getChannelOf<MessageChannel>(channelId)?.getMessageOrNull(messageId)
}

fun Message.toId() = MessageId(channelId, id)
