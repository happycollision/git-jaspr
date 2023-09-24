package sims.michael.gitkspr

import org.slf4j.LoggerFactory

class GitKspr(
    private val ghClient: GithubClient,
    private val gitClient: JGitClient,
    private val config: Config,
    private val newUuid: () -> String = { generateUuid() },
) {

    private val logger = LoggerFactory.getLogger(GitKspr::class.java)

    // git kspr push [[local-object:]target-ref]
    suspend fun push(refSpec: RefSpec = RefSpec(DEFAULT_LOCAL_OBJECT, DEFAULT_TARGET_REF)) {
        logger.trace("push {}", refSpec)

        check(gitClient.workingDirectoryIsClean()) {
            "Your working directory has local changes. Please commit or stash them and re-run the command."
        }

        val remoteName = config.remoteName
        gitClient.fetch(remoteName)

        val targetRef = refSpec.remoteRef
        fun getLocalCommitStack() = gitClient.getLocalCommitStack(remoteName, refSpec.localRef, targetRef)
        val stack = addCommitIdsToLocalStack(getLocalCommitStack()) ?: getLocalCommitStack()
        gitClient.push(stack.map(Commit::getRefSpec))

        val headToPush = stack.firstOrNull()
        if (headToPush != null) {
            val headRefName = stack.first().getRefSpec().remoteRef
            ghClient.createPullRequest(targetRef, headRefName, title = "some title")
        }
    }

    private fun addCommitIdsToLocalStack(commits: List<Commit>): List<Commit>? {
        logger.trace("addCommitIdsToLocalStack {}", commits)
        val indexOfFirstCommitMissingId = commits.indexOfFirst { it.id == null }
        if (indexOfFirstCommitMissingId == -1) {
            logger.trace("No commits are missing IDs")
            return commits
        } else {
            val missing = commits.slice(indexOfFirstCommitMissingId until commits.size)
            val refName = "${missing.first().hash}^"
            gitClient.checkout(refName)
            for (commit in missing) {
                gitClient.cherryPick(commit)
                if (commit.id == null) {
                    val commitId = newUuid()
                    gitClient.setCommitId(commitId)
                }
            }
            return null
        }
    }
}

private const val REMOTE_BRANCH_PREFIX = "kspr/"
private fun Commit.getRefSpec(): RefSpec = RefSpec(hash, "${REMOTE_BRANCH_PREFIX}$id")
