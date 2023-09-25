package sims.michael.gitkspr

import com.expediagroup.graphql.client.GraphQLClient
import org.slf4j.LoggerFactory
import sims.michael.gitkspr.generated.CreatePullRequest
import sims.michael.gitkspr.generated.GetPullRequests
import sims.michael.gitkspr.generated.GetRepositoryId
import sims.michael.gitkspr.generated.inputs.CreatePullRequestInput
import java.util.concurrent.atomic.AtomicReference

// TODO look into batch requests
class GitHubClient(private val delegate: GraphQLClient<*>, private val gitHubInfo: GitHubInfo) {
    private val logger = LoggerFactory.getLogger(GitHubClient::class.java)

    suspend fun createPullRequests(target: String, stack: List<Commit>) {
        logger.trace("createPullRequests {}", stack)

        data class PullRequestInfo(val commit: Commit, val baseRefName: String)

        val prsToOpen: List<PullRequestInfo> = stack.fold(listOf()) { acc, commit ->
            acc + PullRequestInfo(commit, acc.lastOrNull()?.commit?.remoteRefName ?: target)
        }

        val prsById = getPullRequestIdsByCommitId()
        for ((commit, baseRefName) in prsToOpen) {
            if (!prsById.containsKey(commit.id)) {
                createPullRequest(baseRefName, commit.remoteRefName, commit.shortMessage)
            }
        }
    }

    private suspend fun getPullRequestIdsByCommitId(): Map<String, String> {
        logger.trace("getPullRequestsByCommitId")
        val regex = "^${REMOTE_BRANCH_PREFIX}(.*?)$".toRegex()
        return delegate
            .execute(GetPullRequests(GetPullRequests.Variables(gitHubInfo.owner, gitHubInfo.name)))
            .data!!
            .repository!!
            .pullRequests
            .nodes!!
            .filterNotNull()
            .mapNotNull { pullRequest ->
                regex
                    .matchEntire(pullRequest.headRefName)
                    ?.let { result -> result.groupValues[1] to pullRequest.id }
            }
            .toMap()
            .also { prIdsByCommitId ->
                logger.trace("Existing PRs: {}", prIdsByCommitId)
            }
    }

    private suspend fun createPullRequest(baseRefName: String, headRefName: String, title: String) {
        logger.trace("createPullRequest {} {} {}", baseRefName, headRefName, title)
        delegate.execute(
            CreatePullRequest(
                CreatePullRequest.Variables(
                    CreatePullRequestInput(
                        baseRefName = baseRefName,
                        headRefName = headRefName,
                        repositoryId = repositoryId(),
                        title = title,
                    ),
                ),
            ),
        )
    }

    private suspend fun fetchRepositoryId(gitHubInfo: GitHubInfo): String {
        logger.trace("fetchRepositoryId {}", gitHubInfo)
        return delegate.execute(
            GetRepositoryId(
                GetRepositoryId.Variables(
                    gitHubInfo.owner,
                    gitHubInfo.name,
                ),
            ),
        ).data!!.repository!!.id
    }

    private val repositoryId = AtomicReference<String?>(null)
    private suspend fun repositoryId() = repositoryId.get() ?: fetchRepositoryId(gitHubInfo).also(repositoryId::set)
}
