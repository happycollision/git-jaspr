package sims.michael.gitkspr

import com.expediagroup.graphql.client.GraphQLClient
import sims.michael.gitkspr.generated.CreatePullRequest
import sims.michael.gitkspr.generated.GetRepositoryId
import sims.michael.gitkspr.generated.inputs.CreatePullRequestInput

class GitKspr(
    private val githubClient: GraphQLClient<*>,
    private val gitClient: JGitClient,
    private val config: Config,
    private val newUuid: () -> String = { generateUuid() },
) {

    // git kspr push [[local-object:]target-ref]
    suspend fun push(refSpec: RefSpec) {
        // TODO check working directory is clean
        val remoteName = config.remoteName
        gitClient.fetch(remoteName)

        fun getLocalCommitStack() = gitClient.getLocalCommitStack(remoteName, refSpec.localRef, refSpec.remoteRef)
        addCommitIdsToLocalStack(getLocalCommitStack())
        gitClient.push(getLocalCommitStack().map(Commit::getRefSpec))

        val ghInfo = config.gitHubInfo
        val repoIdResponse = githubClient.execute(GetRepositoryId(GetRepositoryId.Variables(ghInfo.owner, ghInfo.name)))
        val repositoryId = repoIdResponse.data!!.repository!!.id

        val response = githubClient.execute(
            CreatePullRequest(
                CreatePullRequest.Variables(
                    CreatePullRequestInput(
                        baseRefName = refSpec.remoteRef,
                        headRefName = getLocalCommitStack().first().getRefSpec().remoteRef,
                        repositoryId = repositoryId,
                        title = "some title",
                    ),
                ),
            ),
        )
        println(response)
    }

    private fun addCommitIdsToLocalStack(commits: List<Commit>) {
        val indexOfFirstCommitMissingId = commits.indexOfFirst { it.id == null }
        if (indexOfFirstCommitMissingId != -1) {
            val missing = commits.slice(indexOfFirstCommitMissingId until commits.size)
            gitClient.checkout("${missing.first().hash}^")
            for (commit in missing) {
                gitClient.cherryPick(commit)
                if (commit.id == null) {
                    gitClient.setCommitId(newUuid())
                }
            }
        }
    }
}

private const val REMOTE_BRANCH_PREFIX = "kspr/"
private fun Commit.getRefSpec(): RefSpec = RefSpec(hash, "${REMOTE_BRANCH_PREFIX}$id")
