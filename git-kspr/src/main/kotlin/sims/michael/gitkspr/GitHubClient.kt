package sims.michael.gitkspr

import com.expediagroup.graphql.client.GraphQLClient
import com.expediagroup.graphql.client.types.GraphQLClientResponse
import org.slf4j.LoggerFactory
import sims.michael.gitkspr.generated.CreatePullRequest
import sims.michael.gitkspr.generated.GetPullRequests
import sims.michael.gitkspr.generated.GetRepositoryId
import sims.michael.gitkspr.generated.UpdatePullRequest
import sims.michael.gitkspr.generated.inputs.CreatePullRequestInput
import sims.michael.gitkspr.generated.inputs.UpdatePullRequestInput
import java.util.concurrent.atomic.AtomicReference

class GitHubClient(private val delegate: GraphQLClient<*>, private val gitHubInfo: GitHubInfo) {
    private val logger = LoggerFactory.getLogger(GitHubClient::class.java)

    suspend fun getPullRequests(): List<PullRequest> {
        logger.trace("getPullRequests")
        val regex = "^${REMOTE_BRANCH_PREFIX}(.*?)$".toRegex()
        return delegate
            .execute(GetPullRequests(GetPullRequests.Variables(gitHubInfo.owner, gitHubInfo.name)))
            .data
            ?.repository
            ?.pullRequests
            ?.nodes
            .orEmpty()
            .filterNotNull()
            .map { pr ->
                val commitId = regex
                    .matchEntire(pr.headRefName)
                    ?.let { result -> result.groupValues[1] }
                PullRequest(pr.id, commitId, pr.number, pr.headRefName, pr.baseRefName, pr.title, pr.body)
            }
            .also { pullRequests -> logger.trace("getPullRequests {}: {}", pullRequests.size, pullRequests) }
    }

    suspend fun createPullRequest(pullRequest: PullRequest) {
        logger.trace("createPullRequest {}", pullRequest)
        check(pullRequest.id == null) { "Cannot create $pullRequest which already exists" }
        delegate
            .execute(
                CreatePullRequest(
                    CreatePullRequest.Variables(
                        CreatePullRequestInput(
                            baseRefName = pullRequest.baseRefName,
                            headRefName = pullRequest.headRefName,
                            repositoryId = repositoryId(),
                            title = pullRequest.title,
                            body = pullRequest.body,
                        ),
                    ),
                ),
            )
            .also { response ->
                response.checkNoErrors { logger.error("Error creating PR {}", pullRequest) }
            }
    }

    suspend fun updatePullRequest(pullRequest: PullRequest) {
        logger.trace("updatePullRequest {}", pullRequest)
        checkNotNull(pullRequest.id) { "Cannot update $pullRequest without an ID" }
        delegate
            .execute(
                UpdatePullRequest(
                    UpdatePullRequest.Variables(
                        UpdatePullRequestInput(
                            pullRequestId = pullRequest.id,
                            baseRefName = pullRequest.baseRefName,
                            title = pullRequest.title,
                            body = pullRequest.body,
                        ),
                    ),
                ),
            )
            .also { response ->
                response.checkNoErrors { logger.error("Error updating PR #{}", pullRequest.number) }
            }
    }

    private suspend fun fetchRepositoryId(gitHubInfo: GitHubInfo): String {
        logger.trace("fetchRepositoryId {}", gitHubInfo)
        val response = delegate.execute(
            GetRepositoryId(
                GetRepositoryId.Variables(
                    gitHubInfo.owner,
                    gitHubInfo.name,
                ),
            ),
        )
        return checkNotNull(response.data?.repository?.id) { "Failed to fetch repository ID, response is null" }
    }

    private val repositoryId = AtomicReference<String?>(null)
    private suspend fun repositoryId() = repositoryId.get() ?: fetchRepositoryId(gitHubInfo).also(repositoryId::set)

    private fun GraphQLClientResponse<*>.checkNoErrors(onError: () -> Unit = {}) {
        val list = errors?.takeUnless { list -> list.isEmpty() } ?: return

        onError()
        for (graphQLClientError in list) {
            logger.error(graphQLClientError.toString())
        }

        throw GitKsprException(list.first().message)
    }
}
