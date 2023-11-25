package sims.michael.gitkspr

object RemoteRefEncoding {
    const val DEFAULT_REMOTE_BRANCH_PREFIX = "kspr"

    const val REV_NUM_DELIMITER = "_"

    fun buildRemoteRef(commitId: String, prefix: String = DEFAULT_REMOTE_BRANCH_PREFIX): String =
        listOf(prefix, commitId).joinToString("/")

    fun getRemoteRefParts(remoteRef: String, remoteBranchPrefix: String): RemoteRefParts? =
        "^$remoteBranchPrefix/(.*?)(?:$REV_NUM_DELIMITER(\\d+))?$"
            .toRegex()
            .matchEntire(remoteRef)
            ?.let { result -> RemoteRefParts(result.groupValues[1], result.groupValues.getOrNull(2)?.toIntOrNull()) }

    fun getCommitIdFromRemoteRef(remoteRef: String, remoteBranchPrefix: String): String? =
        getRemoteRefParts(remoteRef, remoteBranchPrefix)?.commitId

    data class RemoteRefParts(val commitId: String, val revisionNum: Int?)
}
