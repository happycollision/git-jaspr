package sims.michael.gitkspr

import org.slf4j.LoggerFactory

class GitKspr(
    private val ghClient: GitHubClient,
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

        // TODO for each one you're pushing, see if there's an older one. if so, push it to kspr/commit-id/N where
        //   N is N + highest seen or 1

        val pullRequests = ghClient.getPullRequests().associateBy(PullRequest::commitId)

        val prsToMutate = stack
            .windowedPairs()
            .map { (prevCommit, currentCommit) ->
                PullRequest(
                    id = pullRequests[currentCommit.id]?.id,
                    commitId = currentCommit.id,
                    headRefName = currentCommit.remoteRefName,
                    // The base ref for the first commit in the stack (prevCommit == null) is the target branch
                    // (the branch the commit will ultimately merge into). The base ref for each subsequent
                    // commit is the remote ref name (i.e. kspr/<commit-id>) of the previous commit in the stack
                    baseRefName = prevCommit?.remoteRefName ?: refSpec.remoteRef,
                    title = currentCommit.shortMessage,
                    body = currentCommit.fullMessage,
                )
            }
            .filter { pr -> pullRequests[pr.commitId] != pr }

        for (pr in prsToMutate) {
            if (pr.id == null) {
                // create pull request
                ghClient.createPullRequest(pr)
            } else {
                // update pull request
                ghClient.updatePullRequest(pr)
            }
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
            gitClient.reset(refName) // TODO need a test that we're resetting and not doing this in detached HEAD
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

const val REMOTE_BRANCH_PREFIX = "kspr/"
fun Commit.getRefSpec(): RefSpec = RefSpec(hash, remoteRefName)

/** Much like [Iterable.windowed] with `size` == `2` but includes a leading pair of `null to firstElement` */
private fun <T : Any> Iterable<T>.windowedPairs(): List<Pair<T?, T>> {
    val iter = this
    return buildList {
        addAll(iter.take(1).map<T, Pair<T?, T>> { current -> null to current })
        addAll(iter.windowed(2).map { (prev, current) -> prev to current })
    }
}
