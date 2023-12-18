package sims.michael.gitjaspr

import ch.qos.logback.classic.Level
import kotlinx.serialization.Serializable
import sims.michael.gitjaspr.RemoteRefEncoding.DEFAULT_REMOTE_BRANCH_PREFIX
import sims.michael.gitjaspr.serde.FileSerializer
import sims.michael.gitjaspr.serde.LevelSerializer
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import sims.michael.gitjaspr.generated.getpullrequests.RateLimit as GetPullRequestsRateLimit
import sims.michael.gitjaspr.generated.getpullrequestsbyheadref.RateLimit as GetPullRequestsByHeadRefRateLimit
import sims.michael.gitjaspr.generated.getrepositoryid.RateLimit as GetRepositoryIdRateLimit

@Serializable
data class Config(
    @Serializable(with = FileSerializer::class)
    val workingDirectory: File,
    val remoteName: String,
    val gitHubInfo: GitHubInfo,
    val remoteBranchPrefix: String = DEFAULT_REMOTE_BRANCH_PREFIX,
    @Serializable(with = LevelSerializer::class)
    val logLevel: Level = Level.INFO,
    @Serializable(with = FileSerializer::class)
    val logsDirectory: File? = null,
)

@Serializable
data class GitHubInfo(val host: String, val owner: String, val name: String)

data class Commit(
    val hash: String,
    val shortMessage: String,
    val fullMessage: String,
    val id: String?,
    val committer: Ident,
    // Format with date.format(DateTimeFormatter.ofPattern("E MMM d, YYYY, h:mm:ss a z"))
    val commitDate: ZonedDateTime,
    val authorDate: ZonedDateTime,
)

data class Ident(val name: String, val email: String) {
    override fun toString() = "$name <$email>"
}

data class RefSpec(val localRef: String, val remoteRef: String) {
    override fun toString() = "$localRef:$remoteRef"
    fun forcePush() = if (!localRef.startsWith(FORCE_PUSH_PREFIX)) copy(localRef = "+$localRef") else this
}

data class RemoteBranch(val name: String, val commit: Commit) {
    fun toRefSpec(): RefSpec = RefSpec(commit.hash, name)
}

data class RemoteCommitStatus(
    val localCommit: Commit,
    val remoteCommit: Commit?,
    val pullRequest: PullRequest?,
    val checksPass: Boolean?,
    val approved: Boolean?,
)

data class PullRequest(
    val id: String?,
    val commitId: String?,
    val number: Int?,
    val headRefName: String,
    val baseRefName: String,
    val title: String,
    val body: String,
    val checksPass: Boolean? = null,
    val approved: Boolean? = null,
    val checkConclusionStates: List<String> = emptyList(),
    val permalink: String? = null,
    val isDraft: Boolean = false,
) {
    override fun toString(): String {
        val numberString = number?.let { "#$it" }.orEmpty()
        return "PR$numberString($headToBaseString, title=$title, id=$id)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PullRequest

        if (id != other.id) return false.also { println("FLUBBER id") }
        if (commitId != other.commitId) return false.also { println("FLUBBER commitId") }
        if (number != other.number) return false.also { println("FLUBBER number") }
        if (headRefName != other.headRefName) return false.also { println("FLUBBER headrefname") }
        if (baseRefName != other.baseRefName) return false.also { println("FLUBBER baserefname") }
        if (title != other.title) return false.also { println("FLUBBER title") }
        if (body != other.body) return false.also { println("FLUBBER body") }
        if (checksPass != other.checksPass) return false.also { println("FLUBBER checkspass") }
        if (approved != other.approved) return false.also { println("FLUBBER approved") }
        if (checkConclusionStates != other.checkConclusionStates) return false.also { println("FLUBBER checkConclusionStates") }
        if (permalink != other.permalink) return false.also { println("FLUBBER permalink") }
        if (isDraft != other.isDraft) return false.also { println("FLUBBER isdraft") }

        return true
    }

    override fun hashCode(): Int {
        var result = id?.hashCode() ?: 0
        result = 31 * result + (commitId?.hashCode() ?: 0)
        result = 31 * result + (number ?: 0)
        result = 31 * result + headRefName.hashCode()
        result = 31 * result + baseRefName.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + body.hashCode()
        result = 31 * result + (checksPass?.hashCode() ?: 0)
        result = 31 * result + (approved?.hashCode() ?: 0)
        result = 31 * result + checkConclusionStates.hashCode()
        result = 31 * result + (permalink?.hashCode() ?: 0)
        result = 31 * result + isDraft.hashCode()
        return result
    }

    val headToBaseString: String get() = "$headRefName -> $baseRefName"



}

data class GitHubRateLimitInfo(
    val cost: Int,
    val used: Int,
    val limit: Int,
    val remaining: Int,
    val nodeCount: Int,
    val resetAt: LocalDateTime,
)

fun GetPullRequestsRateLimit.toCanonicalRateLimitInfo(): GitHubRateLimitInfo =
    GitHubRateLimitInfo(cost, used, limit, remaining, nodeCount, resetAt.iso8601ToLocalDate())

fun GetPullRequestsByHeadRefRateLimit.toCanonicalRateLimitInfo(): GitHubRateLimitInfo =
    GitHubRateLimitInfo(cost, used, limit, remaining, nodeCount, resetAt.iso8601ToLocalDate())

fun GetRepositoryIdRateLimit.toCanonicalRateLimitInfo(): GitHubRateLimitInfo =
    GitHubRateLimitInfo(cost, used, limit, remaining, nodeCount, resetAt.iso8601ToLocalDate())

/** Convert an ISO-8601 encoded UTC date string to a [LocalDateTime] */
private fun String.iso8601ToLocalDate(): LocalDateTime =
    Instant.parse(this).atZone(ZoneId.systemDefault()).toLocalDateTime()

class GitJasprException(override val message: String) : RuntimeException(message) {
    constructor(message: String, cause: Throwable) : this(message) {
        initCause(cause)
    }
}
