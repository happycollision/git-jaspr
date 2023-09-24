package sims.michael.gitkspr

import com.expediagroup.graphql.client.GraphQLClient
import org.slf4j.LoggerFactory
import sims.michael.gitkspr.generated.CreatePullRequest
import sims.michael.gitkspr.generated.GetRepositoryId
import sims.michael.gitkspr.generated.inputs.CreatePullRequestInput

class GithubClient(private val delegate: GraphQLClient<*>, private val gitHubInfo: GitHubInfo) {
    private val logger = LoggerFactory.getLogger(GithubClient::class.java)
    suspend fun createPullRequest(baseRefName: String, headRefName: String, title: String) {
        logger.trace("createPullRequest {} {} {}", baseRefName, headRefName, title)
        val repositoryId = fetchRepositoryId(gitHubInfo)
        delegate.execute(
            CreatePullRequest(
                CreatePullRequest.Variables(
                    CreatePullRequestInput(
                        baseRefName = baseRefName,
                        headRefName = headRefName,
                        repositoryId = repositoryId,
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
}
