package sims.michael.gitkspr

import ch.qos.logback.classic.Level
import kotlinx.serialization.Serializable
import sims.michael.gitkspr.serde.FileSerializer
import sims.michael.gitkspr.serde.LevelSerializer
import java.io.File

@Serializable
data class Config(
    @Serializable(with = FileSerializer::class)
    val workingDirectory: File,
    val remoteName: String,
    val gitHubInfo: GitHubInfo,
    @Serializable(with = LevelSerializer::class)
    val logLevel: Level = Level.INFO,
    @Serializable(with = FileSerializer::class)
    val logsDirectory: File? = null,
)

@Serializable
data class GitHubInfo(val host: String, val owner: String, val name: String)

data class Commit(val hash: String, val shortMessage: String, val fullMessage: String, val id: String?) {
    val remoteRefName: String get() = "$REMOTE_BRANCH_PREFIX$id"
    override fun toString() = "Commit(id=$id, h=$hash, msg=$shortMessage)"
}

data class RefSpec(val localRef: String, val remoteRef: String) {
    override fun toString() = "$localRef:$remoteRef"
}

data class PullRequest(
    val id: String?,
    val commitId: String?,
    val number: Int?,
    val headRefName: String,
    val baseRefName: String,
    val title: String,
    val body: String,
    // TODO add state?
    // TODO add draft?
) {
    override fun toString(): String {
        val numberString = number?.let { "#$it" }.orEmpty()
        return "PR$numberString(${headRefName.dropPrefix()} -> ${baseRefName.dropPrefix()}, title=$title, id=$id)"
    }

    private fun String.dropPrefix() = removePrefix(REMOTE_BRANCH_PREFIX)
}

class GitKsprException(override val message: String) : RuntimeException(message)
