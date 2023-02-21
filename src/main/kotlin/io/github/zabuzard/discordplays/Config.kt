package io.github.zabuzard.discordplays

import dev.kord.common.entity.Snowflake
import io.github.zabuzard.discordplays.discord.HostId
import kotlinx.serialization.Serializable
import me.jakejmattson.discordkt.dsl.Data

@Serializable
data class Config(
    var romPath: String = "Pokemon Red TPP.gb",
    var gameTitle: String = "Pokemon Red",
    var owners: Set<Snowflake> = setOf(Snowflake(157994153806921728u)),
    var bannedUsers: Set<Snowflake> = setOf(),
    var hosts: Set<HostId> = setOf()
) : Data()
