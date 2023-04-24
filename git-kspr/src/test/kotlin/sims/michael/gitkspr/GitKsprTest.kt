package sims.michael.gitkspr

import com.expediagroup.graphql.client.GraphQLClient
import com.expediagroup.graphql.client.serialization.types.KotlinxGraphQLResponse
import com.expediagroup.graphql.client.types.GraphQLClientRequest
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.junit.MockSystemReader
import org.eclipse.jgit.lib.Constants.GIT_COMMITTER_EMAIL_KEY
import org.eclipse.jgit.lib.Constants.GIT_COMMITTER_NAME_KEY
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.zeroturnaround.exec.ProcessExecutor
import sims.michael.gitkspr.generated.GetPullRequests
import sims.michael.gitkspr.generated.ID
import sims.michael.gitkspr.generated.getpullrequests.PullRequestConnection
import sims.michael.gitkspr.generated.getpullrequests.PullRequestConnection2
import sims.michael.gitkspr.generated.getpullrequests.Repository2
import sims.michael.gitkspr.generated.getpullrequests.User
import sims.michael.gitkspr.testing.toStringWithClickableURI
import java.io.File
import java.nio.file.Files

class GitKsprTest {

    private val logger = LoggerFactory.getLogger(GitKsprTest::class.java)

    @BeforeEach
    fun setUp() = setGitCommitterInfo("Frank Grimes", "grimey@example.com")

    @Test
    fun push() {
        val tempDir = createTempDir()
        logger.info("Temp dir created in {}", tempDir.toStringWithClickableURI())

        val remoteRepoDir = tempDir.resolve("test-remote").apply(File::initRepoWithInitialCommit)

        val localRepoDir = tempDir.resolve("test-local")
        val local = JGitClient(localRepoDir).clone(remoteRepoDir.toURI().toString()).checkout("development", true)
        for (num in (1..3)) {
            val filePattern = "$num.txt"
            localRepoDir.resolve(filePattern).writeText("This is file number $num.\n")
            local.add(filePattern).commit("This is file number $num")
        }

        localRepoDir.printGitCommand("git", "log", "--pretty=fuller")
        val fake = KotlinxGraphQLResponse(
            GetPullRequests.Result(
                User(
                    "blah",
                    PullRequestConnection(
                        emptyList(),
                    ),
                ),
                Repository2(ID(), PullRequestConnection2(emptyList())),
            ),
        )

        val graphQLClient = mock<GraphQLClient<*>> {
            onBlocking { execute(any<GraphQLClientRequest<*>>(), any()) } doReturn fake
        }

        val kspr = GitKspr(graphQLClient, local, config(localRepoDir))
        runBlocking {
            kspr.push(RefSpec("HEAD", "main"))
        }
        localRepoDir.printGitCommand("git", "log", "--pretty=fuller")
    }

    private fun config(localRepo: File) = Config(localRepo, "origin", GitHubInfo("host", "owner", "name"))

    @Suppress("SameParameterValue")
    private fun setGitCommitterInfo(name: String, email: String) {
        SystemReader
            .setInstance(
                MockSystemReader()
                    .apply {
                        setProperty(GIT_COMMITTER_NAME_KEY, name)
                        setProperty(GIT_COMMITTER_EMAIL_KEY, email)
                    },
            )
    }

    private fun createTempDir() = checkNotNull(Files.createTempDirectory(GitKsprTest::class.java.simpleName).toFile())
}

private fun File.initRepoWithInitialCommit() {
    val git = JGitClient(this).init()
    val readme = "README.txt"
    resolve(readme).writeText("This is a test repo.\n")
    git.add(readme).commit("Initial commit")
}

@Suppress("SameParameterValue")
private fun File.printGitCommand(vararg command: String) {
    val result = ProcessExecutor().command(*command).directory(this).readOutput(true).execute()
    println(result.outputString())
}
