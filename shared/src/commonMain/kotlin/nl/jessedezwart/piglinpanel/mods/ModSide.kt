package nl.jessedezwart.piglinpanel.mods

import kotlinx.serialization.Serializable

/**
 * Which side of the game a mod runs on.
 *
 * [UNKNOWN] is kept apart from [BOTH] on purpose. Most jars declare nothing at all, and a mod that
 * has not said where it runs is not the same as one that has said "everywhere" — collapsing the two
 * would present a guess as a fact on nine rows out of ten.
 */
@Serializable
enum class ModSide {
    BOTH,
    CLIENT,
    SERVER,
    UNKNOWN,
    ;

    /** Whether this says something worth showing; [BOTH] is the unremarkable case. */
    fun isNoteworthy(): Boolean = this == CLIENT || this == SERVER

    companion object {
        /**
         * The side a repository claims for a project, from Modrinth's `client_side`/`server_side`
         * support levels — `required`, `optional`, `unsupported` or `unknown`.
         *
         * Only `unsupported` on one side is a claim about where the mod runs; everything else is
         * about how useful it is there, which is not the same question.
         */
        fun ofRepository(clientSide: String?, serverSide: String?): ModSide {
            val client = clientSide?.lowercase()
            val server = serverSide?.lowercase()
            val clientUnsupported = client == UNSUPPORTED
            val serverUnsupported = server == UNSUPPORTED
            return when {
                clientUnsupported && serverUnsupported -> UNKNOWN
                serverUnsupported -> CLIENT
                clientUnsupported -> SERVER
                client.isSupported() && server.isSupported() -> BOTH
                else -> UNKNOWN
            }
        }

        private const val UNSUPPORTED = "unsupported"

        private fun String?.isSupported(): Boolean = this == "required" || this == "optional"
    }
}
