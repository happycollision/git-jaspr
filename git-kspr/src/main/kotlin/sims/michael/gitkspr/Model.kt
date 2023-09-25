package sims.michael.gitkspr

import kotlinx.serialization.Serializable
import sims.michael.gitkspr.serde.FileSerializer
import java.io.File

@Serializable
data class Config(
    @Serializable(with = FileSerializer::class)
    val workingDirectory: File,
    val remoteName: String,
    val gitHubInfo: GitHubInfo,
)

@Serializable
data class GitHubInfo(val host: String, val owner: String, val name: String)

data class Commit(val hash: String, val shortMessage: String, val fullMessage: String, val id: String?) {
    val remoteRefName: String get() = "$REMOTE_BRANCH_PREFIX$id"
}

data class RefSpec(val localRef: String, val remoteRef: String)

class GitKsprException(override val message: String) : RuntimeException(message)
