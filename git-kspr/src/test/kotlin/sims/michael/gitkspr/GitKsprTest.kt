package sims.michael.gitkspr

import org.eclipse.jgit.junit.MockSystemReader
import org.eclipse.jgit.lib.Constants.GIT_COMMITTER_EMAIL_KEY
import org.eclipse.jgit.lib.Constants.GIT_COMMITTER_NAME_KEY
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.*
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.slf4j.LoggerFactory
import sims.michael.gitkspr.JGitClient.Companion.HEAD
import sims.michael.gitkspr.JGitClient.Companion.R_HEADS
import sims.michael.gitkspr.RemoteRefEncoding.DEFAULT_REMOTE_BRANCH_PREFIX
import sims.michael.gitkspr.RemoteRefEncoding.buildRemoteRef
import sims.michael.gitkspr.githubtests.GitHubTestHarness.Companion.withTestSetup
import sims.michael.gitkspr.githubtests.TestCaseData
import sims.michael.gitkspr.githubtests.generatedtestdsl.testCase
import kotlin.test.assertEquals

class GitKsprTest {

    private val logger = LoggerFactory.getLogger(GitKsprTest::class.java)

    @BeforeEach
    fun setUp() = setGitCommitterInfo("Frank Grimes", "grimey@example.com")

    @Test
    fun `windowedPairs produces expected result`() {
        val input = listOf("one", "two", "three")
        val expected = listOf(null to "one", "one" to "two", "two" to "three")
        val actual = input.windowedPairs()
        assertEquals(expected, actual)
    }

    @Test
    fun `push fails unless workdir is clean`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "some_commit"
                            localRefs += "development"
                        }
                    }
                    localWillBeDirty = true
                },
            )
            val exception = assertThrows<IllegalStateException> {
                gitKspr.push()
            }
            logger.info("Exception message is {}", exception.message)
        }
    }

    @Test
    fun `push fetches from remote`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit {
                            title = "two"
                            localRefs += "main"
                        }
                        commit {
                            title = "three"
                            remoteRefs += "main"
                        }
                    }
                },
            )

            gitKspr.push()

            assertEquals(
                listOf("three", "two", "one"),
                localGit.log("origin/main", maxCount = 3).map(Commit::shortMessage),
            )
        }
    }

    @TestFactory
    fun `push adds commit IDs`(): List<DynamicTest> {
        data class Test(val name: String, val expected: List<String>, val testCaseData: TestCaseData)
        return listOf(
            Test(
                "all commits missing IDs",
                listOf("0", "1", "2"),
                testCase {
                    repository {
                        commit { title = "0" }
                        commit { title = "1" }
                        commit {
                            title = "2"
                            localRefs += "main"
                        }
                    }
                },
            ),
            Test(
                "only recent commits missing IDs",
                listOf("A", "B", "0", "1", "2"),
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit { title = "0" }
                        commit { title = "1" }
                        commit {
                            title = "2"
                            localRefs += "main"
                        }
                    }
                },
            ),
            Test(
                "only commits in the middle missing IDs",
                listOf("A", "B", "0", "1", "2", "C", "D"),
                testCase {
                    repository {
                        commit { title = "A" }
                        commit { title = "B" }
                        commit { title = "0" }
                        commit { title = "1" }
                        commit { title = "2" }
                        commit { title = "C" }
                        commit {
                            title = "D"
                            localRefs += "main"
                        }
                    }
                },
            ),
        ).map { (name, expected, collectCommits) ->
            dynamicTest(name) {
                withTestSetup {
                    createCommitsFrom(collectCommits)
                    gitKspr.push()
                    assertEquals(
                        expected,
                        localGit.logRange("$HEAD~${collectCommits.repository.commits.size}", HEAD).map(Commit::id),
                    )
                }
            }
        }
    }

    @Test
    fun `push pushes to expected remote branch names`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "1" }
                        commit { title = "2" }
                        commit {
                            title = "3"
                            localRefs += "main"
                        }
                    }
                },
            )
            gitKspr.push()

            assertEquals(
                (1..3).associate {
                    "$R_HEADS${buildRemoteRef(it.toString())}" to it.toString()
                },
                remoteGit.commitIdsByBranch(),
            )
        }
    }

    @Test
    fun `push pushes revision history branches on update`(testInfo: TestInfo) {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "a" }
                        commit { title = "b" }
                        commit {
                            title = "c"
                            localRefs += "main"
                        }
                    }
                },
            )
            gitKspr.push()
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "z" }
                        commit { title = "a" }
                        commit { title = "b" }
                        commit {
                            title = "c"
                            localRefs += "main"
                        }
                    }
                },
            )
            gitKspr.push()
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "y" }
                        commit { title = "a" }
                        commit { title = "b" }
                        commit {
                            title = "c"
                            localRefs += "main"
                        }
                    }
                },
            )
            gitKspr.push()
            gitLogLocalAndRemote()

            assertEquals(
                listOf("a", "a_01", "a_02", "b", "b_01", "b_02", "c", "c_01", "c_02", "y", "z")
                    .map { name -> buildRemoteRef(name) },
                localGit
                    .getRemoteBranches()
                    .map(RemoteBranch::name)
                    .filter { name -> name.startsWith(DEFAULT_REMOTE_BRANCH_PREFIX) }
                    .sorted(),
            )
        }
    }

    @Test
    fun `push updates base refs for any reordered PRs`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "1" }
                        commit { title = "2" }
                        commit { title = "4" }
                        commit {
                            title = "3"
                            localRefs += "development"
                            remoteRefs += "development"
                        }
                    }
                },
            )

            gitKspr.push()

            assertEquals(
                setOf(
                    "kspr/1 -> main",
                    "kspr/2 -> kspr/1",
                    "kspr/4 -> kspr/2",
                    "kspr/3 -> kspr/4",
                ),
                gitHub.getPullRequests().map(PullRequest::headToBaseString).toSet(),
            )

            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "1" }
                        commit { title = "2" }
                        commit { title = "3" }
                        commit {
                            title = "4"
                            localRefs += "development"
                        }
                    }
                },
            )

            gitKspr.push()

            gitLogLocalAndRemote()

            assertEquals(
                setOf(
                    "kspr/1 -> main",
                    "kspr/2 -> kspr/1",
                    "kspr/3 -> kspr/2",
                    "kspr/4 -> kspr/3",
                ),
                gitHub.getPullRequests().map(PullRequest::headToBaseString).toSet(),
            )
        }
    }

    @Test
    fun `push fails when multiple PRs for a given commit ID exist`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            remoteRefs += buildRemoteRef("one")
                        }
                        commit {
                            title = "two"
                            localRefs += "development"
                            remoteRefs += buildRemoteRef("two")
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "One PR"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "Two PR"
                    }
                },
            )
            val exception = assertThrows<GitKspr.SinglePullRequestPerCommitConstraintViolation> {
                gitKspr.push()
            }
            logger.info("Exception message: {}", exception.message)
        }
    }

    @Test
    fun `getRemoteCommitStatuses produces expected result`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "1"
                            localRefs += "development"
                        }
                    }
                },
            )
            gitKspr.push()
            val remoteCommitStatuses = gitKspr.getRemoteCommitStatuses()
            assertEquals(localGit.log("HEAD", maxCount = 1).single(), remoteCommitStatuses.single().remoteCommit)
        }
    }

    @Test
    fun `status none pushed`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit { title = "one" }
                        commit { title = "two" }
                        commit {
                            title = "three"
                            localRefs += "development"
                        }
                    }
                },
            )

            assertEquals(
                """
                    |[- - - - - -] one
                    |[- - - - - -] two
                    |[- - - - - -] three
                """
                    .trimMargin()
                    .toStatusString(),
                gitKspr.getAndPrintStatusString(),
            )
        }
    }

    @Test
    fun `status one pushed`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            remoteRefs += buildRemoteRef("one")
                        }
                        commit { title = "two" }
                        commit {
                            title = "three"
                            localRefs += "development"
                        }
                    }
                },
            )

            assertEquals(
                """
                    |[+ - - - - -] one
                    |[- - - - - -] two
                    |[- - - - - -] three
                """
                    .trimMargin()
                    .toStatusString(),
                gitKspr.getAndPrintStatusString(),
            )
        }
    }

    @Test
    fun `status one pull request`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            remoteRefs += buildRemoteRef("one")
                        }
                        commit { title = "two" }
                        commit {
                            title = "three"
                            localRefs += "development"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "one"
                    }
                },
            )

            assertEquals(
                """
                    |[+ + ? - - -] one
                    |[- - - - - -] two
                    |[- - - - - -] three
                """
                    .trimMargin()
                    .toStatusString(),
                gitKspr.getAndPrintStatusString(),
            )
        }
    }

    @Test
    fun `status one checks pass`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            remoteRefs += buildRemoteRef("one")
                            willPassVerification = true
                        }
                        commit { title = "two" }
                        commit {
                            title = "three"
                            localRefs += "development"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "one"
                    }
                },
            )

            assertEquals(
                """
                    |[+ + + - - -] one
                    |[- - - - - -] two
                    |[- - - - - -] three
                """
                    .trimMargin()
                    .toStatusString(),
                gitKspr.getAndPrintStatusString(),
            )
        }
    }

    @Test
    fun `approved one`() {
        withTestSetup {
            createCommitsFrom(
                testCase {
                    repository {
                        commit {
                            title = "one"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("one")
                        }
                        commit {
                            title = "two"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("two")
                        }
                        commit {
                            title = "three"
                            willPassVerification = true
                            remoteRefs += buildRemoteRef("three")
                            localRefs += "development"
                        }
                    }
                    pullRequest {
                        headRef = buildRemoteRef("one")
                        baseRef = "main"
                        title = "one"
                        willBeApprovedByUserKey = "michael"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("two")
                        baseRef = buildRemoteRef("one")
                        title = "two"
                    }
                    pullRequest {
                        headRef = buildRemoteRef("three")
                        baseRef = buildRemoteRef("two")
                        title = "three"
                    }
                },
            )

            assertEquals(
                """
                    |[+ + + + - -] one
                    |[+ + + - - -] two
                    |[+ + + - - -] three
                """
                    .trimMargin()
                    .toStatusString(),
                gitKspr.getAndPrintStatusString(),
            )
        }
    }

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
}

suspend fun GitKspr.getAndPrintStatusString() = getStatusString().also(::print)

// It may seem silly to repeat what is already defined in GitKspr.HEADER, but if a dev changes the header I want
// these tests to break so that any such changes are very deliberate. This is a compromise between referencing the
// same value from both tests and prod and the other extreme of repeating this header text manually in every test.
fun String.toStatusString() =
    """
            | ┌─ commit is pushed
            | │ ┌─ pull request exists
            | │ │ ┌─ github checks pass
            | │ │ │ ┌── pull request approved
            | │ │ │ │ ┌─── no merge conflicts
            | │ │ │ │ │ ┌──── stack check
            | │ │ │ │ │ │
            |$this

    """.trimMargin()
